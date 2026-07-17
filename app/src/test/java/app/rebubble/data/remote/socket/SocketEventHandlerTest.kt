package app.rebubble.data.remote.socket

import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.remote.dto.ChatDto
import app.rebubble.data.remote.dto.HandleDto
import app.rebubble.data.remote.dto.MessageDto
import app.rebubble.data.sync.MessageIngestor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SocketEventHandlerTest {

    private lateinit var db: RebubbleDatabase
    private lateinit var ingestor: MessageIngestor
    private lateinit var handlerScope: CoroutineScope

    @Before
    fun setUp() {
        db = InMemoryDatabaseFactory.create()
        ingestor = MessageIngestor(
            db = db,
            messageDao = db.messageDao(),
            chatDao = db.chatDao(),
            attachmentDao = db.attachmentDao(),
            handleDao = db.handleDao(),
        )
        // Default so Room transaction work can resume off Unconfined after suspend points.
        handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        handlerScope.cancel()
        db.close()
    }

    @Test
    fun `NewMessage is ingested with SOCKET source into Room`() = runBlocking {
        val fakeSocket = FakeSocketClient()
        // Unconfined only for this test's collector registration + synchronous ingest path.
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val handler = SocketEventHandler(
                socketClient = fakeSocket,
                ingestor = ingestor,
                onReconnect = SocketReconnectAction { },
                scope = localScope,
            )
            handler.start()
            yield()

            val dto = MessageDto(
                guid = "p:0/socket-msg-1",
                text = "from socket",
                dateCreated = 1_000L,
                isFromMe = false,
                handle = HandleDto(address = "+15551112222", service = "iMessage"),
                chats = listOf(
                    ChatDto(
                        guid = "iMessage;-;+15551112222",
                        style = 45,
                        chatIdentifier = "+15551112222",
                    ),
                ),
                originalRowId = 42L,
            )
            fakeSocket.emit(SocketEvent.NewMessage(dto))

            val row = withTimeout(5_000) {
                while (true) {
                    val found = db.messageDao().getByGuid("p:0/socket-msg-1")
                    if (found != null) return@withTimeout found
                    delay(25)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
            assertEquals("from socket", row.text)
            assertEquals("iMessage;-;+15551112222", row.chatGuid)
        } finally {
            localScope.cancel()
        }
    }

    @Test
    fun `first CONNECTED does not reconcile - reconnect does exactly once per reconnect`() = runBlocking {
        val fakeSocket = FakeSocketClient()
        var reconcileCount = 0
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val handler = SocketEventHandler(
                socketClient = fakeSocket,
                ingestor = ingestor,
                onReconnect = SocketReconnectAction { reconcileCount++ },
                scope = localScope,
            )
            handler.start()
            yield()

            fakeSocket.setConnectionState(ConnState.CONNECTED)
            assertEquals(0, reconcileCount)

            fakeSocket.setConnectionState(ConnState.DISCONNECTED)
            assertEquals(0, reconcileCount)

            fakeSocket.setConnectionState(ConnState.CONNECTED)
            assertEquals(1, reconcileCount)

            fakeSocket.setConnectionState(ConnState.DISCONNECTED)
            fakeSocket.setConnectionState(ConnState.CONNECTED)
            assertEquals(2, reconcileCount)
        } finally {
            localScope.cancel()
        }
    }
}
