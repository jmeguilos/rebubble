package app.rebubble.data.remote

import app.rebubble.data.remote.dto.Envelope
import app.rebubble.data.remote.dto.MessageDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes fixtures/sent-message-with-tempguid.json (see its "_source" field).
 * Verifies the client-generated tempGuid is echoed back on the sent message,
 * matching messageRouter.ts sendText()'s `data.tempGuid = tempGuid` injection.
 */
class SentMessageDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes sent message envelope with echoed tempGuid`() {
        val raw = loadFixture("sent-message-with-tempguid.json")

        val envelope: Envelope<MessageDto> = json.decodeFromString(raw)

        assertEquals(200, envelope.status)
        assertEquals("Message sent!", envelope.message)

        val message = requireNotNull(envelope.data)
        assertEquals(601L, message.originalRowId)
        assertEquals("temp-3f1a9c2e-4b7d-4e11-9f0a-1234567890ab", message.tempGuid)
        assertEquals("p:0/A1B2C3D4-0000-0000-0000-000000000601", message.guid)
        assertTrue(message.isFromMe)
        assertEquals(1752346000000L, message.dateCreated)
    }
}
