package app.rebubble.data.outbox

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.DownloadState
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import app.rebubble.data.remote.dto.Envelope
import app.rebubble.data.remote.dto.MessageDto
import app.rebubble.data.repo.InMemorySecretStore
import app.rebubble.data.repo.ServerConfigRepository
import app.rebubble.data.sync.IngestSource
import app.rebubble.data.sync.MessageIngestor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Outbox attachment-send correctness: URI copy before enqueue, multipart shape,
 * SEND_ACK swap + temp-att cleanup, shared [OutboxRetryPolicy], missing-file failure,
 * and [OutboxRepository.retry] routing to [SendAttachmentWorker].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class OutboxSendAttachmentTest {

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
            attachmentDao = db.attachmentDao(),
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

    private fun sourceFile(bytes: ByteArray = SOURCE_BYTES, name: String = FILE_NAME): File {
        val file = tempFolder.newFile(name)
        file.writeBytes(bytes)
        return file
    }

    private fun buildWorker(
        tempGuid: String,
        chatGuid: String = CHAT_GUID,
        filePath: String,
        name: String = FILE_NAME,
        mimeType: String? = MIME_TYPE,
        runAttemptCount: Int = 0,
        apiOverride: BlueBubblesApi = api,
    ): SendAttachmentWorker {
        val dataBuilder = androidx.work.Data.Builder()
            .putString(SendAttachmentWorker.KEY_TEMP_GUID, tempGuid)
            .putString(SendAttachmentWorker.KEY_CHAT_GUID, chatGuid)
            .putString(SendAttachmentWorker.KEY_FILE_PATH, filePath)
            .putString(SendAttachmentWorker.KEY_NAME, name)
        if (mimeType != null) {
            dataBuilder.putString(SendAttachmentWorker.KEY_MIME_TYPE, mimeType)
        }
        return TestListenableWorkerBuilder<SendAttachmentWorker>(context)
            .setInputData(dataBuilder.build())
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = SendAttachmentWorker(
                        appContext,
                        workerParameters,
                        apiOverride,
                        db.messageDao(),
                        db.attachmentDao(),
                        ingestor,
                        serverConfig,
                    )
                },
            )
            .build()
    }

    private fun sentEnvelope(
        tempGuid: String,
        realGuid: String = REAL_GUID,
        attachmentGuid: String = REAL_ATT_GUID,
        transferName: String = FILE_NAME,
    ): String {
        return """
            {
              "status": 200,
              "message": "Message sent!",
              "data": {
                "originalROWID": 701,
                "tempGuid": "$tempGuid",
                "guid": "$realGuid",
                "text": null,
                "subject": null,
                "error": 0,
                "dateCreated": 1752347000000,
                "dateRead": null,
                "dateDelivered": null,
                "isFromMe": true,
                "handle": null,
                "handleId": 0,
                "attachments": [
                  {
                    "originalROWID": 42,
                    "guid": "$attachmentGuid",
                    "uti": "public.jpeg",
                    "mimeType": "$MIME_TYPE",
                    "transferName": "$transferName",
                    "totalBytes": ${SOURCE_BYTES.size},
                    "height": null,
                    "width": null,
                    "hasLivePhoto": false
                  }
                ],
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

    // --- 1. copy + optimistic insert ------------------------------------------------------------

    @Test
    fun `sendAttachment copies bytes before enqueue and inserts optimistic message+attachment`() = runBlocking {
        seedChat()
        val source = sourceFile()
        val uri = Uri.fromFile(source)

        val tempGuid = outbox.sendAttachment(CHAT_GUID, uri, displayName = FILE_NAME, mimeType = MIME_TYPE)

        assertTrue(tempGuid.matches(Regex("^temp-[0-9a-f]{8}$")))
        val row = db.messageDao().getByGuid(tempGuid)
        assertNotNull(row)
        assertEquals(SendStatus.SENDING, row!!.sendStatus)
        assertNull(row.text)
        assertTrue(row.isFromMe)

        val attGuid = OutboxRepository.tempAttachmentGuid(tempGuid)
        val att = db.attachmentDao().getByGuid(attGuid)
        assertNotNull(att)
        assertEquals(tempGuid, att!!.messageGuid)
        assertEquals(FILE_NAME, att.transferName)
        assertEquals(MIME_TYPE, att.mimeType)
        assertEquals(SOURCE_BYTES.size.toLong(), att.totalBytes)
        assertEquals(DownloadState.DOWNLOADED, att.downloadState)
        assertNotNull(att.localPath)

        val copied = File(att.localPath!!)
        assertTrue(copied.exists())
        assertTrue(copied.absolutePath.contains("outbox/$tempGuid/"))
        assertArrayEquals(SOURCE_BYTES, copied.readBytes())

        val chat = db.chatDao().getByGuid(CHAT_GUID)
        assertEquals(OutboxRepository.ATTACHMENT_PREVIEW, chat?.lastMessagePreview)
        assertEquals(row.dateCreated, chat?.lastMessageDate)

        // Network constraint keeps the worker from running under WorkManagerTestInitHelper.
        assertEquals(0, server.requestCount)
    }

    // --- 2. worker success + multipart + temp-att cleanup ---------------------------------------

    @Test
    fun `worker success posts multipart attachment part and cleans up temp-att after swap`() = runBlocking {
        seedChat()
        val source = sourceFile()
        val tempGuid = outbox.sendAttachment(
            CHAT_GUID,
            Uri.fromFile(source),
            displayName = FILE_NAME,
            mimeType = MIME_TYPE,
        )
        val localPath = db.attachmentDao().getByGuid(OutboxRepository.tempAttachmentGuid(tempGuid))!!.localPath!!
        server.enqueue(MockResponse().setResponseCode(200).setBody(sentEnvelope(tempGuid)))

        val result = buildWorker(tempGuid, filePath = localPath).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(db.messageDao().getByGuid(tempGuid))
        val real = db.messageDao().getByGuid(REAL_GUID)
        assertNotNull(real)
        assertEquals(SendStatus.SENT, real!!.sendStatus)

        // Temp-att deleted; real attachment kept with transferred localPath (no double-render).
        assertNull(db.attachmentDao().getByGuid(OutboxRepository.tempAttachmentGuid(tempGuid)))
        val realAtt = db.attachmentDao().getByGuid(REAL_ATT_GUID)
        assertNotNull(realAtt)
        assertEquals(REAL_GUID, realAtt!!.messageGuid)
        assertEquals(localPath, realAtt.localPath)
        assertEquals(DownloadState.DOWNLOADED, realAtt.downloadState)
        assertEquals(1, db.attachmentDao().getForMessage(REAL_GUID).size)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(req.path!!.contains("message/attachment"))
        val parts = parseMultipart(req)
        assertTrue("expected part named attachment", parts.any { it.name == "attachment" })
        val filePart = parts.first { it.name == "attachment" }
        assertEquals(FILE_NAME, filePart.filename)
        assertArrayEquals(SOURCE_BYTES, filePart.body)
        assertEquals(CHAT_GUID, parts.field("chatGuid"))
        assertEquals(tempGuid, parts.field("tempGuid"))
        assertEquals(FILE_NAME, parts.field("name"))
        assertEquals("apple-script", parts.field("method"))
    }

    // --- 3. ambiguous IO → FAILED; connect-refused → retry --------------------------------------

    @Test
    fun `worker ambiguous IO marks FAILED and connect-refused returns retry`() = runBlocking {
        seedChat()
        val source = sourceFile()
        val tempGuid = outbox.sendAttachment(
            CHAT_GUID,
            Uri.fromFile(source),
            displayName = FILE_NAME,
            mimeType = MIME_TYPE,
        )
        val localPath = db.attachmentDao().getByGuid(OutboxRepository.tempAttachmentGuid(tempGuid))!!.localPath!!

        // Ambiguous: NO_RESPONSE + short read timeout → FAILED via shared OutboxRetryPolicy.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortTimeoutApi = testBlueBubblesApi(
            FakeServerCredentialsProvider(
                urlValue = server.url("/").toString(),
                passwordValue = "pw",
            ),
            readTimeoutMs = 250,
        )
        val ambiguous = buildWorker(tempGuid, filePath = localPath, apiOverride = shortTimeoutApi).doWork()
        assertEquals(ListenableWorker.Result.failure(), ambiguous)
        assertEquals(SendStatus.FAILED, db.messageDao().getByGuid(tempGuid)?.sendStatus)

        // Reset to SENDING for the connect-refused case.
        db.messageDao().update(db.messageDao().getByGuid(tempGuid)!!.copy(sendStatus = SendStatus.SENDING))
        server.shutdown()
        val retryResult = buildWorker(tempGuid, filePath = localPath).doWork()
        assertEquals(ListenableWorker.Result.retry(), retryResult)
        assertEquals(SendStatus.SENDING, db.messageDao().getByGuid(tempGuid)?.sendStatus)
    }

    // --- 4. missing local file → FAILED ---------------------------------------------------------

    @Test
    fun `worker missing local file marks FAILED without crash`() = runBlocking {
        seedChat()
        val source = sourceFile()
        val tempGuid = outbox.sendAttachment(
            CHAT_GUID,
            Uri.fromFile(source),
            displayName = FILE_NAME,
            mimeType = MIME_TYPE,
        )
        val localPath = db.attachmentDao().getByGuid(OutboxRepository.tempAttachmentGuid(tempGuid))!!.localPath!!
        assertTrue(File(localPath).delete())

        val result = buildWorker(tempGuid, filePath = localPath).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(SendStatus.FAILED, db.messageDao().getByGuid(tempGuid)?.sendStatus)
        assertEquals(0, server.requestCount)
    }

    // --- 5. crash-window: early-exit recovers orphaned temp-att ---------------------------------

    @Test
    fun `worker early-exit after ingest-without-cleanup removes orphaned temp-att`() = runBlocking {
        seedChat()
        val source = sourceFile()
        val tempGuid = outbox.sendAttachment(
            CHAT_GUID,
            Uri.fromFile(source),
            displayName = FILE_NAME,
            mimeType = MIME_TYPE,
        )
        val tempAttGuid = OutboxRepository.tempAttachmentGuid(tempGuid)
        val localPath = db.attachmentDao().getByGuid(tempAttGuid)!!.localPath!!

        // Simulate process death between ingest and cleanup: swap + real att insert only.
        val dto = requireNotNull(
            json.decodeFromString<Envelope<MessageDto>>(sentEnvelope(tempGuid)).data,
        )
        ingestor.ingest(listOf(dto), IngestSource.SEND_ACK, fallbackChatGuid = CHAT_GUID)

        assertNull(db.messageDao().getByGuid(tempGuid))
        assertNotNull(db.attachmentDao().getByGuid(tempAttGuid))
        assertEquals(2, db.attachmentDao().getForMessage(REAL_GUID).size)

        val result = buildWorker(tempGuid, filePath = localPath).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(db.attachmentDao().getByGuid(tempAttGuid))
        assertEquals(1, db.attachmentDao().getForMessage(REAL_GUID).size)
        val realAtt = db.attachmentDao().getByGuid(REAL_ATT_GUID)
        assertNotNull(realAtt)
        assertEquals(localPath, realAtt!!.localPath)
        assertEquals(DownloadState.DOWNLOADED, realAtt.downloadState)
        assertEquals(0, server.requestCount)
    }

    // --- 6. retry() re-enqueues SendAttachmentWorker --------------------------------------------

    @Test
    fun `retry on FAILED attachment row re-enqueues SendAttachmentWorker`() = runBlocking {
        seedChat()
        val source = sourceFile()
        val tempGuid = outbox.sendAttachment(
            CHAT_GUID,
            Uri.fromFile(source),
            displayName = FILE_NAME,
            mimeType = MIME_TYPE,
        )
        val row = db.messageDao().getByGuid(tempGuid)!!
        db.messageDao().update(row.copy(sendStatus = SendStatus.FAILED))

        // Clear any KEEP-enqueued work from sendAttachment so REPLACE is observable.
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork(tempGuid)

        outbox.retry(tempGuid)

        assertEquals(SendStatus.SENDING, db.messageDao().getByGuid(tempGuid)?.sendStatus)
        val workInfos = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(tempGuid)
            .get(5, TimeUnit.SECONDS)
        assertTrue(workInfos.isNotEmpty())
        assertTrue(
            "expected SendAttachmentWorker tag",
            workInfos.any { it.tags.contains(SendAttachmentWorker::class.java.name) },
        )
        assertFalse(
            "must not enqueue SendTextWorker for attachment rows",
            workInfos.any { it.tags.contains(SendTextWorker::class.java.name) },
        )
    }

    private fun List<MultipartPart>.field(name: String): String =
        first { it.name == name && it.filename == null }.body.toString(StandardCharsets.UTF_8)

    /**
     * Minimal multipart/form-data parser over a [RecordedRequest] body — avoids extra test deps.
     */
    private fun parseMultipart(request: RecordedRequest): List<MultipartPart> {
        val contentType = request.getHeader("Content-Type")
            ?: error("missing Content-Type")
        val boundary = Regex("boundary=(.+)").find(contentType)?.groupValues?.get(1)
            ?: error("missing boundary in $contentType")
        val body = request.body.readByteArray()
        val text = String(body, StandardCharsets.ISO_8859_1)
        val chunks = text.split("--$boundary")
            .map { it.trim('\r', '\n') }
            .filter { it.isNotEmpty() && it != "--" }
        return chunks.mapNotNull { chunk ->
            val headerEnd = chunk.indexOf("\r\n\r\n")
            if (headerEnd < 0) return@mapNotNull null
            val headers = chunk.substring(0, headerEnd)
            var partBody = chunk.substring(headerEnd + 4)
            if (partBody.endsWith("\r\n")) partBody = partBody.dropLast(2)
            val disp = Regex(
                """Content-Disposition:\s*form-data;\s*name="([^"]+)"(?:;\s*filename="([^"]*)")?""",
                RegexOption.IGNORE_CASE,
            ).find(headers) ?: return@mapNotNull null
            MultipartPart(
                name = disp.groupValues[1],
                filename = disp.groupValues[2].ifEmpty { null },
                body = partBody.toByteArray(StandardCharsets.ISO_8859_1),
            )
        }
    }

    private data class MultipartPart(
        val name: String,
        val filename: String?,
        val body: ByteArray,
    )

    private companion object {
        const val CHAT_GUID = "iMessage;-;+15551234567"
        const val FILE_NAME = "photo.jpg"
        const val MIME_TYPE = "image/jpeg"
        const val REAL_GUID = "p:0/A1B2C3D4-0000-0000-0000-000000000701"
        const val REAL_ATT_GUID = "att-real-0001"
        val SOURCE_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03, 0x04)
    }
}
