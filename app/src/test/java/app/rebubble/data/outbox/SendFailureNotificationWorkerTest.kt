package app.rebubble.data.outbox

import android.app.NotificationManager
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
import app.rebubble.data.logging.RingBufferLogger
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import app.rebubble.data.repo.InMemorySecretStore
import app.rebubble.data.repo.ServerConfigRepository
import app.rebubble.data.sync.MessageIngestor
import app.rebubble.notifications.MessageNotifier
import app.rebubble.notifications.NotificationChannels
import app.rebubble.notifications.SendFailureNotifier
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SendFailureNotificationWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: RebubbleDatabase
    private lateinit var ingestor: MessageIngestor
    private lateinit var server: MockWebServer
    private lateinit var api: BlueBubblesApi
    private lateinit var serverConfig: ServerConfigRepository
    private lateinit var notificationManager: NotificationManager
    private lateinit var sendFailureNotifier: SendFailureNotifier
    private lateinit var logger: RingBufferLogger

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        NotificationChannels.ensureCreated(context)
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
        api = testBlueBubblesApi(
            FakeServerCredentialsProvider(
                urlValue = server.url("/").toString(),
                passwordValue = "pw",
            ),
        )
        serverConfig = ServerConfigRepository(
            dataStore = PreferenceDataStoreFactory.create(
                produceFile = { File(tempFolder.newFolder(), "server_config.preferences_pb") },
            ),
            secretStore = InMemorySecretStore(),
            apiProvider = Provider { api },
        )
        sendFailureNotifier = SendFailureNotifier(context)
        logger = RingBufferLogger()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun `markFailed path posts errors notification with chatGuid`() = runBlocking {
        val chatGuid = "iMessage;-;+15559876543"
        val tempGuid = "temp-fail-guid"
        db.chatDao().upsert(
            listOf(
                ChatEntity(
                    guid = chatGuid,
                    style = 45,
                    chatIdentifier = "+15559876543",
                    displayName = null,
                    isArchived = false,
                    lastMessageDate = null,
                    lastMessagePreview = null,
                ),
            ),
        )
        db.messageDao().insertAll(
            listOf(
                MessageEntity(
                    guid = tempGuid,
                    chatGuid = chatGuid,
                    originalRowId = null,
                    text = "hi",
                    subject = null,
                    isFromMe = true,
                    senderAddress = null,
                    dateCreated = 1L,
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
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"status":400,"message":"bad","data":null}"""))

        val worker = TestListenableWorkerBuilder<SendTextWorker>(context)
            .setInputData(
                workDataOf(
                    SendTextWorker.KEY_TEMP_GUID to tempGuid,
                    SendTextWorker.KEY_CHAT_GUID to chatGuid,
                    SendTextWorker.KEY_TEXT to "hi",
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
                        api,
                        db.messageDao(),
                        ingestor,
                        serverConfig,
                        sendFailureNotifier,
                        logger,
                    )
                },
            )
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.failure()::class, result::class)

        val notification = shadowOf(notificationManager).getNotification(chatGuid.hashCode())
        assertNotNull(notification)
        assertEquals(NotificationChannels.ERRORS, notification!!.channelId)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(chatGuid, intent.getStringExtra(MessageNotifier.EXTRA_CHAT_GUID))
    }
}
