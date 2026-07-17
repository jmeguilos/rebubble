package app.rebubble.data.remote.api

import app.rebubble.data.remote.dto.ServerInfoDto
import app.rebubble.data.remote.dto.requests.FcmDeviceRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Verifies [apiCall]'s error mapping: envelope unwrap on success, typed [ApiException] for
 * non-2xx responses with a parseable error envelope, typed [AuthError] for 401, IOException
 * passthrough for network failures, typed [ApiException] for a non-2xx response whose body isn't
 * parseable JSON at all, and typed [ApiException] (vs. [apiCallNullable] succeeding) when a 2xx
 * envelope's `data` is null.
 */
class ApiResultTest {

    private lateinit var server: MockWebServer
    private lateinit var credentials: FakeServerCredentialsProvider
    private lateinit var api: BlueBubblesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw"
        )
        api = testBlueBubblesApi(credentials)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `unwraps a successful envelope to its data`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"OK","data":{"computer_id":"c1","os_version":"14.0","server_version":"1.2.3"}}"""
            )
        )

        val info = apiCall { api.serverInfo() }

        assertEquals("c1", info.computerId)
        assertEquals("14.0", info.osVersion)
        assertEquals("1.2.3", info.serverVersion)
    }

    @Test
    fun `non-2xx with a parseable error envelope becomes a typed ApiException`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"status":400,"message":"Bad request","error":{"type":"Validation Error","message":"No chat GUID provided"}}"""
            )
        )

        val exception = assertThrows(ApiException::class.java) {
            runBlocking { apiCall<ServerInfoDto> { api.serverInfo() } }
        }

        assertEquals(400, exception.status)
        assertEquals("Validation Error", exception.errorType)
        assertEquals("No chat GUID provided", exception.errorMessage)
    }

    @Test
    fun `non-2xx with an unparseable body still becomes a typed ApiException, not a SerializationException`() {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("<html>gateway</html>")
        )

        val exception = assertThrows(ApiException::class.java) {
            runBlocking { apiCall<ServerInfoDto> { api.serverInfo() } }
        }

        assertEquals(500, exception.status)
        assertNull(exception.errorType)
    }

    @Test
    fun `2xx envelope with null data throws a typed ApiException from apiCall`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success"}"""
            )
        )

        val exception = assertThrows(ApiException::class.java) {
            runBlocking { apiCall<JsonObject> { api.fcmClient() } }
        }

        assertEquals(200, exception.status)
        assertEquals("Empty Data", exception.errorType)
    }

    @Test
    fun `2xx envelope with null data succeeds via apiCallNullable`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success"}"""
            )
        )

        val result = apiCallNullable { api.addFcmDevice(FcmDeviceRequest(name = "n", identifier = "i")) }

        assertNull(result)
    }

    @Test
    fun `401 becomes AuthError regardless of body shape`() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"status":401,"message":"Unauthorized"}""")
        )

        assertThrows(AuthError::class.java) {
            runBlocking { apiCall<ServerInfoDto> { api.serverInfo() } }
        }
    }

    @Test
    fun `network failure passes through as IOException`() {
        server.shutdown()

        assertThrows(IOException::class.java) {
            runBlocking { apiCall<ServerInfoDto> { api.serverInfo() } }
        }
    }
}
