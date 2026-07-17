package app.rebubble.data.outbox

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import app.rebubble.data.remote.dto.MessageDto
import app.rebubble.data.repo.InMemorySecretStore
import app.rebubble.data.repo.ServerConfigRepository
import app.rebubble.data.sync.IngestSource
import app.rebubble.data.sync.MessageIngestor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import javax.inject.Provider

/**
 * Outbox text-send correctness: optimistic SENDING insert, [SendTextWorker] ack path via
 * [IngestSource.SEND_ACK], retry/failure semantics, socket-echo race, and method selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class OutboxSendTextTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: RebubbleDatabase
    private lateinit var ingestor: MessageIngestor
    private lateinit var server: MockWebServer
    private lateinit var api: BlueBubblesApi
    private lateinit var serverConfig: ServerConfigRepository
    private lateinit var outbox: OutboxRepository

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        db = InMemoryDatabaseFactory.create()
        ingestor = MessageIngestor(
            db = db,
            messageDao = db.messageDao(),
            chatDao = db.chatDao(),
            attachmentDao = db.attachmentDao(),
            handleDao = db.handleDao(),
        )

        server = MockWebServer()
        server.start()
        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw",
        )
        api = testBlueBubblesApi(credentials)
        serverConfig = ServerConfigRepository(
            dataStore = newServerConfigDataStore(),
            secretStore = InMemorySecretStore(),
            apiProvider = Provider { api },
        )
        outbox = OutboxRepository(
            context = context,
            messageDao = db.messageDao(),
            chatDao = db.chatDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun newServerConfigDataStore(): DataStore<Preferences> {
        val file = File(tempFolder.newFolder(), "server_config.preferences_pb")
        return PreferenceDataStoreFactory.create(produceFile = { file })
    }

    private suspend fun seedChat(guid: String = CHAT_GUID) {
        db.chatDao().upsert(
            listOf(
                ChatEntity(
                    guid = guid,
                    style = 45,
                    chatIdentifier = "+15551234567",
                    displayName = null,
                    isArchived = false,
                    lastMessageDate = null,
                    lastMessagePreview = null,
                ),
            ),
        )
    }

    private fun buildWorker(
        tempGuid: String,
        chatGuid: String = CHAT_GUID,
        text: String = TEXT,
        runAttemptCount: Int = 0,
    ): SendTextWorker {
        return TestListenableWorkerBuilder<SendTextWorker>(context)
            .setInputData(
                workDataOf(
                    SendTextWorker.KEY_TEMP_GUID to tempGuid,
                    SendTextWorker.KEY_CHAT_GUID to chatGuid,
                    SendTextWorker.KEY_TEXT to text,
                ),
            )
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = SendTextWorker(
                        appContext,
                        workerParameters,
                        api,
                        db.messageDao(),
                        ingestor,
                        serverConfig,
                    )
                },
            )
            .build()
    }

    private fun sentEnvelope(tempGuid: String, realGuid: String = REAL_GUID, text: String = TEXT): String {
        // Shape matches fixtures/sent-message-with-tempguid.json with overridden ids/text.
        return """
            {
              "status": 200,
              "message": "Message sent!",
              "data": {
                "originalROWID": 601,
                "tempGuid": "$tempGuid",
                "guid": "$realGuid",
                "text": "$text",
                "subject": null,
                "error": 0,
                "dateCreated": 1752346000000,
                "dateRead": null,
                "dateDelivered": null,
                "isFromMe": true,
                "handle": null,
                "handleId": 0,
                "attachments": [],
                "itemType": 0,
                "groupTitle": null,
                "groupActionType": 0,
                "associatedMessageGuid": null,
                "associatedMessageType": null,
                "threadOriginatorGuid": null,
                "threadOriginatorPart": null,
                "expressiveSendStyleId": null,
                "dateEdited": null,
                "dateRetracted": null
              }
            }
        """.trimIndent()
    }

    // --- 1. optimistic insert ------------------------------------------------------------------

    @Test
    fun `sendText inserts optimistic SENDING row, updates preview, returns temp guid`() = runBlocking {
        seedChat()

        val tempGuid = outbox.sendText(CHAT_GUID, TEXT)

        assertTrue(tempGuid.matches(Regex("^temp-[0-9a-f]{8}$")))
        val row = db.messageDao().getByGuid(tempGuid)
        assertNotNull(row)
        assertEquals(SendStatus.SENDING, row!!.sendStatus)
        assertEquals(TEXT, row.text)
        assertEquals(CHAT_GUID, row.chatGuid)
        assertTrue(row.isFromMe)
        assertNull(row.originalRowId)

        val chat = db.chatDao().getByGuid(CHAT_GUID)
        assertEquals(TEXT, chat?.lastMessagePreview)
        assertEquals(row.dateCreated, chat?.lastMessageDate)

        // Network constraint keeps the worker from running under WorkManagerTestInitHelper
        // until TestDriver marks constraints met — so the row is still SENDING with 0 requests.
        assertEquals(0, server.requestCount)
    }

    // --- 2. worker success ---------------------------------------------------------------------

    @Test
    fun `worker success swaps temp guid to real via SEND_ACK`() = runBlocking {
        seedChat()
        val tempGuid = outbox.sendText(CHAT_GUID, TEXT)
        server.enqueue(MockResponse().setResponseCode(200).setBody(sentEnvelope(tempGuid)))

        val result = buildWorker(tempGuid).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(db.messageDao().getByGuid(tempGuid))
        val real = db.messageDao().getByGuid(REAL_GUID)
        assertNotNull(real)
        assertEquals(SendStatus.SENT, real!!.sendStatus)
        assertEquals(TEXT, real.text)
        assertEquals(1, db.messageDao().observeMessages(CHAT_GUID, limit = 100).first().size)
    }

    // --- 3. Safe IOException → retry -----------------------------------------------------------

    @Test
    fun `worker connect-refused IOException returns retry and leaves row SENDING`() = runBlocking {
        seedChat()
        val tempGuid = outbox.sendText(CHAT_GUID, TEXT)
        // Request never left the client — connection refused to a shut-down listener.
        server.shutdown()

        val result = buildWorker(tempGuid).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(SendStatus.SENDING, db.messageDao().getByGuid(tempGuid)?.sendStatus)
    }

    // --- 3b. Ambiguous IOException → FAILED ----------------------------------------------------

    @Test
    fun `worker ambiguous read failure marks FAILED and returns failure`() = runBlocking {
        seedChat()
        val tempGuid = outbox.sendText(CHAT_GUID, TEXT)
        // Request may have been accepted; no response body → ambiguous mid-flight outcome.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortTimeoutApi = testBlueBubblesApi(
            FakeServerCredentialsProvider(
                urlValue = server.url("/").toString(),
                passwordValue = "pw",
            ),
            readTimeoutMs = 250,
        )
        val worker = TestListenableWorkerBuilder<SendTextWorker>(context)
            .setInputData(
                workDataOf(
                    SendTextWorker.KEY_TEMP_GUID to tempGuid,
                    SendTextWorker.KEY_CHAT_GUID to CHAT_GUID,
                    SendTextWorker.KEY_TEXT to TEXT,
                ),
            )
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = SendTextWorker(
                        appContext,
                        workerParameters,
                        shortTimeoutApi,
                        db.messageDao(),
                        ingestor,
                        serverConfig,
                    )
                },
            )
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(SendStatus.FAILED, db.messageDao().getByGuid(tempGuid)?.sendStatus)
    }

    // --- 4. 400 → FAILED -----------------------------------------------------------------------

    @Test
    fun `worker 400 marks FAILED and returns failure`() = runBlocking {
        seedChat()
        val tempGuid = outbox.sendText(CHAT_GUID, TEXT)
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"status":400,"message":"Bad","error":{"type":"Bad Request","message":"no"}}"""),
        )

        val result = buildWorker(tempGuid).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(SendStatus.FAILED, db.messageDao().getByGuid(tempGuid)?.sendStatus)
    }

    @Test
    fun `worker 400 already queued marks FAILED without retry`() = runBlocking {
        seedChat()
        val tempGuid = outbox.sendText(CHAT_GUID, TEXT)
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody(
                    """{"status":400,"message":"Bad Request","error":{"type":"Bad Request","message":"Message is already queued to be sent!"}}""",
                ),
        )

        val result = buildWorker(tempGuid).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(SendStatus.FAILED, db.messageDao().getByGuid(tempGuid)?.sendStatus)
    }

    // --- 4b. 5xx attempt budget ----------------------------------------------------------------

    @Test
    fun `worker 500 retries while runAttemptCount below max`() = runBlocking {
        seedChat()
        val tempGuid = outbox.sendText(CHAT_GUID, TEXT)
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"status":500,"message":"err","error":{"type":"Server Error","message":"boom"}}"""),
        )

        val result = buildWorker(tempGuid, runAttemptCount = 1).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(SendStatus.SENDING, db.messageDao().getByGuid(tempGuid)?.sendStatus)
    }

    @Test
    fun `worker 500 marks FAILED when runAttemptCount at max`() = runBlocking {
        seedChat()
        val tempGuid = outbox.sendText(CHAT_GUID, TEXT)
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"status":500,"message":"err","error":{"type":"Server Error","message":"boom"}}"""),
        )

        val result = buildWorker(tempGuid, runAttemptCount = 3).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(SendStatus.FAILED, db.messageDao().getByGuid(tempGuid)?.sendStatus)
    }

    // --- 5. retry() ----------------------------------------------------------------------------

    @Test
    fun `retry on FAILED resets to SENDING and re-enqueues, non-FAILED is no-op`() = runBlocking {
        seedChat()
        val tempGuid = outbox.sendText(CHAT_GUID, TEXT)
        val row = db.messageDao().getByGuid(tempGuid)!!
        db.messageDao().update(row.copy(sendStatus = SendStatus.FAILED))

        outbox.retry(tempGuid)

        assertEquals(SendStatus.SENDING, db.messageDao().getByGuid(tempGuid)?.sendStatus)
        val workInfos = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(tempGuid)
            .get(5, TimeUnit.SECONDS)
        assertTrue(workInfos.isNotEmpty())

        // Non-FAILED (SENDING) → no-op: status unchanged.
        val before = db.messageDao().getByGuid(tempGuid)!!
        outbox.retry(tempGuid)
        assertEquals(SendStatus.SENDING, db.messageDao().getByGuid(tempGuid)?.sendStatus)
        assertEquals(before.dateCreated, db.messageDao().getByGuid(tempGuid)?.dateCreated)

        // SENT → no-op
        db.messageDao().update(before.copy(sendStatus = SendStatus.SENT))
        outbox.retry(tempGuid)
        assertEquals(SendStatus.SENT, db.messageDao().getByGuid(tempGuid)?.sendStatus)
    }

    // --- 6. socket-echo race -------------------------------------------------------------------

    @Test
    fun `worker succeeds without network when socket echo already swapped temp guid`() = runBlocking {
        seedChat()
        val tempGuid = "temp-abcd1234"
        db.messageDao().insertAll(
            listOf(
                MessageEntity(
                    guid = tempGuid,
                    chatGuid = CHAT_GUID,
                    originalRowId = null,
                    text = TEXT,
                    subject = null,
                    isFromMe = true,
                    senderAddress = null,
                    dateCreated = 1_000L,
                    dateRead = null,
                    dateDelivered = null,
                    groupTitle = null,
                    associatedMessageGuid = null,
                    associatedMessageType = null,
                    threadOriginatorGuid = null,
                    expressiveSendStyleId = null,
                    dateEdited = null,
                    dateRetracted = null,
                    sendStatus = SendStatus.SENDING,
                ),
            ),
        )
        // Socket echo wins: ingest real guid + tempGuid via SOCKET (swaps PK).
        val echo = MessageDto(
            originalRowId = 601L,
            tempGuid = tempGuid,
            guid = REAL_GUID,
            text = TEXT,
            isFromMe = true,
            dateCreated = 1_752_346_000_000L,
            handle = null,
            attachments = emptyList(),
        )
        ingestor.ingest(listOf(echo), IngestSource.SOCKET, fallbackChatGuid = CHAT_GUID)
        assertNull(db.messageDao().getByGuid(tempGuid))
        assertEquals(SendStatus.SENT, db.messageDao().getByGuid(REAL_GUID)?.sendStatus)

        val beforeCount = server.requestCount
        val result = buildWorker(tempGuid).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(beforeCount, server.requestCount) // zero new requests
        assertEquals(1, db.messageDao().observeMessages(CHAT_GUID, limit = 100).first().size)
        assertEquals(SendStatus.SENT, db.messageDao().getByGuid(REAL_GUID)?.sendStatus)
    }

    // --- 7. method selection -------------------------------------------------------------------

    @Test
    fun `method is private-api when cached serverInfo has privateApi and helperConnected`() = runBlocking {
        seedChat()
        // Cache serverInfo via refresh (do NOT call refresh again from the worker).
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"OK","data":{"computer_id":"c1","os_version":"14.5","server_version":"1.9.7","private_api":true,"helper_connected":true}}""",
            ),
        )
        serverConfig.save(server.url("/").toString().trimEnd('/'), "pw")
        serverConfig.refreshServerInfo()
        assertTrue(serverConfig.serverInfo.first()!!.privateApi)
        assertTrue(serverConfig.serverInfo.first()!!.helperConnected)

        val tempGuid = outbox.sendText(CHAT_GUID, TEXT)
        server.enqueue(MockResponse().setResponseCode(200).setBody(sentEnvelope(tempGuid)))

        buildWorker(tempGuid).doWork()

        // First request was server/info; second is message/text.
        server.takeRequest(5, TimeUnit.SECONDS) // info
        val sendReq = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = json.parseToJsonElement(sendReq.body.readUtf8()).jsonObject
        assertEquals("private-api", body["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `method is apple-script when serverInfo absent or flags false`() = runBlocking {
        seedChat()
        // No refreshServerInfo → serverInfo Flow is null → apple-script.
        val tempGuid = outbox.sendText(CHAT_GUID, TEXT)
        server.enqueue(MockResponse().setResponseCode(200).setBody(sentEnvelope(tempGuid)))

        buildWorker(tempGuid).doWork()

        val sendReq = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = json.parseToJsonElement(sendReq.body.readUtf8()).jsonObject
        assertEquals("apple-script", body["method"]!!.jsonPrimitive.content)
    }

    private companion object {
        const val CHAT_GUID = "iMessage;-;+15551234567"
        const val TEXT = "On my way!"
        const val REAL_GUID = "p:0/A1B2C3D4-0000-0000-0000-000000000601"
    }
}
