package app.rebubble.data.sync

import android.util.Log
import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.HandleDao
import app.rebubble.data.local.dao.MessageDao
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.ChatHandleCrossRef
import app.rebubble.data.local.entity.HandleEntity
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.apiCall
import app.rebubble.data.remote.dto.ChatDto
import app.rebubble.data.remote.dto.requests.ChatQueryRequest
import app.rebubble.data.remote.dto.requests.MessageQueryRequest
import app.rebubble.data.remote.dto.requests.WhereClause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val LOG_TAG = "Reconciler"

/** Default page size for both the chat-query and message-query passes; see [Reconciler]'s KDoc. */
const val DEFAULT_RECONCILE_PAGE_LIMIT = 1000

/**
 * Outcome of one [Reconciler.reconcile] call. Always returned, never thrown from
 * [Reconciler.reconcile] itself — see that function's KDoc.
 *
 * @property newMessageGuids guids of messages newly inserted by this reconcile that were not sent
 *   by this user (`isFromMe == false`), i.e. candidates for a local notification. Ordered by
 *   ingestion order (oldest page first; ascending `ROWID` within a page).
 * @property error the exception that stopped this reconcile early, or `null` on a fully clean run.
 *   When non-null, [newMessageGuids] and the persisted watermark still reflect whatever pages
 *   were successfully ingested *before* the failure (partial progress is preserved, never rolled
 *   back and never silently dropped).
 */
data class SyncOutcome(val newMessageGuids: List<String>, val error: Throwable?)

/**
 * The reliability guarantee for message delivery: makes the local database eventually consistent
 * with the BlueBubbles server even when realtime delivery (socket/FCM) fails or was missed
 * entirely (app killed, device offline, etc). Invoked on FCM wake, app foreground, socket
 * reconnect (T12), and periodically via WorkManager (T13).
 *
 * [reconcile] runs two passes, in order:
 *
 *  1. **Chat pass** — pages through `POST /chat/query` (`sort=lastmessage`) picking up brand-new
 *     chats and metadata changes (renames, archive toggles) on known ones. New chats are seeded
 *     via [ChatDao.insertIgnore]; known chats are refreshed via [ChatDao.updateMetadata], which
 *     deliberately leaves `lastMessageDate`/`lastMessagePreview` alone. This never uses
 *     [ChatDao.upsert] (that's a `REPLACE`, flagged by T4/T6 as unsafe here: it would clobber a
 *     known chat's denormalized preview with nulls). The preview columns are instead advanced from
 *     each row's `lastMessage` via [seedPreviewFromLastMessage] — only-if-newer, message row not
 *     ingested — which is what gives a fresh install non-blank previews. Participants are upserted
 *     alongside via [HandleDao.upsert] + [HandleDao.upsertChatHandleCrossRefs].
 *  2. **Message pass** — reads the persisted watermark ([SyncWatermarkStore.get]). A `null`
 *     watermark means sync hasn't been initialized yet (expected to happen once at onboarding, via
 *     [SyncWatermarkStore.initializeIfAbsent]) — outside that window there is nothing to reconcile
 *     from, so this pass is skipped entirely (the chat pass above still runs). Otherwise, this
 *     pages through `POST /message/query` (`where: ROWID > watermark`, `sort=ASC`), feeding every
 *     page to [MessageIngestor.ingest] and only advancing the watermark to that page's
 *     [IngestResult.maxRowId] *after* the page's transaction has committed — so a failure on page
 *     *N* leaves the watermark at page *N-1*'s max, never advanced past a page that wasn't fully
 *     ingested, and never regressed either ([SyncWatermarkStore.set] is only called when the new
 *     max is strictly greater than the current watermark). Pagination always re-queries at
 *     `offset=0` with the *advanced* watermark rather than incrementing an offset: the watermark
 *     predicate means every already-ingested row drops out of the next page's result set, so
 *     offset-based paging would be redundant and unsafe if rows ever shifted between calls.
 *
 * [reconcile] **never throws**: any exception raised mid-pass (network failure, server error,
 * ingest failure) is caught and reported via [SyncOutcome.error], with [SyncOutcome.newMessageGuids]
 * and the persisted watermark reflecting whatever progress was made first. [CancellationException]
 * is the one exception never captured this way — it always propagates, so cooperative coroutine
 * cancellation (e.g. the caller's scope going away) keeps working normally.
 *
 * A [Mutex] serializes overlapping calls to this same [Reconciler] instance (an FCM wake racing an
 * app-open, for instance): the second caller's pass only starts once the first has fully returned,
 * so two reconciles can never interleave their message-pass pages against the same watermark.
 */
class Reconciler(
    private val api: BlueBubblesApi,
    private val watermarkStore: SyncWatermarkStore,
    private val ingestor: MessageIngestor,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageDao: MessageDao,
    private val pageLimit: Int = DEFAULT_RECONCILE_PAGE_LIMIT,
) {
    private val mutex = Mutex()

    suspend fun reconcile(): SyncOutcome = mutex.withLock {
        val newMessageGuids = mutableListOf<String>()
        try {
            runChatPass()
            runMessagePass(newMessageGuids)
            SyncOutcome(newMessageGuids, null)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(LOG_TAG, "reconcile stopped early; returning partial progress", failure)
            SyncOutcome(newMessageGuids, failure)
        }
    }

    // --- chat pass --------------------------------------------------------------------------

    private suspend fun runChatPass() {
        var offset = 0
        while (true) {
            val page = apiCall {
                api.queryChats(
                    ChatQueryRequest(
                        with = listOf("lastMessage", "participants"),
                        sort = "lastmessage",
                        offset = offset,
                        limit = pageLimit,
                    )
                )
            }

            upsertChats(page)

            if (page.size < pageLimit) break
            offset += page.size
        }
    }

    private suspend fun upsertChats(chats: List<ChatDto>) {
        if (chats.isEmpty()) return

        // Seed brand-new chats only (insertIgnore no-ops for a guid that already exists) with an
        // empty preview, then refresh metadata on every chat in the page — new or known — without
        // ever touching lastMessageDate/lastMessagePreview. See this class's KDoc for why insertIgnore
        // + updateMetadata is used here instead of ChatDao.upsert.
        chatDao.insertIgnore(
            chats.map { c ->
                ChatEntity(
                    guid = c.guid,
                    style = c.style,
                    chatIdentifier = c.chatIdentifier,
                    displayName = c.displayName,
                    isArchived = c.isArchived,
                    lastMessageDate = null,
                    lastMessagePreview = null,
                )
            }
        )
        chats.forEach { c ->
            chatDao.updateMetadata(
                guid = c.guid,
                displayName = c.displayName,
                chatIdentifier = c.chatIdentifier,
                style = c.style,
                isArchived = c.isArchived,
            )
            seedPreviewFromLastMessage(c)
        }

        val participants = chats.flatMap { c -> c.participants.map { c.guid to it } }
        if (participants.isEmpty()) return

        handleDao.upsert(
            participants.map { (_, handle) -> HandleEntity(address = handle.address, service = handle.service) }
        )
        handleDao.upsertChatHandleCrossRefs(
            participants.map { (chatGuid, handle) -> ChatHandleCrossRef(chatGuid = chatGuid, address = handle.address) }
        )
    }

    /**
     * Fills `lastMessageDate`/`lastMessagePreview` from the `lastMessage` the chat query already
     * returns (`with=lastMessage`).
     *
     * This is what gives a **fresh install** previews: onboarding initializes the watermark at the
     * server's current max, so the message pass never re-fetches history and the list would
     * otherwise sit there showing every conversation with a blank second line until the user opened
     * each one. The message row itself is deliberately *not* ingested here — history is the
     * backfill's job ([app.rebubble.data.repo.MessageRepository.loadOlder]) — only the denormalized
     * preview columns are touched.
     *
     * Reactions are skipped for the same reason [MessageIngestor] skips them (a tapback is not a
     * conversation preview), and [ChatDao.updatePreview]'s only-if-newer predicate keeps a locally
     * ingested newer message from being overwritten by a staler server `lastMessage`.
     */
    private suspend fun seedPreviewFromLastMessage(chat: ChatDto) {
        val last = chat.lastMessage ?: return
        val mapped = MessageMapper.toEntity(last, chat.guid)
        if (mapped.associatedMessageType != null) return
        if (mapped.dateCreated <= 0L) return
        chatDao.updatePreview(chat.guid, mapped.dateCreated, MessageMapper.previewFor(last, mapped))
    }

    // --- message pass -----------------------------------------------------------------------

    private suspend fun runMessagePass(collected: MutableList<String>) {
        var watermark = watermarkStore.get() ?: return

        while (true) {
            val page = apiCall {
                api.queryMessages(
                    MessageQueryRequest(
                        with = listOf("chats", "attachments", "handles"),
                        where = listOf(rowIdGreaterThan(watermark)),
                        sort = "ASC",
                        limit = pageLimit,
                        offset = 0,
                    )
                )
            }

            val result = ingestor.ingest(page, IngestSource.RECONCILE)

            // insertedGuids includes isFromMe rows (this reconciler's own outgoing sends,
            // ack'd late via the message pass rather than SEND_ACK); the outcome's contract is
            // notification-worthy *incoming* messages only, so filter those out here by
            // re-reading the just-inserted rows.
            result.insertedGuids.forEach { guid ->
                if (messageDao.getByGuid(guid)?.isFromMe == false) collected += guid
            }

            val newWatermark = result.maxRowId
            if (newWatermark != null && newWatermark > watermark) {
                watermarkStore.set(newWatermark)
                watermark = newWatermark
            }

            if (page.size < pageLimit) break
        }
    }

    private fun rowIdGreaterThan(watermark: Long) = WhereClause(
        statement = "message.ROWID > :rowid",
        args = JsonObject(mapOf("rowid" to JsonPrimitive(watermark))),
    )
}
