package app.rebubble.data.remote.socket

import app.rebubble.data.remote.loadFixture
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketPayloadParserTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val parser = SocketPayloadParser(json)

    @Test
    fun `new-message fixture becomes NewMessage with correct guid`() {
        val raw = loadFixture("socket-new-message.json")
        val event = parser.parse(SocketPayloadParser.EVENT_NEW_MESSAGE, raw)
        val msg = event as SocketEvent.NewMessage
        assertEquals("p:0/A1B2C3D4-0000-0000-0000-000000000001", msg.dto.guid)
        assertEquals("Hey, are we still on for Saturday?", msg.dto.text)
        assertEquals("iMessage;-;+15557654321", msg.dto.chats?.first()?.guid)
    }

    @Test
    fun `updated-message fixture becomes UpdatedMessage with correct guid`() {
        val raw = loadFixture("socket-new-message.json")
        val event = parser.parse(SocketPayloadParser.EVENT_UPDATED_MESSAGE, raw)
        val msg = event as SocketEvent.UpdatedMessage
        assertEquals("p:0/A1B2C3D4-0000-0000-0000-000000000001", msg.dto.guid)
    }

    @Test
    fun `typing-indicator parses display and guid as chatGuid`() {
        val event = parser.parse(
            SocketPayloadParser.EVENT_TYPING_INDICATOR,
            """{"display":true,"guid":"iMessage;-;+15551234567"}""",
        )
        val typing = event as SocketEvent.TypingIndicator
        assertEquals("iMessage;-;+15551234567", typing.chatGuid)
        assertTrue(typing.display)
    }

    @Test
    fun `chat-read-status-changed prefers read field`() {
        val event = parser.parse(
            SocketPayloadParser.EVENT_CHAT_READ_STATUS_CHANGED,
            """{"chatGuid":"chat-1","read":true}""",
        )
        val read = event as SocketEvent.ChatReadStatusChanged
        assertEquals("chat-1", read.chatGuid)
        assertTrue(read.read)
    }

    @Test
    fun `chat-read-status-changed falls back to status field`() {
        val event = parser.parse(
            SocketPayloadParser.EVENT_CHAT_READ_STATUS_CHANGED,
            """{"chatGuid":"chat-2","status":false}""",
        )
        val read = event as SocketEvent.ChatReadStatusChanged
        assertEquals("chat-2", read.chatGuid)
        assertEquals(false, read.read)
    }

    @Test
    fun `unknown event name becomes Unknown`() {
        val event = parser.parse("some-future-event", """{"x":1}""")
        assertEquals(SocketEvent.Unknown("some-future-event"), event)
    }

    @Test
    fun `malformed JSON for known event becomes Unknown without throwing`() {
        val event = parser.parse(SocketPayloadParser.EVENT_NEW_MESSAGE, "{not-json")
        assertEquals(SocketEvent.Unknown(SocketPayloadParser.EVENT_NEW_MESSAGE), event)
    }

    @Test
    fun `message-send-error keeps raw payload`() {
        val raw = """{"guid":"x","error":4}"""
        val event = parser.parse(SocketPayloadParser.EVENT_MESSAGE_SEND_ERROR, raw)
        assertEquals(SocketEvent.MessageSendError(raw), event)
    }
}
