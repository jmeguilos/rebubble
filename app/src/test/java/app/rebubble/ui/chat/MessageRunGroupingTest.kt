package app.rebubble.ui.chat

import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRunGroupingTest {

    private fun msg(
        guid: String,
        dateCreated: Long,
        isFromMe: Boolean = true,
        senderAddress: String? = null,
        itemType: Int = 0,
    ) = MessageEntity(
        guid = guid,
        chatGuid = "chat",
        originalRowId = null,
        text = guid,
        subject = null,
        isFromMe = isFromMe,
        senderAddress = senderAddress,
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
        itemType = itemType,
    )

    @Test
    fun `single bubble is both first and last in its run`() {
        val flags = computeBubbleRunFlags(listOf(msg("a", 1_000L)))
        assertEquals(
            BubbleRunFlags(isFirstInRun = true, isLastInRun = true),
            flags["a"],
        )
    }

    @Test
    fun `same sender within 60s groups with the run seam only on the last bubble`() {
        val flags = computeBubbleRunFlags(
            listOf(
                msg("a", 1_000L),
                msg("b", 30_000L),
                msg("c", 50_000L),
            ),
        )
        assertEquals(BubbleRunFlags(isFirstInRun = true, isLastInRun = false), flags["a"])
        assertEquals(BubbleRunFlags(isFirstInRun = false, isLastInRun = false), flags["b"])
        assertEquals(BubbleRunFlags(isFirstInRun = false, isLastInRun = true), flags["c"])
    }

    @Test
    fun `gap over 60s breaks run`() {
        val flags = computeBubbleRunFlags(
            listOf(
                msg("a", 1_000L),
                msg("b", 1_000L + RUN_GROUP_WINDOW_MS + 1),
            ),
        )
        assertTrue(flags["a"]!!.isLastInRun)
        assertTrue(flags["b"]!!.isLastInRun)
        assertTrue(flags["b"]!!.isFirstInRun)
    }

    @Test
    fun `exactly 60s still groups`() {
        val flags = computeBubbleRunFlags(
            listOf(
                msg("a", 1_000L),
                msg("b", 1_000L + RUN_GROUP_WINDOW_MS),
            ),
        )
        assertFalse(flags["a"]!!.isLastInRun)
        assertTrue(flags["a"]!!.isFirstInRun)
        assertTrue(flags["b"]!!.isLastInRun)
    }

    @Test
    fun `sender change breaks run even within 60s`() {
        val flags = computeBubbleRunFlags(
            listOf(
                msg("me", 1_000L, isFromMe = true),
                msg("them", 2_000L, isFromMe = false, senderAddress = "+1"),
            ),
        )
        assertTrue(flags["me"]!!.isLastInRun)
        assertTrue(flags["them"]!!.isLastInRun)
    }

    @Test
    fun `different incoming addresses are different senders`() {
        val flags = computeBubbleRunFlags(
            listOf(
                msg("a", 1_000L, isFromMe = false, senderAddress = "+1"),
                msg("b", 2_000L, isFromMe = false, senderAddress = "+2"),
            ),
        )
        assertTrue(flags["a"]!!.isLastInRun)
        assertTrue(flags["b"]!!.isLastInRun)
    }

    @Test
    fun `same incoming address within window groups`() {
        val flags = computeBubbleRunFlags(
            listOf(
                msg("a", 1_000L, isFromMe = false, senderAddress = "+1"),
                msg("b", 2_000L, isFromMe = false, senderAddress = "+1"),
            ),
        )
        assertFalse(flags["a"]!!.isLastInRun)
        assertTrue(flags["b"]!!.isLastInRun)
    }

    @Test
    fun `group-event rows are ignored`() {
        val flags = computeBubbleRunFlags(
            listOf(
                msg("a", 1_000L),
                msg("evt", 2_000L, itemType = 2),
                msg("b", 3_000L),
            ),
        )
        assertEquals(setOf("a", "b"), flags.keys)
        // a and b still group across the ignored event (same sender, within window)
        assertFalse(flags["a"]!!.isLastInRun)
        assertTrue(flags["b"]!!.isLastInRun)
    }

    @Test
    fun `newest-first input yields same flags`() {
        val chronological = listOf(msg("a", 1_000L), msg("b", 2_000L))
        val newestFirst = chronological.reversed()
        assertEquals(computeBubbleRunFlags(chronological), computeBubbleRunFlags(newestFirst))
    }
}
