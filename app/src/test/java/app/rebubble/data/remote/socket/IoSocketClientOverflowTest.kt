package app.rebubble.data.remote.socket

import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class IoSocketClientOverflowTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `buffer overflow schedules reconcile at least once`() = runBlocking {
        var reconcileCount = 0
        val client = IoSocketClient(
            credentials = FakeServerCredentialsProvider(
                urlValue = "http://192.168.1.20:1234",
                passwordValue = "secret",
            ),
            parser = SocketPayloadParser(Json { ignoreUnknownKeys = true }),
            onReconnect = SocketReconnectAction { reconcileCount++ },
            scope = scope,
            eventBufferCapacity = 1,
        )

        // SharedFlow.tryEmit with zero subscribers silently "succeeds" (drops). A hung
        // subscriber is required so the tiny buffer can fill and tryEmit returns false.
        val releaseCollector = CompletableDeferred<Unit>()
        val firstReceived = CompletableDeferred<Unit>()
        scope.launch {
            client.events.collect {
                if (!firstReceived.isCompleted) firstReceived.complete(Unit)
                releaseCollector.await()
            }
        }
        yield()

        val event = SocketEvent.Unknown("probe")
        assertTrue(client.offerEvent(event, eventNameForLog = "probe-delivered"))
        withTimeout(2_000) { firstReceived.await() }
        // Buffer slot fills while collector is stuck on releaseCollector.
        assertTrue(client.offerEvent(event, eventNameForLog = "probe-buffered"))
        assertFalse(client.offerEvent(event, eventNameForLog = "probe-dropped"))

        withTimeout(2_000) {
            while (reconcileCount < 1) delay(10)
        }
        assertTrue(reconcileCount >= 1)
        releaseCollector.complete(Unit)
        Unit
    }
}
