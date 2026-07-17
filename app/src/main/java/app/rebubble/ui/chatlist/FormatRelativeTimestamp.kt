package app.rebubble.ui.chatlist

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Relative timestamp for chat-list rows.
 *
 * Buckets (fixed clock): under 1 minute → `"now"`; under 60 minutes → `"Xm"`; same calendar day →
 * `"HH:mm"`; under 7 days → weekday abbreviation; else → `"MMM d"`.
 */
fun formatRelativeTimestamp(
    nowMs: Long,
    thenMs: Long,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val deltaMs = (nowMs - thenMs).coerceAtLeast(0L)
    if (deltaMs < TimeUnit.MINUTES.toMillis(1)) return "now"
    if (deltaMs < TimeUnit.HOURS.toMillis(1)) {
        return "${TimeUnit.MILLISECONDS.toMinutes(deltaMs)}m"
    }

    val nowCal = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMs }
    val thenCal = Calendar.getInstance(timeZone, locale).apply { timeInMillis = thenMs }
    val sameDay =
        nowCal.get(Calendar.YEAR) == thenCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == thenCal.get(Calendar.DAY_OF_YEAR)
    if (sameDay) {
        return SimpleDateFormat("HH:mm", locale).apply { this.timeZone = timeZone }
            .format(Date(thenMs))
    }

    if (deltaMs < TimeUnit.DAYS.toMillis(7)) {
        return SimpleDateFormat("EEE", locale).apply { this.timeZone = timeZone }
            .format(Date(thenMs))
    }

    return SimpleDateFormat("MMM d", locale).apply { this.timeZone = timeZone }
        .format(Date(thenMs))
}
