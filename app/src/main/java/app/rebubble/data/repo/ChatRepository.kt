package app.rebubble.data.repo

import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.ContactDao
import app.rebubble.data.local.dao.HandleDao
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.ChatHandleCrossRef
import app.rebubble.data.local.entity.HandleEntity
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.apiCall
import app.rebubble.data.remote.dto.requests.CreateChatRequest
import app.rebubble.data.sync.IngestSource
import app.rebubble.data.sync.MessageIngestor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** One row in the conversation list, with a resolved display title. */
data class ChatListItem(
    val guid: String,
    val title: String,
    val isGroup: Boolean,
    val lastMessageDate: Long?,
    val lastMessagePreview: String?,
    val style: Int,
    /** Contact avatar path for 1:1 chats when known; null → monogram (or group treatment). */
    val avatarPath: String? = null,
    /** Local unread messages since the chat was last opened; `> 0` drives the row's badge. */
    val unreadCount: Int = 0,
)

/**
 * Read path for the conversation list. Combines [ChatDao.observeChats],
 * [ContactDao.observeContacts], and a single [HandleDao.observeAllChatParticipants] join so each
 * emission is one participants query for the whole list (not N+1 [HandleDao.participantsFor]).
 *
 * Title resolution: [resolveChatTitle]. [ChatListItem.isGroup] is `style == 43`
 * (BlueBubbles group chat style — [GROUP_CHAT_STYLE]).
 *
 * Chat refresh / reconciler upserts are owned elsewhere, except for [startChat] below, which is
 * this repository's one write path to the API.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val contactDao: ContactDao,
    private val api: BlueBubblesApi,
    private val ingestor: MessageIngestor,
) {

    fun observeChats(): Flow<List<ChatListItem>> =
        combine(
            chatDao.observeChats(),
            contactDao.observeContacts(),
            handleDao.observeAllChatParticipants(),
        ) { chats, contacts, participantRows ->
            val contactsByAddress = contacts.associateBy { it.address }
            val participantsByChat = participantRows
                .groupBy({ it.chatGuid }) { HandleEntity(address = it.address, service = it.service) }
            chats.map { chat ->
                val participants = participantsByChat[chat.guid].orEmpty()
                val isGroup = chat.style == GROUP_CHAT_STYLE
                ChatListItem(
                    guid = chat.guid,
                    title = resolveChatTitle(chat, participants, contactsByAddress),
                    isGroup = isGroup,
                    lastMessageDate = chat.lastMessageDate,
                    lastMessagePreview = chat.lastMessagePreview,
                    style = chat.style,
                    avatarPath = if (!isGroup) {
                        participants.firstOrNull()
                            ?.let { contactsByAddress[it.address]?.avatarPath }
                    } else {
                        null
                    },
                    unreadCount = chat.unreadCount,
                )
            }
        }.distinctUntilChanged()

    /**
     * Clear-on-open: zeroes [ChatListItem.unreadCount] for [chatGuid]. Called when the conversation
     * screen becomes active (alongside [app.rebubble.notifications.ActiveChatTracker]), which is
     * also the point from which the ingestor stops counting new arrivals for that chat.
     */
    suspend fun clearUnread(chatGuid: String) {
        chatDao.clearUnread(chatGuid)
    }

    /**
     * Creates a new chat (`POST /chat/new`) and, when the server echoes a sent message back on
     * it, ingests that message. Returns the new chat's guid on success.
     *
     * Failures are left as the [app.rebubble.data.remote.api.apiCall] typed exceptions
     * ([app.rebubble.data.remote.api.AuthError], [app.rebubble.data.remote.api.ApiException], or
     * a bare [java.io.IOException] for a network failure) -- same convention as
     * [app.rebubble.ui.onboarding.OnboardingViewModel]'s connect flow: this repository never
     * wraps or swallows them, it's the caller's job to catch and map to UI copy, and no local
     * write happens unless the call actually succeeds.
     *
     * Chat upsert mirrors [app.rebubble.data.sync.Reconciler.upsertChats]: [ChatDao.insertIgnore]
     * seeds the row only if it's new, then [ChatDao.updateMetadata] refreshes display fields --
     * never [ChatDao.upsert] (`REPLACE`), which would null out an existing chat's denormalized
     * `lastMessageDate`/`lastMessagePreview`. That can only matter here if the server ever
     * resolves [addresses] to a chat guid this client already knows (e.g. re-"starting" an
     * existing 1:1), so the guard costs nothing and avoids a latent regression.
     *
     * The embedded message (if any) carries no `chats[]` of its own -- see [ChatDto.messages]'s
     * KDoc -- so it's ingested with `fallbackChatGuid = ` the chat's own guid, exactly the seam
     * [app.rebubble.data.sync.MessageIngestor.ingest]'s KDoc describes for "the send path, which
     * already knows the target chat for its SEND_ACK".
     */
    suspend fun startChat(
        addresses: List<String>,
        message: String,
        service: String = "iMessage",
    ): String {
        val chat = apiCall {
            api.createChat(
                CreateChatRequest(
                    addresses = addresses,
                    message = message,
                    service = service,
                )
            )
        }

        chatDao.insertIgnore(
            listOf(
                ChatEntity(
                    guid = chat.guid,
                    style = chat.style,
                    chatIdentifier = chat.chatIdentifier,
                    displayName = chat.displayName,
                    isArchived = chat.isArchived,
                    lastMessageDate = null,
                    lastMessagePreview = null,
                )
            )
        )
        chatDao.updateMetadata(
            guid = chat.guid,
            displayName = chat.displayName,
            chatIdentifier = chat.chatIdentifier,
            style = chat.style,
            isArchived = chat.isArchived,
        )

        if (chat.participants.isNotEmpty()) {
            handleDao.upsert(
                chat.participants.map { HandleEntity(address = it.address, service = it.service) }
            )
            handleDao.upsertChatHandleCrossRefs(
                chat.participants.map {
                    ChatHandleCrossRef(chatGuid = chat.guid, address = it.address)
                }
            )
        }

        val sentMessages = chat.messages
        if (!sentMessages.isNullOrEmpty()) {
            ingestor.ingest(
                dtos = sentMessages,
                source = IngestSource.SEND_ACK,
                fallbackChatGuid = chat.guid,
            )
        }

        return chat.guid
    }
}
