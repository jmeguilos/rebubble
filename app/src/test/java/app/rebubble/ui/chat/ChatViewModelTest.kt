package app.rebubble.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.ChatHandleCrossRef
import app.rebubble.data.local.entity.ContactEntity
import app.rebubble.data.local.entity.DownloadState
import app.rebubble.data.local.entity.HandleEntity
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.data.media.AttachmentCache
import app.rebubble.data.media.AttachmentDownloader
import app.rebubble.data.outbox.OutboxRepository
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import app.rebubble.data.repo.AttachmentRepository
import app.rebubble.data.repo.ChatRepository
import app.rebubble.data.repo.MessageRepository
import app.rebubble.data.sync.MessageIngestor
import app.rebubble.notifications.ActiveChatTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ChatViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: RebubbleDatabase
    private lateinit var server: MockWebServer
    private lateinit var api: BlueBubblesApi
    private lateinit var ingestor: MessageIngestor
    private lateinit var messageRepository: ControllableMessageRepository
    private lateinit var outbox: RecordingOutboxRepository
    private lateinit var attachmentRepository: RecordingAttachmentRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var activeChatTracker: ActiveChatTracker

    private val chatGuid = "iMessage;-;+15551234567"

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        db = InMemoryDatabaseFactory.create()
        server = MockWebServer()
        server.start()
        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw",
        )
        api = testBlueBubblesApi(credentials)
        ingestor = MessageIngestor(
            db = db,
            messageDao = db.messageDao(),
            chatDao = db.chatDao(),
            attachmentDao = db.attachmentDao(),
            handleDao = db.handleDao(),
        )
        messageRepository = ControllableMessageRepository(api, db, ingestor)
        outbox = RecordingOutboxRepository(context, db)
        attachmentRepository = RecordingAttachmentRepository(
            attachmentDao = db.attachmentDao(),
            downloader = AttachmentDownloader(context, api),
            cache = AttachmentCache(context, db.attachmentDao()),
        )
        chatRepository = ChatRepository(
            chatDao = db.chatDao(),
            handleDao = db.handleDao(),
            contactDao = db.contactDao(),
        )
        activeChatTracker = ActiveChatTracker()

        runBlocking {
            db.chatDao().upsert(
                listOf(
                    ChatEntity(
                        guid = chatGuid,
                        style = 45,
                        chatIdentifier = "+15551234567",
                        displayName = "Alex",
                        isArchived = false,
                        lastMessageDate = null,
                        lastMessagePreview = null,
                    ),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
    }

    private fun viewModel(guid: String = chatGuid) = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("guid" to guid)),
        messageRepository = messageRepository,
        outboxRepository = outbox,
        attachmentRepository = attachmentRepository,
        chatRepository = chatRepository,
        attachmentDao = db.attachmentDao(),
        contactDao = db.contactDao(),
        activeChatTracker = activeChatTracker,
    )

    private suspend fun awaitState(
        vm: ChatViewModel,
        predicate: (ChatUiState) -> Boolean,
    ): ChatUiState = withTimeout(5_000) {
        var last = vm.uiState.value
        if (predicate(last)) return@withTimeout last
        vm.uiState.first { state ->
            last = state
            predicate(state)
        }
    }

    private fun message(
        guid: String,
        dateCreated: Long,
        text: String? = "hi",
        sendStatus: SendStatus = SendStatus.SENT,
        isFromMe: Boolean = true,
    ) = MessageEntity(
        guid = guid,
        chatGuid = chatGuid,
        originalRowId = null,
        text = text,
        subject = null,
        isFromMe = isFromMe,
        senderAddress = if (isFromMe) null else "+1",
        dateCreated = dateCreated,
        dateRead = null,
        dateDelivered = null,
        groupTitle = null,
        associatedMessageGuid = null,
        associatedMessageType = null,
        threadOriginatorGuid = null,
        expressiveSendStyleId = null,
        dateEdited = null,
        dateRetracted = null,
        sendStatus = sendStatus,
    )

    @Test
    fun `sendText appends optimistic SENDING row via observeMessages`() = runBlocking {
        val vm = viewModel()
        awaitState(vm) { !it.loading }

        vm.sendText("hello world")

        val state = awaitState(vm) {
            it.items.any { item ->
                item is ChatUiItem.Bubble &&
                    item.message.text == "hello world" &&
                    item.message.sendStatus == SendStatus.SENDING
            }
        }
        val bubble = state.items.filterIsInstance<ChatUiItem.Bubble>()
            .first { it.message.text == "hello world" }
        assertTrue(bubble.message.guid.startsWith("temp-"))
        assertTrue(bubble.isSending)
        assertEquals(1, outbox.sendTextCalls.size)
    }

    @Test
    fun `FAILED bubble exposed and retry delegates to outbox`() = runBlocking {
        db.messageDao().insertAll(
            listOf(message("temp-deadbeef", dateCreated = 100L, text = "nope", sendStatus = SendStatus.FAILED)),
        )
        val vm = viewModel()
        val state = awaitState(vm) {
            it.items.any { item -> item is ChatUiItem.Bubble && item.isFailed }
        }
        val failed = state.items.filterIsInstance<ChatUiItem.Bubble>().first { it.isFailed }
        assertEquals("temp-deadbeef", failed.message.guid)

        vm.retry("temp-deadbeef")
        assertEquals(listOf("temp-deadbeef"), outbox.retryCalls)
    }

    @Test
    fun `loadOlder uses oldest dateCreated and guards concurrent plus endReached`() = runBlocking {
        db.messageDao().insertAll(
            listOf(
                message("m1", dateCreated = 100L),
                message("m2", dateCreated = 200L),
                message("m3", dateCreated = 50L),
            ),
        )
        val gate = CompletableDeferred<Unit>()
        messageRepository.blockLoadOlder = gate
        messageRepository.nextResult = 50

        val vm = viewModel()
        awaitState(vm) { it.items.filterIsInstance<ChatUiItem.Bubble>().size == 3 }

        val first = async { vm.loadOlder() }
        delay(20)
        vm.loadOlder() // concurrent — must not start a second repo call
        assertEquals(1, messageRepository.loadOlderCalls.size)

        gate.complete(Unit)
        first.await()
        delay(20)
        assertEquals(1, messageRepository.loadOlderCalls.size)
        assertEquals(50L, messageRepository.loadOlderCalls.single().beforeMs)

        // Short page → endReached; further calls no-op
        messageRepository.nextResult = 10
        messageRepository.blockLoadOlder = null
        vm.loadOlder()
        delay(20)
        assertEquals(2, messageRepository.loadOlderCalls.size)
        val afterEnd = awaitState(vm) { it.endReached }
        assertTrue(afterEnd.endReached)

        vm.loadOlder()
        delay(20)
        assertEquals(2, messageRepository.loadOlderCalls.size)
    }

    @Test
    fun `ActiveChatTracker set onEnter and cleared on onCleared`() = runBlocking {
        val vm = viewModel()
        assertNull(activeChatTracker.current.value)

        vm.onEnter()
        assertEquals(chatGuid, activeChatTracker.current.value)

        val store = ViewModelStore()
        store.put("chat", vm)
        store.clear()
        assertNull(activeChatTracker.current.value)
    }

    @Test
    fun `onExit clears tracker only for this chat`() = runBlocking {
        val vm = viewModel()
        vm.onEnter()
        activeChatTracker.current.value = "other-chat"
        vm.onExit()
        assertEquals("other-chat", activeChatTracker.current.value)

        vm.onEnter()
        vm.onExit()
        assertNull(activeChatTracker.current.value)
    }

    @Test
    fun `sendAttachment delegates to outbox`() = runBlocking {
        val vm = viewModel()
        val file = tempFolder.newFile("photo.jpg")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val uri = Uri.fromFile(file)

        vm.sendAttachment(uri)
        delay(50)
        assertEquals(1, outbox.sendAttachmentCalls.size)
        assertEquals(chatGuid, outbox.sendAttachmentCalls.single().first)
    }

    @Test
    fun `ensureDownloaded delegates to attachment repository`() = runBlocking {
        val vm = viewModel()
        vm.ensureDownloaded("att-1")
        delay(20)
        assertEquals(listOf("att-1"), attachmentRepository.ensureCalls)
    }

    @Test
    fun `loadOlder IOException sets transientError and allows retry`() = runBlocking {
        db.messageDao().insertAll(listOf(message("m1", dateCreated = 100L)))
        messageRepository.throwOnLoadOlder = IOException("offline")
        messageRepository.nextResult = 50

        val vm = viewModel()
        awaitState(vm) { it.items.filterIsInstance<ChatUiItem.Bubble>().size == 1 }

        vm.loadOlder()
        val errored = awaitState(vm) { it.transientError != null }
        assertEquals("Couldn't load older messages.", errored.transientError)
        assertFalse(errored.endReached)
        assertEquals(1, messageRepository.loadOlderCalls.size)

        vm.clearTransientError()
        messageRepository.throwOnLoadOlder = null
        vm.loadOlder()
        delay(20)
        assertEquals(2, messageRepository.loadOlderCalls.size)
        assertNull(vm.uiState.value.transientError)
    }

    @Test
    fun `sendAttachment failure sets transientError without crashing`() = runBlocking {
        outbox.throwOnSendAttachment = IOException("copy failed")
        val vm = viewModel()
        awaitState(vm) { !it.loading }

        val file = tempFolder.newFile("bad.jpg")
        file.writeBytes(byteArrayOf(1, 2, 3))
        vm.sendAttachment(Uri.fromFile(file))

        val errored = awaitState(vm) { it.transientError != null }
        assertEquals("Couldn't attach that file.", errored.transientError)
        assertFalse(errored.pendingScrollToBottom)
        assertEquals(1, outbox.sendAttachmentCalls.size)
    }

    @Test
    fun `sendText sets pendingScrollToBottom`() = runBlocking {
        val vm = viewModel()
        awaitState(vm) { !it.loading }
        assertFalse(vm.uiState.value.pendingScrollToBottom)

        vm.sendText("hello")
        val scrolled = awaitState(vm) { it.pendingScrollToBottom }
        assertTrue(scrolled.pendingScrollToBottom)

        vm.consumeScrollToBottom()
        val cleared = awaitState(vm) { !it.pendingScrollToBottom }
        assertFalse(cleared.pendingScrollToBottom)
    }

    @Test
    fun `incoming repository emission does not set pendingScrollToBottom`() = runBlocking {
        val vm = viewModel()
        awaitState(vm) { !it.loading }
        assertFalse(vm.uiState.value.pendingScrollToBottom)

        db.messageDao().insertAll(
            listOf(message("incoming-1", dateCreated = 500L, text = "hey", isFromMe = false)),
        )
        awaitState(vm) {
            it.items.any { item ->
                item is ChatUiItem.Bubble && item.message.guid == "incoming-1"
            }
        }
        assertFalse(vm.uiState.value.pendingScrollToBottom)
    }

    @Test
    fun `isSms true when guid starts with SMS`() = runBlocking {
        val smsGuid = "SMS;-;+15550001111"
        db.chatDao().upsert(
            listOf(
                ChatEntity(
                    guid = smsGuid,
                    style = 45,
                    chatIdentifier = "+15550001111",
                    displayName = "SMS Pal",
                    isArchived = false,
                    lastMessageDate = null,
                    lastMessagePreview = null,
                ),
            ),
        )
        val vm = viewModel(smsGuid)
        val state = awaitState(vm) { !it.loading }
        assertTrue(state.isSms)
        assertEquals("SMS Pal", state.title)
    }

    @Test
    fun `avatarPath and isGroup propagate from ChatMeta`() = runBlocking {
        val address = "+15551234567"
        val avatar = tempFolder.newFile("alex.png").absolutePath
        db.contactDao().upsert(
            listOf(
                ContactEntity(
                    address = address,
                    displayName = "Alex",
                    avatarPath = avatar,
                ),
            ),
        )
        db.handleDao().upsert(
            listOf(
                HandleEntity(
                    address = address,
                    service = "iMessage",
                ),
            ),
        )
        db.handleDao().upsertChatHandleCrossRefs(
            listOf(
                ChatHandleCrossRef(
                    chatGuid = chatGuid,
                    address = address,
                ),
            ),
        )

        val dm = viewModel()
        val dmState = awaitState(dm) { !it.loading && it.avatarPath == avatar }
        assertEquals(avatar, dmState.avatarPath)
        assertFalse(dmState.isGroup)
        assertEquals("Alex", dmState.title)

        val groupGuid = "iMessage;+;chat123"
        db.chatDao().upsert(
            listOf(
                ChatEntity(
                    guid = groupGuid,
                    style = 43,
                    chatIdentifier = "chat123",
                    displayName = "Ski trip",
                    isArchived = false,
                    lastMessageDate = null,
                    lastMessagePreview = null,
                ),
            ),
        )
        val groupVm = viewModel(groupGuid)
        val groupState = awaitState(groupVm) { !it.loading && it.title == "Ski trip" }
        assertTrue(groupState.isGroup)
        assertNull(groupState.avatarPath)
    }

    // --- test doubles ---

    private class ControllableMessageRepository(
        api: BlueBubblesApi,
        db: RebubbleDatabase,
        ingestor: MessageIngestor,
    ) : MessageRepository(api, db.messageDao(), ingestor) {
        data class Call(val chatGuid: String, val beforeMs: Long, val pageSize: Int)

        val loadOlderCalls = mutableListOf<Call>()
        var nextResult: Int = 50
        var blockLoadOlder: CompletableDeferred<Unit>? = null
        var throwOnLoadOlder: Throwable? = null
        private val inFlight = AtomicInteger(0)

        override suspend fun loadOlder(chatGuid: String, beforeMs: Long, pageSize: Int): Int {
            val n = inFlight.incrementAndGet()
            assertEquals("concurrent loadOlder", 1, n)
            try {
                loadOlderCalls += Call(chatGuid, beforeMs, pageSize)
                blockLoadOlder?.await()
                throwOnLoadOlder?.let { throw it }
                return nextResult
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private class RecordingOutboxRepository(
        context: Context,
        db: RebubbleDatabase,
    ) : OutboxRepository(context, db.messageDao(), db.chatDao(), db.attachmentDao()) {
        val sendTextCalls = mutableListOf<Pair<String, String>>()
        val sendAttachmentCalls = mutableListOf<Pair<String, Uri>>()
        val retryCalls = mutableListOf<String>()
        var throwOnSendAttachment: Throwable? = null

        override suspend fun sendText(chatGuid: String, text: String): String {
            sendTextCalls += chatGuid to text
            return super.sendText(chatGuid, text)
        }

        override suspend fun sendAttachment(
            chatGuid: String,
            uri: Uri,
            displayName: String?,
            mimeType: String?,
        ): String {
            sendAttachmentCalls += chatGuid to uri
            throwOnSendAttachment?.let { throw it }
            return super.sendAttachment(chatGuid, uri, displayName, mimeType)
        }

        override suspend fun retry(tempGuid: String) {
            retryCalls += tempGuid
            // Don't re-enqueue WorkManager for FAILED without attachments/text edge — just record.
        }
    }

    private class RecordingAttachmentRepository(
        attachmentDao: app.rebubble.data.local.dao.AttachmentDao,
        downloader: AttachmentDownloader,
        cache: AttachmentCache,
    ) : AttachmentRepository(attachmentDao, downloader, cache) {
        val ensureCalls = mutableListOf<String>()

        override suspend fun ensureDownloaded(guid: String): Result<String> {
            ensureCalls += guid
            return Result.success("/fake/$guid")
        }
    }
}
