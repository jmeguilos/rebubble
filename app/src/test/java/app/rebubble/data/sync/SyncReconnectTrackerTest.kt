package app.rebubble.data.sync

import app.rebubble.data.logging.RingBufferLogger;
import app.rebubble.data.remote.socket.SocketReconnectAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Production [SocketReconnectAction] (SocketModule) wraps reconcile in [SyncStatusTracker.track].
 * This test exercises that binding shape: action = `{ tracker.track { reconcile } }`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SyncReconnectTrackerTest {

    @Test
    fun `reconnect action drives tracker through Syncing then Idle`() = runBlocking {
        val tracker = SyncStatusTracker(RingBufferLogger())
        val gate = CompletableDeferred<Unit>()
        val action = SocketReconnectAction {
            tracker.track {
                gate.await()
                SyncOutcome(emptyList(), null)
            }
        }

        collectEmissions(tracker.status) { emissions ->
            assertEquals(SyncStatus.Idle, emissions.next())

            val job = async(Dispatchers.Default) { action.onReconnect() }
            assertEquals(SyncStatus.Syncing, emissions.next())
            gate.complete(Unit)
            job.await()
            assertEquals(SyncStatus.Idle, emissions.next())
        }
    }

    private suspend fun <T> collectEmissions(
        flow: Flow<T>,
        block: suspend (Channel<T>) -> Unit,
    ) {
        val channel = Channel<T>(Channel.UNLIMITED)
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            flow.collect { channel.send(it) }
        }
        try {
            block(channel)
        } finally {
            job.cancel()
            channel.close()
        }
    }

    private suspend fun Channel<SyncStatus>.next(): SyncStatus =
        withTimeout(5_000) { receive() }
}
