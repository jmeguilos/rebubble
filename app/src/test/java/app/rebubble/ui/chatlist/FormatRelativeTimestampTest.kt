package app.rebubble.ui.chatlist

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Fixed-clock buckets for [formatRelativeTimestamp]:
 * <1min → "now"; <60min → "Xm"; same calendar day → "HH:mm";
 * <7 days → weekday; else → "MMM d".
 */
class FormatRelativeTimestampTest {

    private val zone = TimeZone.getTimeZone("UTC")
    private val locale = Locale.US

    private fun ms(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
        second: Int = 0,
    ): Long {
        val cal = Calendar.getInstance(zone, locale)
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis
    }

    @Test
    fun `under one minute is now`() {
        val now = ms(2026, 7, 17, 14, 30, 0)
        assertEquals("now", formatRelativeTimestamp(now, now - 30_000L, locale, zone))
        assertEquals("now", formatRelativeTimestamp(now, now, locale, zone))
    }

    @Test
    fun `under sixty minutes is Xm`() {
        val now = ms(2026, 7, 17, 14, 30, 0)
        assertEquals("1m", formatRelativeTimestamp(now, now - 60_000L, locale, zone))
        assertEquals("5m", formatRelativeTimestamp(now, now - 5 * 60_000L, locale, zone))
        assertEquals("59m", formatRelativeTimestamp(now, now - 59 * 60_000L, locale, zone))
    }

    @Test
    fun `same calendar day is HH colon mm`() {
        val now = ms(2026, 7, 17, 14, 30, 0)
        val then = ms(2026, 7, 17, 9, 5, 0)
        assertEquals("09:05", formatRelativeTimestamp(now, then, locale, zone))
    }

    @Test
    fun `within seven days is weekday abbreviation`() {
        // Friday Jul 17 2026; then = Tuesday Jul 14
        val now = ms(2026, 7, 17, 14, 30, 0)
        val then = ms(2026, 7, 14, 10, 0, 0)
        assertEquals("Tue", formatRelativeTimestamp(now, then, locale, zone))
    }

    @Test
    fun `older than seven days is MMM d`() {
        val now = ms(2026, 7, 17, 14, 30, 0)
        val then = ms(2026, 7, 3, 10, 0, 0)
        assertEquals("Jul 3", formatRelativeTimestamp(now, then, locale, zone))
    }
}
