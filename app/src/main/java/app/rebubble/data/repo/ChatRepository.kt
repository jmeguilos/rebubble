package app.rebubble.data.repo

import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.ContactDao
import app.rebubble.data.local.dao.HandleDao
import app.rebubble.data.local.entity.HandleEntity
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
)

/**
 * Read path for the conversation list. Combines [ChatDao.observeChats],
 * [ContactDao.observeContacts], and a single [HandleDao.observeAllChatParticipants] join so each
 * emission is one participants query for the whole list (not N+1 [HandleDao.participantsFor]).
 *
 * Title resolution: [resolveChatTitle]. [ChatListItem.isGroup] is `style == 43`
 * (BlueBubbles group chat style — [GROUP_CHAT_STYLE]).
 *
 * Chat refresh / reconciler upserts are owned elsewhere — this repository does not call the API.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val contactDao: ContactDao,
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
                )
            }
        }.distinctUntilChanged()
}
