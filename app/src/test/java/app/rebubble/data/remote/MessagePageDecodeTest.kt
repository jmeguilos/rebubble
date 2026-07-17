package app.rebubble.data.remote

import app.rebubble.data.remote.dto.Envelope
import app.rebubble.data.remote.dto.MessageDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes fixtures/message-page.json (see its "_source" field for the exact
 * BlueBubbles serializer lines this fixture was derived from). Covers a plain
 * text message, a group-event message (null text), a reaction/tapback message
 * (associatedMessageGuid/Type set, null text), and an attachment message.
 */
class MessagePageDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes message page with ms-epoch dates, null text, group events, reactions, attachments`() {
        val raw = loadFixture("message-page.json")

        val envelope: Envelope<List<MessageDto>> = json.decodeFromString(raw)

        assertEquals(200, envelope.status)
        val messages = requireNotNull(envelope.data)
        assertEquals(4, messages.size)

        // Plain text message: ms-epoch dates decode as Long, not truncated/lossy.
        val plain = messages[0]
        assertEquals("Hey, are we still on for Saturday?", plain.text)
        assertEquals(1752345600000L, plain.dateCreated)
        assertEquals(1752345660000L, plain.dateRead)
        assertEquals(1752345605000L, plain.dateDelivered)
        assertFalse(plain.isFromMe)
        assertEquals("+15557654321", plain.handle?.address)

        // Group-event message: text is null (server omits literal text for these).
        val groupEvent = messages[1]
        assertNull(groupEvent.text)
        assertEquals(1, groupEvent.itemType)
        assertEquals(1, groupEvent.groupActionType)

        // Reaction/tapback message: null text, associatedMessageGuid/Type set.
        val reaction = messages[2]
        assertNull(reaction.text)
        assertEquals("p:0/A1B2C3D4-0000-0000-0000-000000000001", reaction.associatedMessageGuid)
        assertEquals("love", reaction.associatedMessageType)
        assertTrue(reaction.isFromMe)
        assertEquals(503L, reaction.originalRowId)

        // Attachment message.
        val withAttachment = messages[3]
        assertEquals(504L, withAttachment.originalRowId)
        assertEquals(1, withAttachment.attachments.size)
        val attachment = withAttachment.attachments[0]
        assertEquals(88L, attachment.originalRowId)
        assertEquals("image/jpeg", attachment.mimeType)
        assertEquals(2481932L, attachment.totalBytes)
        assertEquals(3024, attachment.height)
        assertEquals(4032, attachment.width)
        assertTrue(attachment.hasLivePhoto)

        // Pagination metadata: {offset, limit, total, count}
        val metadata = requireNotNull(envelope.metadata)
        assertEquals(0, metadata.offset)
        assertEquals(100, metadata.limit)
        assertEquals(4, metadata.total)
        assertEquals(4, metadata.count)
    }
}
