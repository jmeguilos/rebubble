package app.rebubble.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.ChatHandleCrossRef
import app.rebubble.data.local.entity.ContactEntity
import app.rebubble.data.local.entity.HandleEntity
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.data.outbox.OutboxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T15 notification pipeline: MessagingStyle from Room, active-chat suppression, group title,
 * duplicate-guid onlyAlertOnce, ReplyReceiver → sendText + repost, isFromMe-only skip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class MessageNotifierTest {

    private lateinit var context: Context
    private lateinit var db: RebubbleDatabase
    private lateinit var activeChatTracker: ActiveChatTracker
    private lateinit var notifier: MessageNotifier
    private lateinit var notificationManager: NotificationManager
    private lateinit var outbox: OutboxRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        NotificationChannels.ensureCreated(context)

        db = InMemoryDatabaseFactory.create()
        activeChatTracker = ActiveChatTracker()
        notifier = MessageNotifier(
            context = context,
            messageDao = db.messageDao(),
            chatDao = db.chatDao(),
            handleDao = db.handleDao(),
            contactDao = db.contactDao(),
            activeChatTracker = activeChatTracker,
        )
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()

        outbox = OutboxRepository(
            context = context,
            messageDao = db.messageDao(),
            chatDao = db.chatDao(),
            attachmentDao = db.attachmentDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun chat(
        guid: String,
        style: Int = 45,
        displayName: String? = null,
        chatIdentifier: String? = "+15550001111",
    ) = ChatEntity(
        guid = guid,
        style = style,
        chatIdentifier = chatIdentifier,
        displayName = displayName,
        isArchived = false,
        lastMessageDate = null,
        lastMessagePreview = null,
    )

    private fun message(
        guid: String,
        chatGuid: String,
        text: String,
        dateCreated: Long,
        isFromMe: Boolean = false,
        senderAddress: String? = "+15550001111",
        associatedMessageType: String? = null,
    ) = MessageEntity(
        guid = guid,
        chatGuid = chatGuid,
        originalRowId = null,
        text = text,
        subject = null,
        isFromMe = isFromMe,
        senderAddress = senderAddress,
        dateCreated = dateCreated,
        dateRead = null,
        dateDelivered = null,
        groupTitle = null,
        associatedMessageGuid = null,
        associatedMessageType = associatedMessageType,
        threadOriginatorGuid = null,
        expressiveSendStyleId = null,
        dateEdited = null,
        dateRetracted = null,
        sendStatus = SendStatus.SENT,
    )

    private suspend fun seedDm(chatGuid: String = CHAT_GUID) {
        db.chatDao().upsert(listOf(chat(chatGuid)))
        db.handleDao().upsert(listOf(HandleEntity(address = "+15550001111", service = "iMessage")))
        db.handleDao().upsertChatHandleCrossRefs(
            listOf(ChatHandleCrossRef(chatGuid = chatGuid, address = "+15550001111")),
        )
        db.contactDao().upsert(
            listOf(ContactEntity(address = "+15550001111", displayName = "Alice", avatarPath = null)),
        )
    }

    private fun posted(chatGuid: String = CHAT_GUID): Notification? =
        shadowOf(notificationManager).getNotification(chatGuid.hashCode())

    private fun messagingStyle(notification: Notification): NotificationCompat.MessagingStyle =
        requireNotNull(
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification),
        )

    @Test
    fun `onNewMessages posts MessagingStyle with last message text on messages channel`() = runBlocking {
        seedDm()
        db.messageDao().insertAll(
            listOf(message("m1", CHAT_GUID, "hello from alice", dateCreated = 1_000L)),
        )

        notifier.onNewMessages(listOf("m1"))

        val notification = posted()
        assertNotNull(notification)
        assertEquals(NotificationChannels.MESSAGES, notification!!.channelId)

        val style = messagingStyle(notification)
        assertEquals("hello from alice", style.messages.last().text.toString())
        assertFalse(style.isGroupConversation)
    }

    @Test
    fun `active chat suppresses notification`() = runBlocking {
        seedDm()
        db.messageDao().insertAll(
            listOf(message("m1", CHAT_GUID, "suppressed", dateCreated = 1_000L)),
        )
        activeChatTracker.current.value = CHAT_GUID

        notifier.onNewMessages(listOf("m1"))

        assertNull(posted())
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `group chat sets isGroupConversation and conversation title`() = runBlocking {
        val groupGuid = "chat-group"
        db.chatDao().upsert(
            listOf(chat(groupGuid, style = 43, displayName = "Family Chat", chatIdentifier = "chat.family")),
        )
        db.handleDao().upsert(
            listOf(
                HandleEntity(address = "+15551110001", service = "iMessage"),
                HandleEntity(address = "+15551110002", service = "iMessage"),
            ),
        )
        db.handleDao().upsertChatHandleCrossRefs(
            listOf(
                ChatHandleCrossRef(chatGuid = groupGuid, address = "+15551110001"),
                ChatHandleCrossRef(chatGuid = groupGuid, address = "+15551110002"),
            ),
        )
        db.messageDao().insertAll(
            listOf(
                message(
                    "g1",
                    groupGuid,
                    "group hi",
                    dateCreated = 2_000L,
                    senderAddress = "+15551110001",
                ),
            ),
        )

        notifier.onNewMessages(listOf("g1"))

        val style = messagingStyle(requireNotNull(posted(groupGuid)))
        assertTrue(style.isGroupConversation)
        assertEquals("Family Chat", style.conversationTitle.toString())
    }

    @Test
    fun `duplicate guid re-delivery rebuilds notification with onlyAlertOnce`() = runBlocking {
        seedDm()
        db.messageDao().insertAll(
            listOf(message("m1", CHAT_GUID, "once", dateCreated = 1_000L)),
        )

        notifier.onNewMessages(listOf("m1"))
        val first = requireNotNull(posted())
        assertEquals(0, first.flags and Notification.FLAG_ONLY_ALERT_ONCE)

        notifier.onNewMessages(listOf("m1"))
        val second = requireNotNull(posted())
        assertEquals("once", messagingStyle(second).messages.last().text.toString())
        assertTrue(second.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun `isFromMe-only guid list posts nothing`() = runBlocking {
        seedDm()
        db.messageDao().insertAll(
            listOf(
                message(
                    "mine",
                    CHAT_GUID,
                    "from me",
                    dateCreated = 1_000L,
                    isFromMe = true,
                    senderAddress = null,
                ),
            ),
        )

        notifier.onNewMessages(listOf("mine"))

        assertNull(posted())
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `ReplyReceiver sendText and reposts notification containing reply`() = runBlocking {
        seedDm()
        db.messageDao().insertAll(
            listOf(message("m1", CHAT_GUID, "incoming", dateCreated = 1_000L)),
        )
        notifier.onNewMessages(listOf("m1"))
        assertNotNull(posted())

        val receiver = ReplyReceiver().apply {
            outboxRepository = outbox
            messageNotifier = notifier
            // unused by handleReply; set so the receiver is fully wired like production
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        }

        val intent = Intent(context, ReplyReceiver::class.java).putExtra(
            MessageNotifier.EXTRA_CHAT_GUID,
            CHAT_GUID,
        )
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(MessageNotifier.KEY_REPLY).build()),
            intent,
            Bundle().apply { putCharSequence(MessageNotifier.KEY_REPLY, "my reply") },
        )

        // handleReply (not onReceive) — Hilt's generated onReceive requires @HiltAndroidApp.
        receiver.handleReply(intent)

        assertTrue(
            db.messageDao().getRecentNonReaction(CHAT_GUID, 20)
                .any { it.isFromMe && it.text == "my reply" },
        )
        assertTrue(
            messagingStyle(requireNotNull(posted())).messages
                .any { it.text.toString() == "my reply" },
        )
    }

    private companion object {
        const val CHAT_GUID = "chat-1"
    }
}
