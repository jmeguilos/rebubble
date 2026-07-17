package app.rebubble.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import app.rebubble.data.sync.MessageIngestor
import app.rebubble.data.sync.SyncWatermarkStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [MessageRepository] read path: [MessageRepository.observeMessages] delegates to Room;
 * [MessageRepository.loadOlder] pages `GET /chat/:guid/message` through
 * [MessageIngestor.ingest] with [IngestSource.BACKFILL] and must never touch the sync watermark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class MessageRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: RebubbleDatabase
    private lateinit var ingestor: MessageIngestor
    private lateinit var watermarkStore: SyncWatermarkStore
    private lateinit var repo: MessageRepository

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
        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw",
        )
        repo = MessageRepository(
            api = testBlueBubblesApi(credentials),
            messageDao = db.messageDao(),
            ingestor = ingestor,
        )
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

    private fun envelope(dataArrayJson: String) =
        """{"status":200,"message":"OK","data":$dataArrayJson}"""

    private fun messageJson(
        guid: String,
        text: String,
        dateCreated: Long,
        rowId: Long? = null,
        chatGuid: String = "chat-1",
    ): String {
        val rowPart = rowId?.let { "\"originalROWID\":$it," }.orEmpty()
        return """
            {$rowPart"guid":"$guid","text":"$text","isFromMe":false,"dateCreated":$dateCreated,
             "chats":[{"guid":"$chatGuid","style":45,"chatIdentifier":"+15551234567"}]}
        """.trimIndent()
    }

    private suspend fun seedChat() {
        db.chatDao().upsert(
            listOf(
                ChatEntity(
                    guid = "chat-1",
                    style = 45,
                    chatIdentifier = "+15551234567",
                    displayName = null,
                    isArchived = false,
                    lastMessageDate = 1000L,
                    lastMessagePreview = "seed",
                )
            )
        )
    }

    private fun seedLocalMessage(guid: String, dateCreated: Long = 900L) = MessageEntity(
        guid = guid,
        chatGuid = "chat-1",
        originalRowId = 1L,
        text = "already here",
        subject = null,
        isFromMe = false,
        senderAddress = "+15551234567",
        dateCreated = dateCreated,
        dateRead = null,
        dateDelivered = null,
        itemType = 0,
        groupActionType = 0,
        groupTitle = null,
        associatedMessageGuid = null,
        associatedMessageType = null,
        threadOriginatorGuid = null,
        expressiveSendStyleId = null,
        dateEdited = null,
        dateRetracted = null,
        sendStatus = SendStatus.SENT,
    )

    // --- 1. loadOlder backfill ------------------------------------------------------------------

    @Test
    fun `loadOlder requests DESC page with before+limit, ingests via BACKFILL, returns count, leaves watermark untouched`() =
        runBlocking {
            seedChat()
            watermarkStore.set(42L)

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    envelope(
                        "[${messageJson("m-old-1", "older one", dateCreated = 800L, rowId = 10)}," +
                            "${messageJson("m-old-2", "older two", dateCreated = 700L, rowId = 9)}]"
                    )
                )
            )

            val count = withTimeout(10_000) {
                repo.loadOlder(chatGuid = "chat-1", beforeMs = 1000L, pageSize = 50)
            }

            assertEquals(2, count)

            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/api/v1/chat/chat-1/message", recorded.path?.substringBefore("?"))
            assertEquals(
                "attachment,message.attributedBody,message.messageSummaryInfo",
                recorded.requestUrl?.queryParameter("with"),
            )
            assertEquals("DESC", recorded.requestUrl?.queryParameter("sort"))
            assertEquals("1000", recorded.requestUrl?.queryParameter("before"))
            assertEquals("50", recorded.requestUrl?.queryParameter("limit"))

            val rows = db.messageDao().observeMessages("chat-1", limit = 100).first()
            assertEquals(setOf("m-old-1", "m-old-2"), rows.map { it.guid }.toSet())
            assertEquals("older one", rows.first { it.guid == "m-old-1" }.text)

            assertEquals(42L, watermarkStore.get())
        }

    // --- 2. loadOlder de-dups -------------------------------------------------------------------

    @Test
    fun `loadOlder de-dups a page that contains an already-present guid`() = runBlocking {
        seedChat()
        db.messageDao().insertAll(listOf(seedLocalMessage("m-dup", dateCreated = 900L)))
        watermarkStore.set(7L)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                envelope(
                    "[${messageJson("m-dup", "server copy", dateCreated = 900L, rowId = 1)}," +
                        "${messageJson("m-new", "brand new older", dateCreated = 500L, rowId = 2)}]"
                )
            )
        )

        val count = withTimeout(10_000) {
            repo.loadOlder(chatGuid = "chat-1", beforeMs = 1000L, pageSize = 50)
        }

        assertEquals(2, count) // messages *received*, not newly inserted
        val rows = db.messageDao().observeMessages("chat-1", limit = 100).first()
        assertEquals(2, rows.size)
        assertEquals(setOf("m-dup", "m-new"), rows.map { it.guid }.toSet())
        assertEquals(1, rows.count { it.guid == "m-dup" })
        assertNotNull(db.messageDao().getByGuid("m-dup"))
        assertEquals(7L, watermarkStore.get())
    }

    // --- 3. observeMessages delegates -----------------------------------------------------------

    @Test
    fun `observeMessages returns the Room window for the chat`() = runBlocking {
        seedChat()
        db.messageDao().insertAll(
            listOf(
                seedLocalMessage("m1", dateCreated = 300L),
                seedLocalMessage("m2", dateCreated = 200L).copy(guid = "m2"),
            )
        )

        val observed = repo.observeMessages("chat-1", limit = 100).first()
        assertEquals(listOf("m1", "m2"), observed.map { it.guid })
        assertTrue(observed.all { it.chatGuid == "chat-1" })
    }
}
