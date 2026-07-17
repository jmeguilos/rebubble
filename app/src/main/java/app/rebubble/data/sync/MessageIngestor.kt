package app.rebubble.data.sync

import android.util.Log
import androidx.room.withTransaction
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.dao.AttachmentDao
import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.HandleDao
import app.rebubble.data.local.dao.MessageDao
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.ChatHandleCrossRef
import app.rebubble.data.local.entity.HandleEntity
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.data.remote.dto.ChatDto
import app.rebubble.data.remote.dto.MessageDto

/** Where a batch of [MessageDto]s came from; recorded for diagnostics/behavioural tweaks. */
enum class IngestSource { SOCKET, FCM, RECONCILE, SEND_ACK, BACKFILL }

/**
 * Outcome of one [MessageIngestor.ingest] batch.
 *
 * @property insertedGuids guids of rows *newly inserted* this batch (merges and temp-guid swaps are
 *   counted in [updated], never here).
 * @property updated number of existing rows merged or swapped.
 * @property maxRowId the largest `originalROWID` seen across the input batch (null if none carried
 *   one) — the reconciler's high-water mark. Includes unroutable/skipped dtos so a message the
 *   client can never store does not stall the reconciler's watermark.
 */
data class IngestResult(
    val insertedGuids: List<String>,
    val updated: Int,
    val maxRowId: Long?,
)

/**
 * The single idempotent convergence point where every inbound message path (FCM, socket,
 * reconcile, send-ack, backfill) meets Room. The whole batch runs inside one [withTransaction] so a
 * partially-processed batch can never be observed and an unroutable dto leaves no partial writes.
 *
 * Per message, exactly one of four outcomes:
 *  1. **merge** — a row already exists with the same guid: prefer non-null server fields, never
 *     regress the server timestamps to null, force `sendStatus = SENT`, keep local-only state.
 *  2. **swap** — `tempGuid` is set and matches an optimistic local row: rewrite its primary key to
 *     the real guid ([MessageDao.swapGuid]), re-parent its attachments ([AttachmentDao.reparent]),
 *     then merge the server fields on top.
 *  3. **insert** — a brand-new row, plus its sender handle, chat cross-ref, and attachments.
 *  4. **skip** — no chat can be resolved (no `chats[]`, no `fallbackChatGuid`, no existing row): the
 *     message is logged and dropped with no writes.
 *
 * After a successful insert/merge/swap the referenced chat's denormalized
 * `lastMessageDate`/`lastMessagePreview` are advanced via [ChatDao.updatePreview] (only-if-newer).
 */
class MessageIngestor(
    private val db: RebubbleDatabase,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val attachmentDao: AttachmentDao,
    private val handleDao: HandleDao,
) {

    /**
     * @param fallbackChatGuid chat guid to route to when a dto carries no `chats[]` (used by the
     *   send path, which already knows the target chat for its SEND_ACK).
     */
    suspend fun ingest(
        dtos: List<MessageDto>,
        source: IngestSource,
        fallbackChatGuid: String? = null,
    ): IngestResult {
        if (dtos.isEmpty()) return IngestResult(emptyList(), 0, null)

        // "Seen in the batch" includes skipped dtos on purpose (see [IngestResult.maxRowId]).
        val maxRowId = dtos.mapNotNull { it.originalRowId }.maxOrNull()

        return db.withTransaction {
            val inserted = mutableListOf<String>()
            var updated = 0

            for (dto in dtos) {
                val existingByGuid = messageDao.getByGuid(dto.guid)
                val existingByTemp = if (existingByGuid == null && dto.tempGuid != null) {
                    messageDao.getByGuid(dto.tempGuid)
                } else {
                    null
                }

                val chatGuid = dto.chats?.firstOrNull()?.guid
                    ?: fallbackChatGuid
                    ?: existingByGuid?.chatGuid
                    ?: existingByTemp?.chatGuid
                if (chatGuid == null) {
                    Log.w(
                        LOG_TAG,
                        "skipping unroutable message guid=${dto.guid} (no chats[], no fallback, " +
                            "no existing row); source=$source",
                    )
                    continue
                }

                // Seed the chat only if it's new; never REPLACE (that would clobber the denormalized
                // preview of a known chat).
                seedChatsIfAbsent(dto.chats)

                val mapped = MessageMapper.toEntity(dto, chatGuid)
                upsertSender(dto, chatGuid)

                when {
                    existingByGuid != null -> {
                        messageDao.update(merge(existingByGuid, mapped))
                        updated++
                    }
                    existingByTemp != null -> {
                        val realGuid = dto.guid
                        val originalRowId = mapped.originalRowId ?: existingByTemp.originalRowId
                        // Re-parent attachments and rewrite the PK before merging server fields on.
                        attachmentDao.reparent(oldMessageGuid = existingByTemp.guid, newMessageGuid = realGuid)
                        messageDao.swapGuid(
                            tempGuid = existingByTemp.guid,
                            realGuid = realGuid,
                            originalRowId = originalRowId,
                            sendStatus = SendStatus.SENT,
                        )
                        val merged = merge(existingByTemp, mapped)
                            .copy(guid = realGuid, originalRowId = originalRowId, sendStatus = SendStatus.SENT)
                        messageDao.update(merged)
                        updated++
                    }
                    else -> {
                        messageDao.insertAll(listOf(mapped))
                        inserted += mapped.guid
                    }
                }

                // Attachments (IGNORE) so a re-insert keeps any locally-set localPath/downloadState.
                val attachments = MessageMapper.toAttachmentEntities(dto, messageGuid = dto.guid)
                if (attachments.isNotEmpty()) attachmentDao.insertAll(attachments)

                chatDao.updatePreview(chatGuid, mapped.dateCreated, previewFor(dto, mapped))
            }

            IngestResult(insertedGuids = inserted, updated = updated, maxRowId = maxRowId)
        }
    }

    private suspend fun seedChatsIfAbsent(chats: List<ChatDto>?) {
        val minimal = chats.orEmpty().map { c ->
            ChatEntity(
                guid = c.guid,
                style = c.style,
                chatIdentifier = c.chatIdentifier,
                displayName = c.displayName,
                isArchived = false,
                lastMessageDate = null,
                lastMessagePreview = null,
            )
        }
        if (minimal.isNotEmpty()) chatDao.insertIgnore(minimal)
    }

    private suspend fun upsertSender(dto: MessageDto, chatGuid: String) {
        val handle = dto.handle ?: return
        handleDao.upsert(listOf(HandleEntity(address = handle.address, service = handle.service)))
        handleDao.upsertChatHandleCrossRefs(
            listOf(ChatHandleCrossRef(chatGuid = chatGuid, address = handle.address)),
        )
    }

    /**
     * Merge server fields onto an existing row: prefer a non-null server value, but never regress
     * the four server timestamps back to null once seen (take the max of old/new), and always
     * settle the row to [SendStatus.SENT]. `guid` and any local-only state are inherited from
     * [existing].
     */
    private fun merge(existing: MessageEntity, mapped: MessageEntity): MessageEntity = existing.copy(
        chatGuid = mapped.chatGuid,
        originalRowId = mapped.originalRowId ?: existing.originalRowId,
        text = mapped.text ?: existing.text,
        subject = mapped.subject ?: existing.subject,
        isFromMe = mapped.isFromMe,
        senderAddress = mapped.senderAddress ?: existing.senderAddress,
        dateCreated = if (mapped.dateCreated != 0L) mapped.dateCreated else existing.dateCreated,
        dateRead = maxOfNullable(existing.dateRead, mapped.dateRead),
        dateDelivered = maxOfNullable(existing.dateDelivered, mapped.dateDelivered),
        error = if (mapped.error != 0) mapped.error else existing.error,
        itemType = if (mapped.itemType != 0) mapped.itemType else existing.itemType,
        groupActionType = if (mapped.groupActionType != 0) mapped.groupActionType else existing.groupActionType,
        groupTitle = mapped.groupTitle ?: existing.groupTitle,
        associatedMessageGuid = mapped.associatedMessageGuid ?: existing.associatedMessageGuid,
        associatedMessageType = mapped.associatedMessageType ?: existing.associatedMessageType,
        threadOriginatorGuid = mapped.threadOriginatorGuid ?: existing.threadOriginatorGuid,
        expressiveSendStyleId = mapped.expressiveSendStyleId ?: existing.expressiveSendStyleId,
        dateEdited = maxOfNullable(existing.dateEdited, mapped.dateEdited),
        dateRetracted = maxOfNullable(existing.dateRetracted, mapped.dateRetracted),
        sendStatus = SendStatus.SENT,
    )

    /** Preview text: message text, else an attachment marker, else a group-event summary, else "". */
    private fun previewFor(dto: MessageDto, mapped: MessageEntity): String {
        val text = mapped.text?.takeIf { it.isNotBlank() }
        return when {
            text != null -> text
            dto.attachments.isNotEmpty() -> ATTACHMENT_PREVIEW
            mapped.itemType != 0 -> groupEventSummary(mapped)
            else -> ""
        }
    }

    private fun groupEventSummary(m: MessageEntity): String =
        m.groupTitle?.takeIf { it.isNotBlank() }?.let { "Named the conversation \"$it\"" } ?: "Group event"

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private companion object {
        const val LOG_TAG = "MessageIngestor"
        const val ATTACHMENT_PREVIEW = "📎 Attachment"
    }
}
