package app.rebubble.data.sync

import app.rebubble.data.logging.RingBufferLogger;
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import app.rebubble.data.repo.InMemorySecretStore
import app.rebubble.data.repo.ServerConfigRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SyncWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var db: RebubbleDatabase
    private lateinit var ingestor: MessageIngestor
    private lateinit var watermarkStore: SyncWatermarkStore
    private lateinit var serverConfig: ServerConfigRepository
    private lateinit var api: BlueBubblesApi
    private lateinit var tracker: SyncStatusTracker

    private val alerted = mutableListOf<List<String>>()
    private val alert = NewMessageAlert { guids -> alerted.add(guids.toList()) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
        watermarkStore = SyncWatermarkStore(newDataStore("sync_state"))
        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw",
        )
        api = testBlueBubblesApi(credentials)
        serverConfig = ServerConfigRepository(
            dataStore = newDataStore("server_config"),
            secretStore = InMemorySecretStore(),
            apiProvider = Provider { api },
        )
        tracker = SyncStatusTracker(RingBufferLogger())
        alerted.clear()
        runBlocking {
            serverConfig.save(server.url("/").toString().trimEnd('/'), "pw")
        }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        db.close()
    }

    private fun newDataStore(name: String): DataStore<Preferences> {
        val file = File(tempFolder.newFolder(), "$name.preferences_pb")
        return PreferenceDataStoreFactory.create(produceFile = { file })
    }

    private fun reconciler(): Reconciler = Reconciler(
        api = api,
        watermarkStore = watermarkStore,
        ingestor = ingestor,
        chatDao = db.chatDao(),
        handleDao = db.handleDao(),
        messageDao = db.messageDao(),
        pageLimit = 10,
    )

    private fun buildWorker(): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = SyncWorker(
                        appContext,
                        workerParameters,
                        reconciler(),
                        tracker,
                        alert,
                        serverConfig,
                    )
                },
            )
            .build()

    private fun envelope(dataArrayJson: String) =
        """{"status":200,"message":"OK","data":$dataArrayJson}"""

    private fun enqueueChats(vararg chatsJson: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(envelope("[${chatsJson.joinToString(",")}]")),
        )
    }

    private fun enqueueMessages(vararg messagesJson: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(envelope("[${messagesJson.joinToString(",")}]")),
        )
    }

    private fun messageJson(guid: String, rowId: Long, isFromMe: Boolean = false): String = """
        {"originalROWID":$rowId,"guid":"$guid","text":"hi","isFromMe":$isFromMe,"dateCreated":1000,
         "chats":[{"guid":"chat-1","style":45,"chatIdentifier":"+15551234567"}]}
    """.trimIndent()

    @Test
    fun `success with new messages invokes NewMessageAlert with those guids`() = runBlocking {
        watermarkStore.set(0L)
        enqueueChats()
        enqueueMessages(messageJson("g1", 1), messageJson("g2", 2))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, alerted.size)
        assertEquals(listOf("g1", "g2"), alerted[0])
    }

    @Test
    fun `success with empty newMessageGuids does not invoke NewMessageAlert`() = runBlocking {
        // Null watermark → message pass skipped; empty chat page → clean empty outcome.
        enqueueChats()

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(alerted.isEmpty())
    }

    @Test
    fun `IOException outcome returns retry`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `non-IO error outcome returns failure`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setBody("""{"status":500,"message":"boom","error":{"type":"Server","message":"boom"}}"""),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `clean success returns success`() = runBlocking {
        enqueueChats()

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }
}
