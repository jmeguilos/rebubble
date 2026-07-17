package app.rebubble.ui.chat

import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DaySeparatorsTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun msg(guid: String, dateCreated: Long) = MessageEntity(
        guid = guid,
        chatGuid = "chat",
        originalRowId = null,
        text = guid,
        subject = null,
        isFromMe = true,
        senderAddress = null,
        dateCreated = dateCreated,
        dateRead = null,
        dateDelivered = null,
        groupTitle = null,
        associatedMessageGuid = null,
        associatedMessageType = null,
        threadOriginatorGuid = null,
        expressiveSendStyleId = null,
        dateEdited = null,
        dateRetracted = null,
        sendStatus = SendStatus.SENT,
    )

    @Test
    fun `empty list yields empty`() {
        assertTrue(insertDaySeparators(emptyList(), nowMs = 0L, zone = zone).isEmpty())
    }

    @Test
    fun `Today and Yesterday labels with fixed clock`() {
        // Fixed "now": 2026-07-17 15:00 PDT
        val now = at(2026, 7, 17, 15)
        val todayMsg = msg("t", at(2026, 7, 17, 10))
        val yestMsg = msg("y", at(2026, 7, 16, 18))
        // newest-first
        val items = insertDaySeparators(listOf(todayMsg, yestMsg), nowMs = now, zone = zone)
        assertEquals(
            listOf(
                "t",
                "Today",
                "y",
                "Yesterday",
            ),
            items.map {
                when (it) {
                    is ChatTimelineItem.Message -> it.entity.guid
                    is ChatTimelineItem.DaySeparator -> it.label
                }
            },
        )
    }

    @Test
    fun `older day uses MMM d`() {
        val now = at(2026, 7, 17, 15)
        val older = msg("o", at(2026, 7, 12, 9))
        val items = insertDaySeparators(listOf(older), nowMs = now, zone = zone)
        assertEquals(2, items.size)
        assertEquals("o", (items[0] as ChatTimelineItem.Message).entity.guid)
        assertEquals("Jul 12", (items[1] as ChatTimelineItem.DaySeparator).label)
    }

    @Test
    fun `same-day messages share one separator after the oldest`() {
        val now = at(2026, 7, 17, 15)
        val newer = msg("n", at(2026, 7, 17, 14))
        val older = msg("o", at(2026, 7, 17, 9))
        val items = insertDaySeparators(listOf(newer, older), nowMs = now, zone = zone)
        assertEquals(
            listOf("n", "o", "Today"),
            items.map {
                when (it) {
                    is ChatTimelineItem.Message -> it.entity.guid
                    is ChatTimelineItem.DaySeparator -> it.label
                }
            },
        )
    }

    @Test
    fun `daySeparatorLabel helpers`() {
        val today = LocalDate.of(2026, 7, 17)
        val yesterday = today.minusDays(1)
        assertEquals("Today", daySeparatorLabel(today, today, yesterday))
        assertEquals("Yesterday", daySeparatorLabel(yesterday, today, yesterday))
        assertEquals("Jul 12", daySeparatorLabel(LocalDate.of(2026, 7, 12), today, yesterday))
    }
}
