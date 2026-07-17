package app.rebubble.data.remote

import app.rebubble.data.remote.dto.requests.ChatQueryRequest
import app.rebubble.data.remote.dto.requests.FcmDeviceRequest
import app.rebubble.data.remote.dto.requests.MessageQueryRequest
import app.rebubble.data.remote.dto.requests.SendTextRequest
import app.rebubble.data.remote.dto.requests.WhereClause
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity round-trip for the request-body DTOs. These aren't decoded from
 * server fixtures (they're bodies the client sends), so this just verifies
 * the wire field names/shapes match the routes they target:
 *
 * - ChatQueryRequest -> chatRouter.ts query(), lines 118-127
 * - MessageQueryRequest / WhereClause -> messageRouter.ts query(), lines 114-125;
 *   DBWhereItem shape in databases/imessage/types.ts lines 15-18
 * - SendTextRequest -> messageRouter.ts sendText(), lines 238-241
 * - FcmDeviceRequest -> fcmRouter.ts registerDevice(), line 36
 */
class RequestDtoEncodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ChatQueryRequest encodes with,sort,offset,limit`() {
        val request = ChatQueryRequest(with = listOf("participants"), sort = "lastmessage", offset = 0, limit = 100)
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<ChatQueryRequest>(encoded)
        assertEquals(request, decoded)
        assertTrue(encoded.contains("\"with\""))
        assertTrue(encoded.contains("\"sort\""))
    }

    @Test
    fun `MessageQueryRequest encodes where clauses with statement and args`() {
        val request = MessageQueryRequest(
            chatGuid = "iMessage;-;+15551234567",
            with = listOf("chats"),
            where = listOf(WhereClause(statement = "message.text LIKE :term COLLATE NOCASE", args = kotlinx.serialization.json.JsonObject(mapOf("term" to JsonPrimitive("%hi%"))))),
            sort = "DESC",
            offset = 0,
            limit = 100
        )
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<MessageQueryRequest>(encoded)
        assertEquals(request, decoded)
        assertEquals("message.text LIKE :term COLLATE NOCASE", decoded.where.single().statement)
        assertEquals("%hi%", decoded.where.single().args["term"]!!.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `SendTextRequest round-trips chatGuid, tempGuid, message, method`() {
        val request = SendTextRequest(
            chatGuid = "iMessage;-;+15551234567",
            tempGuid = "temp-123",
            message = "hi",
            method = "private-api"
        )
        val decoded = json.decodeFromString<SendTextRequest>(json.encodeToString(request))
        assertEquals(request, decoded)
    }

    @Test
    fun `FcmDeviceRequest round-trips name and identifier`() {
        val request = FcmDeviceRequest(name = "pixel-8", identifier = "fcm-token-abc")
        val decoded = json.decodeFromString<FcmDeviceRequest>(json.encodeToString(request))
        assertEquals(request, decoded)
    }
}
