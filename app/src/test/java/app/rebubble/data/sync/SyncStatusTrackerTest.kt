package app.rebubble.data.sync

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SyncStatusTrackerTest {

    @Test
    fun `track transitions Idle to Syncing to Idle on success`() = runBlocking {
        val tracker = SyncStatusTracker()
        collectEmissions(tracker.status) { emissions ->
            assertEquals(SyncStatus.Idle, emissions.next())

            val gate = CompletableDeferred<Unit>()
            val job = async(Dispatchers.Default) {
                tracker.track {
                    gate.await()
                    SyncOutcome(emptyList(), null)
                }
            }

            assertEquals(SyncStatus.Syncing, emissions.next())
            gate.complete(Unit)
            job.await()
            assertEquals(SyncStatus.Idle, emissions.next())
        }
    }

    @Test
    fun `track transitions to Error with message then Idle on next success`() = runBlocking {
        val tracker = SyncStatusTracker()
        collectEmissions(tracker.status) { emissions ->
            assertEquals(SyncStatus.Idle, emissions.next())

            val failGate = CompletableDeferred<Unit>()
            val failJob = async(Dispatchers.Default) {
                tracker.track {
                    failGate.await()
                    SyncOutcome(emptyList(), IOException("network down"))
                }
            }
            assertEquals(SyncStatus.Syncing, emissions.next())
            failGate.complete(Unit)
            failJob.await()

            val error = emissions.next()
            assertTrue(error is SyncStatus.Error)
            assertEquals("network down", (error as SyncStatus.Error).message)
            assertTrue(error.at > 0L)

            val okGate = CompletableDeferred<Unit>()
            val okJob = async(Dispatchers.Default) {
                tracker.track {
                    okGate.await()
                    SyncOutcome(listOf("g1"), null)
                }
            }
            assertEquals(SyncStatus.Syncing, emissions.next())
            okGate.complete(Unit)
            okJob.await()
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
