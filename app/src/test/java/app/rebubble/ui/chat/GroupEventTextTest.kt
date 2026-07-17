package app.rebubble.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupEventTextTest {

    @Test
    fun `itemType 1 add and remove with names`() {
        assertEquals(
            "Alex added Jordan to the conversation",
            formatGroupEventText(1, 0, null, "Alex", "Jordan"),
        )
        assertEquals(
            "Alex removed Jordan from the conversation",
            formatGroupEventText(1, 1, null, "Alex", "Jordan"),
        )
    }

    @Test
    fun `itemType 1 falls back to someone`() {
        assertEquals(
            "Someone added someone to the conversation",
            formatGroupEventText(1, 0, null, "Someone"),
        )
    }

    @Test
    fun `itemType 2 rename and name removed`() {
        assertEquals(
            "Alex named the conversation \"Ski trip\"",
            formatGroupEventText(2, 0, "Ski trip", "Alex"),
        )
        assertEquals(
            "Alex removed the name from the conversation",
            formatGroupEventText(2, 0, null, "Alex"),
        )
        assertEquals(
            "Alex removed the name from the conversation",
            formatGroupEventText(2, 0, "  ", "Alex"),
        )
    }

    @Test
    fun `itemType 3 left photo changed photo removed`() {
        assertEquals(
            "Alex left the conversation",
            formatGroupEventText(3, 0, null, "Alex"),
        )
        assertEquals(
            "Alex changed the group photo",
            formatGroupEventText(3, 1, null, "Alex"),
        )
        assertEquals(
            "Alex removed the group photo",
            formatGroupEventText(3, 2, null, "Alex"),
        )
    }

    @Test
    fun `resolveGroupEventSenderName You contact Someone`() {
        assertEquals("You", resolveGroupEventSenderName(true, "+1", emptyMap()))
        assertEquals(
            "Alex",
            resolveGroupEventSenderName(false, "+1", mapOf("+1" to "Alex")),
        )
        assertEquals("Someone", resolveGroupEventSenderName(false, "+1", emptyMap()))
        assertEquals("Someone", resolveGroupEventSenderName(false, null, emptyMap()))
    }
}
