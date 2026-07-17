package app.rebubble.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.TimeUnit

/**
 * [Reconciler] is the reliability guarantee that makes message delivery eventually consistent even
 * when realtime push fails. These tests exercise the chat-pass upsert-without-clobbering
 * behaviour, the message-pass pagination loop, per-page watermark advancement (and where it stops
 * on a mid-loop failure), the null-watermark skip, `isFromMe` filtering of the outcome, and mutex
 * serialization of overlapping [Reconciler.reconcile] calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ReconcilerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: RebubbleDatabase
    private lateinit var ingestor: MessageIngestor
    private lateinit var watermarkStore: SyncWatermarkStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = InMemoryDatabaseFactory.create()
        ingestor = MessageIngestor(
            db = db,
            messageDao = db.messageDao(),
            chatDao = db.chatDao(),
            attachmentDao = db.attachmentDao(),
            handleDao = db.handleDao(),
        )
        watermarkStore = SyncWatermarkStore(newWatermarkDataStore())
    }

    private fun newWatermarkDataStore(): DataStore<Preferences> {
        val file = File(tempFolder.newFolder(), "sync_state.preferences_pb")
        return PreferenceDataStoreFactory.create(produceFile = { file })
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        db.close()
    }

    // --- harness ----------------------------------------------------------------------------

    private fun reconciler(pageLimit: Int = 1000): Reconciler {
        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw",
        )
        return Reconciler(
            api = testBlueBubblesApi(credentials),
            watermarkStore = watermarkStore,
            ingestor = ingestor,
            chatDao = db.chatDao(),
            handleDao = db.handleDao(),
            messageDao = db.messageDao(),
            pageLimit = pageLimit,
        )
    }

    private suspend fun runReconcile(reconciler: Reconciler = reconciler()): SyncOutcome =
        withTimeout(10_000) { reconciler.reconcile() }

    private fun envelope(dataArrayJson: String) = """{"status":200,"message":"OK","data":$dataArrayJson}"""

    private fun enqueueChats(vararg chatsJson: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(envelope("[${chatsJson.joinToString(",")}]")))
    }

    private fun enqueueMessages(vararg messagesJson: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(envelope("[${messagesJson.joinToString(",")}]")))
    }

    private fun chatJson(
        guid: String,
        displayName: String? = null,
        style: Int = 45,
        chatIdentifier: String = "+15551234567",
    ): String {
        val displayNamePart = displayName?.let { "\"displayName\":\"$it\"," }.orEmpty()
        return """{"guid":"$guid","style":$style,"chatIdentifier":"$chatIdentifier",$displayNamePart"participants":[]}"""
    }

    private fun messageJson(
        guid: String,
        rowId: Long,
        isFromMe: Boolean = false,
        dateCreated: Long = 1000L,
        chatGuid: String = "chat-1",
    ): String = """
        {"originalROWID":$rowId,"guid":"$guid","text":"hi","isFromMe":$isFromMe,"dateCreated":$dateCreated,
         "chats":[{"guid":"$chatGuid","style":45,"chatIdentifier":"+15551234567"}]}
    """.trimIndent()

    private suspend fun messageCount(chatGuid: String = "chat-1"): Int =
        db.messageDao().observeMessages(chatGuid, limit = 10_000).first().size

    // --- 1. pagination loop runs until a short page; watermark ends at the global max --------

    @Test
    fun `message pass paginates until a short page, landing every message and advancing the watermark to the global max`() =
        runBlocking {
            watermarkStore.set(100L)
            enqueueChats() // empty chat page: 0 < limit, one iteration, nothing to upsert
            enqueueMessages(messageJson("m101", 101), messageJson("m102", 102), messageJson("m103", 103)) // full page
            enqueueMessages(messageJson("m104", 104), messageJson("m105", 105), messageJson("m106", 106)) // full page
            enqueueMessages(messageJson("m107", 107), messageJson("m108", 108)) // short page: loop stops

            val outcome = runReconcile(reconciler(pageLimit = 3))

            assertNull(outcome.error)
            assertEquals(8, messageCount())
            assertEquals(108L, watermarkStore.get())
            assertEquals(8, outcome.newMessageGuids.size)
        }

    // --- 2. watermark advances per page, post-commit; a mid-loop failure stops it there -------

    @Test
    fun `a failure on page 2 leaves the watermark at page 1's max and keeps page 1's messages`() = runBlocking {
        watermarkStore.set(0L)
        enqueueChats() // empty
        enqueueMessages(messageJson("m1", 1), messageJson("m2", 2), messageJson("m3", 3)) // full page (size == limit)
        // Page 2: force a connection-level failure (IOException), not an HTTP error response.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) // defensive 2nd, in case of a connection-reuse retry

        val outcome = runReconcile(reconciler(pageLimit = 3))

        assertNotNull(outcome.error)
        assertEquals(3L, watermarkStore.get())
        assertEquals(3, messageCount())
        assertEquals(listOf("m1", "m2", "m3"), outcome.newMessageGuids)
    }

    // --- 3. re-running after a clean pass ingests nothing new ---------------------------------

    @Test
    fun `re-running reconcile after a clean pass queries with the final watermark and ingests nothing new`() =
        runBlocking {
            watermarkStore.set(0L)
            enqueueChats()
            enqueueMessages(messageJson("m5", 5), messageJson("m7", 7)) // short page (2 < 10)
            val first = runReconcile(reconciler(pageLimit = 10))
            assertNull(first.error)
            assertEquals(7L, watermarkStore.get())

            enqueueChats()
            enqueueMessages() // empty: nothing past the new watermark
            val second = runReconcile(reconciler(pageLimit = 10))

            assertNull(second.error)
            assertTrue(second.newMessageGuids.isEmpty())
            assertEquals(2, messageCount()) // no duplicates
            assertEquals(7L, watermarkStore.get()) // unchanged

            // Exactly 4 requests total: chat+message pass, twice. The second message-pass request
            // must carry the watermark left by the first run.
            val requests = (1..4).map { server.takeRequest() }
            val secondMessageRequest = requests[3]
            assertTrue(secondMessageRequest.body.readUtf8().contains("\"rowid\":7"))
        }

    // --- 4. chat pass upserts a brand-new chat, never clobbers a known chat's preview ----------

    @Test
    fun `chat pass upserts a brand-new chat and never clobbers an existing chat's preview while updating its name`() =
        runBlocking {
            db.chatDao().upsert(
                listOf(
                    ChatEntity(
                        guid = "chat-1",
                        style = 45,
                        chatIdentifier = "+15551234567",
                        displayName = "Old Name",
                        isArchived = false,
                        lastMessageDate = 500L,
                        lastMessagePreview = "existing preview",
                    )
                )
            )
            // No watermark set -> message pass must be skipped, so only a chat-pass response is queued.
            enqueueChats(
                chatJson("chat-1", displayName = "New Name"),
                chatJson("chat-2", displayName = "Brand New", chatIdentifier = "+15559876543"),
            )

            val outcome = runReconcile()

            assertNull(outcome.error)
            val chat1 = db.chatDao().getByGuid("chat-1")
            assertEquals("New Name", chat1?.displayName)
            assertEquals("existing preview", chat1?.lastMessagePreview)
            assertEquals(500L, chat1?.lastMessageDate)

            val chat2 = db.chatDao().getByGuid("chat-2")
            assertNotNull(chat2)
            assertEquals("Brand New", chat2?.displayName)
        }

    // --- 5. overlapping reconcile calls serialize ----------------------------------------------

    @Test
    fun `overlapping reconcile calls serialize without corrupting the watermark or duplicating messages`() =
        runBlocking {
            watermarkStore.set(0L)
            enqueueChats() // call A's chat pass
            server.enqueue(
                // call A's message pass: slow response to widen the window where an unserialized
                // second caller could otherwise race ahead and interleave its own requests.
                MockResponse().setResponseCode(200)
                    .setBodyDelay(150, TimeUnit.MILLISECONDS)
                    .setBody(envelope("[${messageJson("m1", 1)},${messageJson("m2", 2)}]"))
            )
            enqueueChats() // call B's chat pass
            enqueueMessages() // call B's message pass: nothing new past watermark 2

            val sharedReconciler = reconciler(pageLimit = 10)
            val outcomes = withTimeout(10_000) {
                val a = async(Dispatchers.Default) { sharedReconciler.reconcile() }
                val b = async(Dispatchers.Default) { sharedReconciler.reconcile() }
                awaitAll(a, b)
            }

            assertTrue(outcomes.all { it.error == null })
            assertEquals(2, messageCount())
            assertEquals(2L, watermarkStore.get())
            assertEquals(4, server.requestCount)
        }

    // --- 6. newMessageGuids excludes isFromMe rows ---------------------------------------------

    @Test
    fun `newMessageGuids excludes isFromMe rows though they are still ingested`() = runBlocking {
        watermarkStore.set(0L)
        enqueueChats()
        enqueueMessages(messageJson("mine", 1, isFromMe = true), messageJson("theirs", 2, isFromMe = false))

        val outcome = runReconcile(reconciler(pageLimit = 10))

        assertNull(outcome.error)
        assertEquals(listOf("theirs"), outcome.newMessageGuids)
        assertEquals(2, messageCount())
    }

    // --- 7. null watermark skips the message pass but the chat pass still runs -----------------

    @Test
    fun `a null watermark skips the message pass entirely while the chat pass still runs`() = runBlocking {
        // watermarkStore never set -> get() is null.
        enqueueChats(chatJson("chat-new", displayName = "New Chat"))

        val outcome = runReconcile()

        assertNull(outcome.error)
        assertNull(watermarkStore.get())
        assertNotNull(db.chatDao().getByGuid("chat-new"))
        assertEquals(1, server.requestCount) // only the chat-pass request; no message-pass call attempted
    }
}
