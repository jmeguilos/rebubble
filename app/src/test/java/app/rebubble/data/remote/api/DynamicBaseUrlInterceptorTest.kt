package app.rebubble.data.remote.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Verifies the dynamic base-URL rewrite: Retrofit is wired against the placeholder base URL, but
 * requests must actually land on the [ServerCredentialsProvider]-configured host/port (here, a
 * real [MockWebServer]).
 */
class DynamicBaseUrlInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `requests reach the configured server despite the placeholder base URL`() = runBlocking {
        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw"
        )
        val api = testBlueBubblesApi(credentials)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"OK","data":{"computer_id":"c","os_version":"14","server_version":"1"}}"""
            )
        )

        apiCall { api.serverInfo() }

        val recorded = server.takeRequest()
        assertNotEquals("placeholder.invalid", recorded.requestUrl?.host)
        assertEquals(server.hostName, recorded.requestUrl?.host)
        assertEquals("/api/v1/server/info", recorded.path?.substringBefore("?"))
    }

    @Test
    fun `a configured url with a reverse-proxy path prefix is prepended ahead of the retrofit path`() = runBlocking {
        // e.g. http://127.0.0.1:PORT/bluebubbles - a legitimate reverse-proxy setup, distinct from
        // a doubled `/api/v1` suffix (which ServerConfigRepository.sanitizeUrl strips before this
        // ever reaches the interceptor).
        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/bluebubbles").toString(),
            passwordValue = "pw"
        )
        val api = testBlueBubblesApi(credentials)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"OK","data":{"computer_id":"c","os_version":"14","server_version":"1"}}"""
            )
        )

        apiCall { api.serverInfo() }

        val recorded = server.takeRequest()
        assertEquals("/bluebubbles/api/v1/server/info", recorded.path?.substringBefore("?"))
    }
}
