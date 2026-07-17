package app.rebubble.data.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RingBufferLoggerTest {

    @Test
    fun `caps at 500 entries dropping oldest`() {
        val logger = RingBufferLogger(capacity = 500)
        repeat(520) { i -> logger.log("t", "m$i") }

        val snap = logger.snapshot()
        assertEquals(500, snap.size)
        assertTrue(snap.first().contains("m20"))
        assertTrue(snap.last().contains("m519"))
    }

    @Test
    fun `snapshot preserves insertion order`() {
        val logger = RingBufferLogger(capacity = 10)
        logger.log("a", "one")
        logger.log("b", "two")
        logger.log("c", "three")

        val snap = logger.snapshot()
        assertEquals(3, snap.size)
        assertTrue(snap[0].contains("one"))
        assertTrue(snap[1].contains("two"))
        assertTrue(snap[2].contains("three"))
        assertTrue(snap[0].contains("a"))
        assertTrue(snap[1].contains("b"))
    }

    @Test
    fun `concurrent writers never throw and honor capacity`() {
        val logger = RingBufferLogger(capacity = 500)
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        repeat(threads) { t ->
            pool.execute {
                try {
                    repeat(perThread) { i -> logger.log("t$t", "m$i") }
                } catch (_: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, errors.get())
        assertEquals(500, logger.snapshot().size)
    }
}
