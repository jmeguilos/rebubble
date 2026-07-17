package app.rebubble.data.remote

import app.rebubble.data.remote.dto.Envelope
import app.rebubble.data.remote.dto.FcmClientDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decodes fixtures/fcm-client.json (see its "_source" field). FcmRouter's
 * getClientConfig() returns the raw google-services.json file contents
 * verbatim, so FcmClientDto is a JsonObject passthrough rather than a fixed
 * schema.
 */
class FcmClientDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes fcm client envelope as passthrough json`() {
        val raw = loadFixture("fcm-client.json")

        val envelope: Envelope<FcmClientDto> = json.decodeFromString(raw)

        val data = requireNotNull(envelope.data)
        assertEquals(
            "rebubble-test",
            data.jsonObject["project_info"]!!.jsonObject["project_id"]!!.jsonPrimitive.content
        )
        val firstClient = data.jsonObject["client"]!!.jsonArray[0].jsonObject
        assertEquals(
            "app.rebubble.android",
            firstClient["client_info"]!!.jsonObject["android_client_info"]!!.jsonObject["package_name"]!!.jsonPrimitive.content
        )
    }
}
