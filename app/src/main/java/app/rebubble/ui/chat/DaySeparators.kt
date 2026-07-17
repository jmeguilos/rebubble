package app.rebubble.ui.chat

import app.rebubble.data.local.entity.MessageEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One row in the reverseLayout chat list (newest-first). Separators sit after the last (oldest)
 * message of each calendar day so they render above that day's bubbles.
 */
sealed interface ChatTimelineItem {
    val key: String

    data class DaySeparator(
        val label: String,
        val dayEpochDay: Long,
    ) : ChatTimelineItem {
        override val key: String get() = "day-$dayEpochDay"
    }

    data class Message(
        val entity: MessageEntity,
    ) : ChatTimelineItem {
        override val key: String get() = entity.guid
    }
}

private val MONTH_DAY: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US)

/**
 * Inserts day separators into a newest-first [messages] list.
 *
 * Labels: "Today" / "Yesterday" / "MMM d" (e.g. "Jul 12") relative to [nowMs] in [zone].
 */
fun insertDaySeparators(
    messages: List<MessageEntity>,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ChatTimelineItem> {
    if (messages.isEmpty()) return emptyList()

    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val yesterday = today.minusDays(1)
    val result = ArrayList<ChatTimelineItem>(messages.size + 4)
    var currentDay: LocalDate? = null

    for (msg in messages) {
        val day = Instant.ofEpochMilli(msg.dateCreated).atZone(zone).toLocalDate()
        if (currentDay != null && day != currentDay) {
            result.add(
                ChatTimelineItem.DaySeparator(
                    label = daySeparatorLabel(currentDay, today, yesterday),
                    dayEpochDay = currentDay.toEpochDay(),
                ),
            )
            currentDay = day
        } else if (currentDay == null) {
            currentDay = day
        }
        result.add(ChatTimelineItem.Message(msg))
    }
    val lastDay = currentDay
    if (lastDay != null) {
        result.add(
            ChatTimelineItem.DaySeparator(
                label = daySeparatorLabel(lastDay, today, yesterday),
                dayEpochDay = lastDay.toEpochDay(),
            ),
        )
    }
    return result
}

internal fun daySeparatorLabel(
    day: LocalDate,
    today: LocalDate,
    yesterday: LocalDate,
): String = when (day) {
    today -> "Today"
    yesterday -> "Yesterday"
    else -> MONTH_DAY.format(day)
}
