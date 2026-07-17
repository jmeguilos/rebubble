package app.rebubble.data.sync

import app.rebubble.data.logging.RingBufferLogger;
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SyncStatusTrackerTest {

    @Test
    fun `track transitions Idle to Syncing to Idle on success`() = runBlocking {
        val tracker = SyncStatusTracker(RingBufferLogger())
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
        val tracker = SyncStatusTracker(RingBufferLogger())
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

    @Test
    fun `overlapping tracks stay Syncing until last completes`() = runBlocking {
        val tracker = SyncStatusTracker(RingBufferLogger())
        collectEmissions(tracker.status) { emissions ->
            assertEquals(SyncStatus.Idle, emissions.next())

            val gateA = CompletableDeferred<Unit>()
            val gateB = CompletableDeferred<Unit>()
            val jobA = async(Dispatchers.Default) {
                tracker.track {
                    gateA.await()
                    SyncOutcome(emptyList(), null)
                }
            }
            assertEquals(SyncStatus.Syncing, emissions.next())

            val bEntered = CompletableDeferred<Unit>()
            val jobB = async(Dispatchers.Default) {
                tracker.track {
                    bEntered.complete(Unit)
                    gateB.await()
                    SyncOutcome(emptyList(), null)
                }
            }
            bEntered.await()
            assertEquals(SyncStatus.Syncing, tracker.status.value)

            gateA.complete(Unit)
            jobA.await()
            assertEquals(SyncStatus.Syncing, tracker.status.value)

            gateB.complete(Unit)
            jobB.await()
            assertEquals(SyncStatus.Idle, emissions.next())
        }
    }

    @Test
    fun `overlapping error then later success drains to Idle`() = runBlocking {
        // Drain rule: lastError is recorded while count>0; on count→0 surface Error(lastError)
        // unless a later call succeeded (success clears lastError) → Idle.
        val tracker = SyncStatusTracker(RingBufferLogger())
        collectEmissions(tracker.status) { emissions ->
            assertEquals(SyncStatus.Idle, emissions.next())

            val gateA = CompletableDeferred<Unit>()
            val gateB = CompletableDeferred<Unit>()
            val jobA = async(Dispatchers.Default) {
                tracker.track {
                    gateA.await()
                    SyncOutcome(emptyList(), IOException("first failed"))
                }
            }
            assertEquals(SyncStatus.Syncing, emissions.next())

            val bEntered = CompletableDeferred<Unit>()
            val jobB = async(Dispatchers.Default) {
                tracker.track {
                    bEntered.complete(Unit)
                    gateB.await()
                    SyncOutcome(emptyList(), null)
                }
            }
            bEntered.await()

            gateA.complete(Unit)
            jobA.await()
            assertEquals(SyncStatus.Syncing, tracker.status.value)

            gateB.complete(Unit)
            jobB.await()
            assertEquals(SyncStatus.Idle, emissions.next())
        }
    }

    @Test
    fun `throwing block ends Error and propagates`() = runBlocking {
        val tracker = SyncStatusTracker(RingBufferLogger())
        collectEmissions(tracker.status) { emissions ->
            assertEquals(SyncStatus.Idle, emissions.next())

            supervisorScope {
                val gate = CompletableDeferred<Unit>()
                val job = async(Dispatchers.Default) {
                    tracker.track {
                        gate.await()
                        throw RuntimeException("boom")
                    }
                }
                assertEquals(SyncStatus.Syncing, emissions.next())
                gate.complete(Unit)

                try {
                    job.await()
                    fail("expected RuntimeException")
                } catch (e: RuntimeException) {
                    assertEquals("boom", e.message)
                }
            }

            val error = emissions.next()
            assertTrue(error is SyncStatus.Error)
            assertEquals("boom", (error as SyncStatus.Error).message)
        }
    }

    @Test
    fun `concurrent tracks never Idle or Error while body running`() = runBlocking {
        val tracker = SyncStatusTracker(RingBufferLogger())
        val runningBodies = AtomicInteger(0)
        val violations = AtomicInteger(0)
        val n = 200
        val started = CompletableDeferred<Unit>()

        val sampler = launch(Dispatchers.Default) {
            started.await()
            while (isActive) {
                // Read running first: Syncing is written before the body runs, so
                // running>0 ⇒ entry's Syncing write already happened (happens-before).
                val running = runningBodies.get()
                val status = tracker.status.value
                if (running > 0 && (status is SyncStatus.Idle || status is SyncStatus.Error)) {
                    violations.incrementAndGet()
                }
            }
        }

        // N=200 track() calls on Default, pipelined across a few workers so entries
        // overlap drains (the TOCTOU window). Mix success/error outcomes.
        val remaining = AtomicInteger(n)
        val jobs = List(2) { w ->
            async(Dispatchers.Default) {
                started.complete(Unit)
                while (remaining.getAndDecrement() > 0) {
                    tracker.track {
                        runningBodies.incrementAndGet()
                        try {
                            // In-body checks + sampler: stuck Idle/Error must never be observed.
                            if (tracker.status.value !is SyncStatus.Syncing) {
                                violations.incrementAndGet()
                            }
                            // Wall-clock hold (not delay) so Robolectric virtual time cannot skip it.
                            Thread.sleep(1)
                            if (tracker.status.value !is SyncStatus.Syncing) {
                                violations.incrementAndGet()
                            }
                            if (w % 2 == 0) {
                                SyncOutcome(emptyList(), null)
                            } else {
                                SyncOutcome(emptyList(), IOException("err-$w"))
                            }
                        } finally {
                            runningBodies.decrementAndGet()
                        }
                    }
                }
            }
        }

        jobs.awaitAll()
        sampler.cancel()

        assertEquals(0, runningBodies.get())
        assertEquals(
            "status Idle/Error while a track body was mid-execution (TOCTOU)",
            0,
            violations.get(),
        )
        val finalStatus = tracker.status.value
        assertTrue(
            "final status must be Idle or Error per drain rule, was $finalStatus",
            finalStatus is SyncStatus.Idle || finalStatus is SyncStatus.Error,
        )
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
