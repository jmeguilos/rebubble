package app.rebubble.ui.chat

import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestOwnMessageGuidTest {

    private fun bubble(
        guid: String,
        isFromMe: Boolean,
        dateCreated: Long = 0L,
    ) = ChatUiItem.Bubble(
        message = MessageEntity(
            guid = guid,
            chatGuid = "chat",
            originalRowId = null,
            text = guid,
            subject = null,
            isFromMe = isFromMe,
            senderAddress = if (isFromMe) null else "+1",
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
        ),
        attachments = emptyList(),
        showTail = true,
        isFirstInRun = true,
        isLastInRun = true,
    )

    @Test
    fun `newest-first list picks first own bubble`() {
        // Newest at index 0 (reverseLayout LazyColumn).
        val items = listOf(
            bubble("own-new", isFromMe = true, dateCreated = 300L),
            bubble("theirs", isFromMe = false, dateCreated = 200L),
            bubble("own-old", isFromMe = true, dateCreated = 100L),
        )
        assertEquals("own-new", latestOwnMessageGuid(items))
    }

    @Test
    fun `loadOlder append at tail leaves latest own unchanged`() {
        val newestFirst = listOf(
            bubble("own-latest", isFromMe = true, dateCreated = 400L),
            bubble("theirs-1", isFromMe = false, dateCreated = 350L),
            bubble("own-mid", isFromMe = true, dateCreated = 300L),
        )
        val before = latestOwnMessageGuid(newestFirst)
        assertEquals("own-latest", before)

        // Simulated loadOlder: older page appended at the tail (higher indices).
        val afterLoadOlder = newestFirst + listOf(
            bubble("own-older", isFromMe = true, dateCreated = 50L),
            bubble("theirs-old", isFromMe = false, dateCreated = 40L),
        )
        assertEquals(before, latestOwnMessageGuid(afterLoadOlder))
        assertEquals("own-latest", latestOwnMessageGuid(afterLoadOlder))
    }

    @Test
    fun `no own bubbles yields null`() {
        val items = listOf(
            bubble("a", isFromMe = false),
            ChatUiItem.DaySeparator(label = "Today", dayEpochDay = 0),
            bubble("b", isFromMe = false),
        )
        assertNull(latestOwnMessageGuid(items))
    }
}
