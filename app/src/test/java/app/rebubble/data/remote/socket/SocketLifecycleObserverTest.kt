package app.rebubble.data.remote.socket

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.repo.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SocketLifecycleObserverTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class Owner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        init {
            registry.currentState = Lifecycle.State.INITIALIZED
        }
    }

    private fun observer(
        fake: FakeSocketClient,
        credentials: FakeServerCredentialsProvider,
        config: MutableStateFlow<ServerConfig?>,
    ) = SocketLifecycleObserver(
        socketClient = fake,
        credentials = credentials,
        config = config,
        scope = scope,
    )

    @Test
    fun `ON_START with config calls connect`() {
        val fake = FakeSocketClient()
        val credentials = FakeServerCredentialsProvider(
            urlValue = "http://192.168.1.20:1234",
            passwordValue = "secret",
        )
        val config = MutableStateFlow<ServerConfig?>(
            ServerConfig("http://192.168.1.20:1234", "secret"),
        )
        val observer = observer(fake, credentials, config)
        observer.onStart(Owner())
        assertEquals(1, fake.connectCalls)
        assertEquals(0, fake.disconnectCalls)
    }

    @Test
    fun `ON_START without config does not connect`() {
        val fake = FakeSocketClient()
        val credentials = FakeServerCredentialsProvider(urlValue = null, passwordValue = null)
        val observer = observer(fake, credentials, MutableStateFlow<ServerConfig?>(null))
        observer.onStart(Owner())
        assertEquals(0, fake.connectCalls)
    }

    @Test
    fun `ON_STOP calls disconnect`() {
        val fake = FakeSocketClient()
        val credentials = FakeServerCredentialsProvider(
            urlValue = "http://192.168.1.20:1234",
            passwordValue = "secret",
        )
        val observer = observer(
            fake,
            credentials,
            MutableStateFlow<ServerConfig?>(ServerConfig("http://192.168.1.20:1234", "secret")),
        )
        observer.onStop(Owner())
        assertEquals(1, fake.disconnectCalls)
    }

    @Test
    fun `STARTED with null config then config becomes present calls connect once`() {
        val fake = FakeSocketClient()
        val credentials = FakeServerCredentialsProvider(urlValue = null, passwordValue = null)
        val config = MutableStateFlow<ServerConfig?>(null)
        val observer = observer(fake, credentials, config)

        observer.onStart(Owner())
        assertEquals(0, fake.connectCalls)

        val saved = ServerConfig("http://192.168.1.20:1234", "secret")
        credentials.urlValue = saved.url
        credentials.passwordValue = saved.password
        config.value = saved

        assertEquals(1, fake.connectCalls)
    }

    @Test
    fun `config already present at ON_START connects once without double-connect from collector`() {
        val fake = FakeSocketClient()
        val saved = ServerConfig("http://192.168.1.20:1234", "secret")
        val credentials = FakeServerCredentialsProvider(
            urlValue = saved.url,
            passwordValue = saved.password,
        )
        val config = MutableStateFlow<ServerConfig?>(saved)
        val observer = observer(fake, credentials, config)

        observer.onStart(Owner())
        assertEquals(1, fake.connectCalls)

        // Collector must not treat the already-present first emission as a null→present transition.
        config.value = saved
        assertEquals(1, fake.connectCalls)
    }

    @Test
    fun `config transition while STOPPED does not connect until next ON_START`() {
        val fake = FakeSocketClient()
        val credentials = FakeServerCredentialsProvider(urlValue = null, passwordValue = null)
        val config = MutableStateFlow<ServerConfig?>(null)
        val observer = observer(fake, credentials, config)

        val saved = ServerConfig("http://192.168.1.20:1234", "secret")
        credentials.urlValue = saved.url
        credentials.passwordValue = saved.password
        config.value = saved
        assertEquals(0, fake.connectCalls)

        observer.onStart(Owner())
        assertEquals(1, fake.connectCalls)
    }
}
