package app.rebubble.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import app.rebubble.MainActivity
import app.rebubble.R
import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.ContactDao
import app.rebubble.data.local.dao.HandleDao
import app.rebubble.data.local.dao.MessageDao
import app.rebubble.data.local.entity.ContactEntity
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.repo.GROUP_CHAT_STYLE
import app.rebubble.data.repo.resolveChatTitle
import app.rebubble.data.sync.NewMessageAlert
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts MessagingStyle notifications for newly ingested messages.
 *
 * ## Idempotency / re-alert (T13 KDoc)
 *
 * The same guid may arrive again (FCM insert + reconciler re-surface). Rebuilding the chat
 * notification from Room is naturally content-idempotent. To avoid a second buzz for the same
 * guid, we keep an in-memory map of the last-notified max [MessageEntity.dateCreated] per chat
 * and call [NotificationCompat.Builder.setOnlyAlertOnce] when nothing newer arrived.
 *
 * Avatar loading is skipped for M1 (Person has name only; no Icon).
 */
@Singleton
class MessageNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val contactDao: ContactDao,
    private val activeChatTracker: ActiveChatTracker,
) : NewMessageAlert {

    /** Per-chat max dateCreated we have already alerted on (process lifetime). */
    private val lastNotifiedMaxDate = ConcurrentHashMap<String, Long>()

    /**
     * Test-only: invoked after the atomic watermark decision and before posting, so concurrent
     * callers can be held until both have decided. Null in production.
     */
    internal var dedupeRaceGate: (suspend () -> Unit)? = null

    /** Test-only: observes every [NotificationManagerCompat.notify] call. Null in production. */
    internal var notificationPostedListener: ((id: Int, notification: android.app.Notification) -> Unit)? =
        null

    override suspend fun onNewMessages(guids: List<String>) {
        if (guids.isEmpty()) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val rows = messageDao.getByGuids(guids)
        val incoming = rows.filter { !it.isFromMe }
        if (incoming.isEmpty()) return

        val active = activeChatTracker.current.value
        val byChat = incoming.groupBy { it.chatGuid }

        var postedAny = false
        for ((chatGuid, chatIncoming) in byChat) {
            if (chatGuid == active) continue
            val maxIncoming = chatIncoming.maxOf { it.dateCreated }
            var onlyAlertOnce = false
            // Atomic read-decide-write so concurrent FCM + reconcile cannot both alert.
            lastNotifiedMaxDate.compute(chatGuid) { _, previous ->
                onlyAlertOnce = previous != null && maxIncoming <= previous
                if (onlyAlertOnce) previous else maxIncoming
            }
            dedupeRaceGate?.invoke()
            if (postChatNotification(chatGuid, onlyAlertOnce = onlyAlertOnce)) {
                postedAny = true
            }
        }
        if (postedAny) {
            postSummaryNotification()
        }
    }

    /**
     * Rebuilds the chat notification after an inline reply so the optimistic outbound row is
     * visible. Always [onlyAlertOnce] — the reply must not re-buzz.
     */
    suspend fun repostIncludingReply(chatGuid: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (postChatNotification(chatGuid, onlyAlertOnce = true)) {
            postSummaryNotification()
        }
    }

    private suspend fun postChatNotification(chatGuid: String, onlyAlertOnce: Boolean): Boolean {
        val chat = chatDao.getByGuid(chatGuid) ?: return false
        val history = messageDao.getRecentNonReaction(chatGuid, HISTORY_LIMIT)
        if (history.isEmpty()) return false

        val contactsByAddress = contactDao.getAll().associateBy { it.address }
        val participants = handleDao.participantsFor(chatGuid)
        val title = resolveChatTitle(chat, participants, contactsByAddress)
        val isGroup = chat.style == GROUP_CHAT_STYLE

        val user = Person.Builder().setName(USER_DISPLAY_NAME).build()
        val style = NotificationCompat.MessagingStyle(user)
            .setGroupConversation(isGroup)
        if (isGroup) {
            style.conversationTitle = title
        }

        // MessagingStyle wants chronological (oldest → newest); DAO returns newest-first.
        for (row in history.asReversed()) {
            style.addMessage(toStyleMessage(row, contactsByAddress))
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            chatGuid.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_CHAT_GUID, chatGuid)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val replyRemoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel(REPLY_LABEL)
            .build()
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            chatGuid.hashCode(),
            Intent(context, ReplyReceiver::class.java).apply {
                putExtra(EXTRA_CHAT_GUID, chatGuid)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            REPLY_LABEL,
            replyPendingIntent,
        )
            .addRemoteInput(replyRemoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        val notification = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(style)
            .setContentIntent(contentIntent)
            .addAction(replyAction)
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setGroup(NotificationChannels.GROUP_KEY)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        return try {
            val id = chatGuid.hashCode()
            NotificationManagerCompat.from(context).notify(id, notification)
            notificationPostedListener?.invoke(id, notification)
            true
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied on API 33+ — areNotificationsEnabled should catch most cases.
            Log.w(LOG_TAG, "notify failed for chat=$chatGuid", e)
            false
        }
    }

    private fun postSummaryNotification() {
        val summary = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(SUMMARY_TITLE)
            .setContentText(SUMMARY_TEXT)
            .setStyle(
                NotificationCompat.InboxStyle()
                    .setSummaryText(SUMMARY_TEXT),
            )
            .setGroup(NotificationChannels.GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, summary)
            notificationPostedListener?.invoke(SUMMARY_NOTIFICATION_ID, summary)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "summary notify failed", e)
        }
    }

    private fun toStyleMessage(
        row: MessageEntity,
        contactsByAddress: Map<String, ContactEntity>,
    ): NotificationCompat.MessagingStyle.Message {
        val text = row.text?.takeIf { it.isNotBlank() } ?: ATTACHMENT_PLACEHOLDER
        // null person ⇒ attributed to the MessagingStyle user ("You"). Avatars skipped for M1.
        val person: Person? = if (row.isFromMe) {
            null
        } else {
            val name = row.senderAddress
                ?.let { addr ->
                    contactsByAddress[addr]?.displayName?.takeIf { it.isNotBlank() } ?: addr
                }
                ?: UNKNOWN_SENDER
            Person.Builder().setName(name).build()
        }
        return NotificationCompat.MessagingStyle.Message(text, row.dateCreated, person)
    }

    companion object {
        const val EXTRA_CHAT_GUID = "chatGuid"
        const val KEY_REPLY = "reply"

        private const val LOG_TAG = "MessageNotifier"
        private const val HISTORY_LIMIT = 10
        private const val USER_DISPLAY_NAME = "You"
        private const val UNKNOWN_SENDER = "Unknown"
        private const val REPLY_LABEL = "Reply"
        private const val ATTACHMENT_PLACEHOLDER = "Attachment"
        private const val SUMMARY_TITLE = "Rebubble"
        private const val SUMMARY_TEXT = "Messages"
        const val SUMMARY_NOTIFICATION_ID = 0x52424C45 // "RBLE"
    }
}
