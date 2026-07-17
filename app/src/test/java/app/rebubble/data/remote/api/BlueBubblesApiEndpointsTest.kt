package app.rebubble.data.remote.api

import app.rebubble.data.remote.dto.requests.ChatQueryRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Additional endpoint coverage beyond the shared interceptor/error-mapping tests, to catch
 * path/param typos in [BlueBubblesApi]'s Retrofit annotations for a request-with-body endpoint
 * (POST) and a path+query-heavy endpoint (GET).
 */
class BlueBubblesApiEndpointsTest {

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
    fun `queryChats posts the request body and unwraps a chat list`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"OK","data":[{"guid":"chat-1","style":45,"chatIdentifier":"ci-1"}]}"""
            )
        )

        val chats = apiCall { api.queryChats(ChatQueryRequest(with = listOf("lastmessage"), limit = 10)) }

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/chat/query", recorded.path?.substringBefore("?"))
        assertTrue(recorded.body.readUtf8().contains("\"limit\":10"))
        assertEquals(1, chats.size)
        assertEquals("chat-1", chats[0].guid)
    }

    @Test
    fun `chatMessages builds the path and query parameters`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"OK","data":[{"guid":"m-1"}]}"""
            )
        )

        val messages = apiCall {
            api.chatMessages(g = "chat-guid-1", with = "attachment", sort = "DESC", before = 12345L, limit = 25)
        }

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/chat/chat-guid-1/message", recorded.path?.substringBefore("?"))
        assertEquals("attachment", recorded.requestUrl?.queryParameter("with"))
        assertEquals("DESC", recorded.requestUrl?.queryParameter("sort"))
        assertEquals("12345", recorded.requestUrl?.queryParameter("before"))
        assertEquals("25", recorded.requestUrl?.queryParameter("limit"))
        assertEquals("pw", recorded.requestUrl?.queryParameter("guid"))
        assertEquals(1, messages.size)
        assertEquals("m-1", messages[0].guid)
    }
}
