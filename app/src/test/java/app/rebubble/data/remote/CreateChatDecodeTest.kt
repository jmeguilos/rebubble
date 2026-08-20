package app.rebubble.data.remote

import app.rebubble.data.remote.dto.ChatDto
import app.rebubble.data.remote.dto.Envelope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes fixtures/create-chat-response.json (see its "_source" field): the `POST /chat/new`
 * response embeds the newly created chat's participants *and* its first sent message on the same
 * [ChatDto] object -- there is no separate top-level message field -- with the request's
 * `tempGuid` echoed back onto that message exactly like [SentMessageDecodeTest]'s `message/text`
 * case.
 */
class CreateChatDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes created chat envelope with embedded messages and echoed tempGuid`() {
        val raw = loadFixture("create-chat-response.json")

        val envelope: Envelope<ChatDto> = json.decodeFromString(raw)

        assertEquals(200, envelope.status)
        assertEquals("Successfully created chat!", envelope.message)
        assertNull(envelope.error)

        val chat = requireNotNull(envelope.data)
        assertEquals(14L, chat.originalRowId)
        assertEquals("iMessage;-;+15559998888", chat.guid)
        assertEquals(45, chat.style)
        assertEquals("+15559998888", chat.chatIdentifier)
        assertNull(chat.displayName)
        assertEquals(1, chat.participants.size)
        assertEquals("+15559998888", chat.participants[0].address)

        val messages = requireNotNull(chat.messages)
        assertEquals(1, messages.size)
        val message = messages[0]
        assertEquals(701L, message.originalRowId)
        assertEquals("temp-new-chat-9f0a-1234567890ab", message.tempGuid)
        assertEquals("p:0/B2C3D4E5-0000-0000-0000-000000000701", message.guid)
        assertEquals("Hey, it's Jules", message.text)
        assertTrue(message.isFromMe)
        // The create-chat response's embedded message never carries its own chats[] (the
        // serializer's config.includeChats is hardcoded false, ChatSerializer.ts line 71) --
        // callers must route it with an explicit fallbackChatGuid (see ChatRepository.startChat).
        assertNull(message.chats)
    }
}
