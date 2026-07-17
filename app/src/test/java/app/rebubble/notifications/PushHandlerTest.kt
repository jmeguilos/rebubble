package app.rebubble.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.remote.loadFixture
import app.rebubble.data.sync.MessageIngestor
import app.rebubble.data.sync.NewMessageAlert
import app.rebubble.data.sync.SyncScheduling
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class PushHandlerTest {

    private lateinit var context: Context
    private lateinit var db: RebubbleDatabase
    private lateinit var ingestor: MessageIngestor
    private lateinit var workManager: WorkManager
    private lateinit var handler: PushHandler

    private val alerted = mutableListOf<List<String>>()
    private val alert = NewMessageAlert { guids -> alerted.add(guids.toList()) }
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        db = InMemoryDatabaseFactory.create()
        ingestor = MessageIngestor(
            db = db,
            messageDao = db.messageDao(),
            chatDao = db.chatDao(),
            attachmentDao = db.attachmentDao(),
            handleDao = db.handleDao(),
        )
        alerted.clear()
        handler = PushHandler(
            ingestor = ingestor,
            newMessageAlert = alert,
            json = json,
            logger = app.rebubble.data.logging.RingBufferLogger(),
            context = context,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `valid new-message payload inserts row and invokes NewMessageAlert`() = runBlocking {
        val payload = loadFixture("socket-new-message.json")
        // Strip provenance-only _source if present — ignoreUnknownKeys handles it.
        handler.handle(
            mapOf(
                "type" to "new-message",
                "data" to payload,
            ),
        )

        val row = db.messageDao().getByGuid("p:0/A1B2C3D4-0000-0000-0000-000000000001")
        assertNotNull(row)
        assertEquals(1, alerted.size)
        assertEquals(listOf("p:0/A1B2C3D4-0000-0000-0000-000000000001"), alerted[0])
        assertNoExpeditedWork()
    }

    @Test
    fun `isFromMe insert does not invoke NewMessageAlert`() = runBlocking {
        val payload = """
            {
              "guid": "from-me-1",
              "text": "sent by me",
              "isFromMe": true,
              "dateCreated": 1000,
              "chats": [{
                "guid": "chat-me",
                "style": 45,
                "chatIdentifier": "+15550001111",
                "displayName": null
              }],
              "handle": { "address": "+15550001111", "service": "iMessage" }
            }
        """.trimIndent()

        handler.handle(mapOf("type" to "new-message", "data" to payload))

        assertNotNull(db.messageDao().getByGuid("from-me-1"))
        assertTrue(alerted.isEmpty())
        assertNoExpeditedWork()
    }

    @Test
    fun `unknown type enqueues expedited sync without crash`() = runBlocking {
        handler.handle(mapOf("type" to "typing-indicator", "data" to "{}"))

        assertExpeditedEnqueued()
        assertTrue(alerted.isEmpty())
    }

    @Test
    fun `garbled AES-looking payload enqueues expedited sync`() = runBlocking {
        // encrypt_coms payloads are opaque AES ciphertext strings, not JSON objects.
        val aesLooking = "dGhpc2lzbm90anNvbmJ1dGJhc2U2NGVuY3J5cHRlZA=="
        handler.handle(mapOf("type" to "new-message", "data" to aesLooking))

        assertExpeditedEnqueued()
        assertNull(db.messageDao().getByGuid("anything"))
        assertTrue(alerted.isEmpty())
    }

    @Test
    fun `absent payload enqueues expedited sync`() = runBlocking {
        handler.handle(mapOf("type" to "new-message"))

        assertExpeditedEnqueued()
        assertTrue(alerted.isEmpty())
    }

    @Test
    fun `ingest explosion still enqueues expedited sync and never throws`() = runBlocking {
        val exploding = object : MessageIngestor(
            db = db,
            messageDao = db.messageDao(),
            chatDao = db.chatDao(),
            attachmentDao = db.attachmentDao(),
            handleDao = db.handleDao(),
        ) {
            override suspend fun ingest(
                dtos: List<app.rebubble.data.remote.dto.MessageDto>,
                source: app.rebubble.data.sync.IngestSource,
                fallbackChatGuid: String?,
            ) = throw RuntimeException("boom")
        }
        val explodingHandler = PushHandler(
            ingestor = exploding,
            newMessageAlert = alert,
            json = json,
            logger = app.rebubble.data.logging.RingBufferLogger(),
            context = context,
        )

        explodingHandler.handle(
            mapOf(
                "type" to "new-message",
                "data" to loadFixture("socket-new-message.json"),
            ),
        )

        assertExpeditedEnqueued()
        assertTrue(alerted.isEmpty())
    }

    private fun assertExpeditedEnqueued() {
        val infos = workManager
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_EXPEDITED)
            .get(5, TimeUnit.SECONDS)
        assertEquals(1, infos.size)
    }

    private fun assertNoExpeditedWork() {
        val infos = workManager
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_EXPEDITED)
            .get(5, TimeUnit.SECONDS)
        assertTrue(infos.isEmpty())
    }
}
