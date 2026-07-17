package app.rebubble.data.repo

import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.ContactDao
import app.rebubble.data.local.dao.HandleDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
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
)

/**
 * Read path for the conversation list. Combines [ChatDao.observeChats] with
 * [ContactDao.observeContacts] so a contact rename re-resolves titles; per emission, loads each
 * chat's participants via [HandleDao.participantsFor] (suspend) inside [mapLatest].
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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeChats(): Flow<List<ChatListItem>> =
        combine(
            chatDao.observeChats(),
            contactDao.observeContacts(),
        ) { chats, contacts -> chats to contacts }
            .mapLatest { (chats, contacts) ->
                val contactsByAddress = contacts.associateBy { it.address }
                chats.map { chat ->
                    val participants = handleDao.participantsFor(chat.guid)
                    ChatListItem(
                        guid = chat.guid,
                        title = resolveChatTitle(chat, participants, contactsByAddress),
                        isGroup = chat.style == GROUP_CHAT_STYLE,
                        lastMessageDate = chat.lastMessageDate,
                        lastMessagePreview = chat.lastMessagePreview,
                        style = chat.style,
                    )
                }
            }
}
