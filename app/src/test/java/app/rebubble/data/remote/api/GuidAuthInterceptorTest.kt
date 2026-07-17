package app.rebubble.data.remote.api

import app.rebubble.data.remote.dto.requests.ChatQueryRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Verifies [GuidAuthInterceptor] appends `?guid=<password>` to every outgoing request. */
class GuidAuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var credentials: FakeServerCredentialsProvider
    private lateinit var api: BlueBubblesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "s3cret"
        )
        api = testBlueBubblesApi(credentials)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `every request carries the guid query parameter`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"OK","data":{"computer_id":"c","os_version":"14","server_version":"1"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"OK","data":[{"guid":"chat-1","style":45,"chatIdentifier":"ci-1"}]}"""
            )
        )

        apiCall { api.serverInfo() }
        apiCall { api.queryChats(ChatQueryRequest()) }

        val first = server.takeRequest()
        val second = server.takeRequest()

        assertEquals("s3cret", first.requestUrl?.queryParameter("guid"))
        assertEquals("s3cret", second.requestUrl?.queryParameter("guid"))
    }
}
