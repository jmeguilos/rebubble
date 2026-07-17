package app.rebubble.notifications

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import app.rebubble.data.remote.loadFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device registration path with a fake [FirebaseRuntime] (no Play Services / real FCM).
 * Asserts `POST /fcm/device` body shape `{name, identifier}`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class FirebaseBootstrapperDeviceRegistrationTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `setup POSTs fcm device with name and identifier token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(loadFixture("fcm-client.json")))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":null}""",
            ),
        )

        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw",
        )
        val api = testBlueBubblesApi(credentials)
        val runtime = object : FirebaseRuntime {
            override fun ensureInitialized(context: Context, params: FirebaseOptionsParams) {
                // no-op — isolate FirebaseApp.initializeApp (device-verified only)
            }

            override suspend fun fetchToken(): String = "test-fcm-token-abc"
        }
        val bootstrapper = FirebaseBootstrapper(
            context = context,
            api = api,
            runtime = runtime,
        )

        val result = bootstrapper.setup()

        assertTrue(result is FcmSetupResult.Success)
        assertEquals("test-fcm-token-abc", (result as FcmSetupResult.Success).token)

        // First request: GET fcm/client
        val getClient = server.takeRequest()
        assertTrue(getClient.path!!.contains("fcm/client"))

        // Second: POST fcm/device
        val postDevice = server.takeRequest()
        assertTrue(postDevice.path!!.contains("fcm/device"))
        assertEquals("POST", postDevice.method)
        val body = json.parseToJsonElement(postDevice.body.readUtf8()).jsonObject
        assertEquals(Build.MODEL, body["name"]!!.jsonPrimitive.content)
        assertEquals("test-fcm-token-abc", body["identifier"]!!.jsonPrimitive.content)
    }

    @Test
    fun `registerDevice POSTs name and identifier`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":null}""",
            ),
        )

        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw",
        )
        val bootstrapper = FirebaseBootstrapper(
            context = context,
            api = testBlueBubblesApi(credentials),
            runtime = object : FirebaseRuntime {
                override fun ensureInitialized(context: Context, params: FirebaseOptionsParams) = Unit
                override suspend fun fetchToken(): String = error("unused")
            },
        )

        val result = bootstrapper.registerDevice("token-xyz")
        assertTrue(result is FcmSetupResult.Success)

        val req = server.takeRequest()
        assertTrue(req.path!!.contains("fcm/device"))
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(Build.MODEL, body["name"]!!.jsonPrimitive.content)
        assertEquals("token-xyz", body["identifier"]!!.jsonPrimitive.content)
    }
}
