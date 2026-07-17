package app.rebubble.data.remote

import app.rebubble.data.remote.dto.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decodes fixtures/error-envelope.json (see its "_source" field). Verifies the
 * typed error{type,message} block and that `data` is absent/null.
 */
class ErrorEnvelopeDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes error envelope with typed error fields`() {
        val raw = loadFixture("error-envelope.json")

        val envelope: Envelope<JsonElement> = json.decodeFromString(raw)

        assertEquals(400, envelope.status)
        assertEquals("You've made a bad request! Please check your request params & body", envelope.message)
        assertNull(envelope.data)

        val error = requireNotNull(envelope.error)
        assertEquals("Validation Error", error.type)
        assertEquals("No chat GUID provided", error.message)
    }
}
