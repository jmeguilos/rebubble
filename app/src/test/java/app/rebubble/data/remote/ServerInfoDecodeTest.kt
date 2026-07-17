package app.rebubble.data.remote

import app.rebubble.data.remote.dto.Envelope
import app.rebubble.data.remote.dto.ServerInfoDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes fixtures/server-info.json (see its "_source" field). Field names on
 * the wire are snake_case (per GeneralInterface.getServerMetadata()); the DTO
 * maps them to camelCase Kotlin properties via @SerialName.
 */
class ServerInfoDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes server info envelope`() {
        val raw = loadFixture("server-info.json")

        val envelope: Envelope<ServerInfoDto> = json.decodeFromString(raw)

        val info = requireNotNull(envelope.data)
        assertEquals("F0E1D2C3-B4A5-4678-9012-ABCDEF012345", info.computerId)
        assertEquals("14.5", info.osVersion)
        assertEquals("1.9.7", info.serverVersion)
        assertTrue(info.privateApi)
        assertTrue(info.helperConnected)
        assertEquals("dynamic-dns", info.proxyService)
        assertEquals("user@icloud.com", info.detectedIcloud)
        assertEquals(listOf("192.168.1.42"), info.localIpv4s)
        assertEquals(listOf("fe80::1"), info.localIpv6s)
    }
}
