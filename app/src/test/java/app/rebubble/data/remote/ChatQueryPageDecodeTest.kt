package app.rebubble.data.remote

import app.rebubble.data.remote.dto.ChatDto
import app.rebubble.data.remote.dto.Envelope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decodes fixtures/chat-query-page.json (see its "_source" field for the exact
 * BlueBubbles serializer lines this fixture was derived from).
 */
class ChatQueryPageDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes paginated chat list envelope`() {
        val raw = loadFixture("chat-query-page.json")

        val envelope: Envelope<List<ChatDto>> = json.decodeFromString(raw)

        assertEquals(200, envelope.status)
        assertEquals("Success", envelope.message)
        assertNull(envelope.error)

        val chats = requireNotNull(envelope.data)
        assertEquals(2, chats.size)

        val dm = chats[0]
        assertEquals("iMessage;-;+15551234567", dm.guid)
        assertEquals(45, dm.style)
        assertEquals("+15551234567", dm.chatIdentifier)
        assertNull(dm.displayName)
        assertEquals(1, dm.participants.size)
        assertEquals("+15551234567", dm.participants[0].address)
        assertEquals("iMessage", dm.participants[0].service)

        val group = chats[1]
        assertEquals(43, group.style)
        assertEquals("Weekend Trip", group.displayName)
        assertEquals(2, group.participants.size)

        // Pagination metadata: {offset, limit, total, count}
        val metadata = requireNotNull(envelope.metadata)
        assertEquals(0, metadata.offset)
        assertEquals(1000, metadata.limit)
        assertEquals(2, metadata.total)
        assertEquals(2, metadata.count)
    }
}
