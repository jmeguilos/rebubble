package app.rebubble.ui.newchat

import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import app.rebubble.data.repo.ChatRepository
import app.rebubble.data.sync.MessageIngestor
import app.rebubble.notifications.ActiveChatTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.robolectric.annotation.Config

/**
 * [NewChatViewModel.send]: validation guard, in-flight state, success navigation event, and
 * failure state. Exercises the real [ChatRepository] against a [MockWebServer] (same convention
 * as [app.rebubble.ui.chat.ChatViewModelTest]) since [ChatRepository] isn't `open` for a test
 * subclass -- there's nothing in it worth faking here anyway, its only collaborators are Room and
 * [BlueBubblesApi].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class NewChatViewModelTest {

    private lateinit var db: RebubbleDatabase
    private lateinit var server: MockWebServer
    private lateinit var api: BlueBubblesApi
    private lateinit var chatRepository: ChatRepository
    private lateinit var viewModel: NewChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = InMemoryDatabaseFactory.create()
        server = MockWebServer()
        server.start()
        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw",
        )
        api = testBlueBubblesApi(credentials)
        chatRepository = ChatRepository(
            chatDao = db.chatDao(),
            handleDao = db.handleDao(),
            contactDao = db.contactDao(),
            api = api,
            ingestor = MessageIngestor(
                db = db,
                messageDao = db.messageDao(),
                chatDao = db.chatDao(),
                attachmentDao = db.attachmentDao(),
                handleDao = db.handleDao(),
                activeChatTracker = ActiveChatTracker(),
            ),
        )
        viewModel = NewChatViewModel(chatRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        runCatching { server.shutdown() }
        db.close()
    }

    private fun createChatResponseJson() = """
        {"status":200,"message":"Successfully created chat!","data":{
          "originalROWID":14,"guid":"iMessage;-;+15559998888","style":45,
          "chatIdentifier":"+15559998888","displayName":null,"isArchived":false,
          "participants":[],
          "messages":[{"originalROWID":701,"guid":"p:0/created-701","text":"hi","isFromMe":true,
            "dateCreated":1752347000000}]
        }}
    """.trimIndent()

    // --- 1. validation matrix ----------------------------------------------------------------

    @Test
    fun `isValidNewChatRecipient accepts email-ish and 7+ digit phone inputs`() {
        assertTrue(isValidNewChatRecipient("friend@example.com"))
        assertTrue(isValidNewChatRecipient("+1 (555) 010-0001"))
        assertTrue(isValidNewChatRecipient("5550100"))
    }

    @Test
    fun `isValidNewChatRecipient rejects blank, whitespace-only, and short-digit inputs`() {
        assertFalse(isValidNewChatRecipient(""))
        assertFalse(isValidNewChatRecipient("   "))
        assertFalse(isValidNewChatRecipient("12345"))
        assertFalse(isValidNewChatRecipient("no digits or at sign"))
    }

    // --- 2. send() validation guard (no request, no state change) ----------------------------

    @Test
    fun `send with an invalid recipient is a no-op`() = runBlocking {
        viewModel.send(recipient = "12345", message = "hi")
        assertEquals(0, server.requestCount)
        assertEquals(NewChatUiState(), viewModel.uiState.value)
    }

    @Test
    fun `send with a blank message is a no-op`() = runBlocking {
        viewModel.send(recipient = "+15559998888", message = "   ")
        assertEquals(0, server.requestCount)
        assertEquals(NewChatUiState(), viewModel.uiState.value)
    }

    // --- 3. in-flight state ---------------------------------------------------------------------

    @Test
    fun `send flips isSending true immediately, before the response arrives`() = runBlocking {
        // No response enqueued yet: the request is in flight (MockWebServer blocks awaiting one),
        // so isSending must already be true by the time send() returns control to the caller.
        viewModel.send(recipient = "+15559998888", message = "hi")

        assertTrue(viewModel.uiState.value.isSending)
        assertNull(viewModel.uiState.value.errorMessage)

        server.enqueue(MockResponse().setResponseCode(200).setBody(createChatResponseJson()))
        val finalState = withTimeout(10_000) { viewModel.uiState.first { !it.isSending } }
        assertFalse(finalState.isSending)
    }

    // --- 4. success navigation event -------------------------------------------------------------

    @Test
    fun `send emits NavigateToChat with the new guid on success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(createChatResponseJson()))

        val eventDeferred = async {
            withTimeout(10_000) { viewModel.events.first() }
        }
        viewModel.send(recipient = "+15559998888", message = "hi")

        val event = eventDeferred.await()
        assertTrue(event is NewChatEvent.NavigateToChat)
        assertEquals("iMessage;-;+15559998888", (event as NewChatEvent.NavigateToChat).chatGuid)
        assertFalse(viewModel.uiState.value.isSending)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    // --- 5. failure state -----------------------------------------------------------------------

    @Test
    fun `send surfaces a server error inline and does not navigate`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody(
                """{"status":500,"message":"Server Error","error":{"type":"Server Error","message":"Failed to create chat!"}}"""
            )
        )

        viewModel.send(recipient = "+15559998888", message = "hi")

        withTimeout(10_000) { viewModel.uiState.first { !it.isSending } }
        assertEquals("Failed to create chat!", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isSending)
    }

    @Test
    fun `send surfaces a 401 as a settings-password hint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401,"message":"Unauthorized"}"""))

        viewModel.send(recipient = "+15559998888", message = "hi")

        withTimeout(10_000) { viewModel.uiState.first { !it.isSending } }
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("password", ignoreCase = true))
    }

    @Test
    fun `clearError resets errorMessage`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401,"message":"Unauthorized"}"""))
        viewModel.send(recipient = "+15559998888", message = "hi")
        withTimeout(10_000) { viewModel.uiState.first { !it.isSending } }
        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.errorMessage)
    }

    // --- 6. service selection ---------------------------------------------------------------

    @Test
    fun `onServiceSelected updates the selected service`() {
        assertEquals(NewChatService.IMessage, viewModel.uiState.value.service)
        viewModel.onServiceSelected(NewChatService.Sms)
        assertEquals(NewChatService.Sms, viewModel.uiState.value.service)
    }
}
