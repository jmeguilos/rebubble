package app.rebubble.data.remote.socket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class IoSocketClientUriTest {

    @Test
    fun `buildSocketUri appends percent-encoded guid query`() {
        val uri = buildSocketUri("http://192.168.1.20:1234", "p@ss word/&?")
        assertEquals("http", uri.scheme)
        assertEquals("192.168.1.20", uri.host)
        assertEquals(1234, uri.port)
        // OkHttp uses %20 for spaces (not '+'), which matches the server's decodeURI.
        assertTrue(uri.rawQuery!!.startsWith("guid="))
        assertTrue(uri.rawQuery!!.contains("%20") || !uri.rawQuery!!.contains(" "))
        val encodedValue = uri.rawQuery!!.removePrefix("guid=")
        val decoded = URLDecoder.decode(encodedValue, StandardCharsets.UTF_8)
        assertEquals("p@ss word/&?", decoded)
    }

    @Test
    fun `buildSocketUri strips trailing slash from base`() {
        val uri = buildSocketUri("https://bb.example.com/", "abc")
        assertEquals("https://bb.example.com/?guid=abc", uri.toString())
    }

    @Test
    fun `buildSocketQuery percent-encodes password for IO Options`() {
        val password = "s3cret/value?"
        val query = buildSocketQuery(password)
        assertTrue(query.startsWith("guid="))
        val decoded = URLDecoder.decode(query.removePrefix("guid="), StandardCharsets.UTF_8)
        assertEquals(password, decoded)
    }

    @Test
    fun `redactSocketUriForLog keeps host and strips password query`() {
        val password = "super-secret-password-xyz"
        val uri = buildSocketUri("http://192.168.1.20:1234", password)
        val redacted = redactSocketUriForLog(uri)
        assertTrue(redacted.contains("192.168.1.20"))
        assertTrue(redacted.contains("1234"))
        assertTrue(redacted.startsWith("http://"))
        assertFalse(redacted.contains(password))
        assertFalse(redacted.contains("guid="))
        assertFalse(redacted.contains("?"))
    }
}