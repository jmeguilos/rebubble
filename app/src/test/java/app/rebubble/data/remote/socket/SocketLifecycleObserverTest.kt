package app.rebubble.data.remote.socket

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SocketLifecycleObserverTest {

    private class Owner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        init {
            registry.currentState = Lifecycle.State.INITIALIZED
        }
    }

    @Test
    fun `ON_START with config calls connect`() {
        val fake = FakeSocketClient()
        val credentials = FakeServerCredentialsProvider(
            urlValue = "http://192.168.1.20:1234",
            passwordValue = "secret",
        )
        val observer = SocketLifecycleObserver(fake, credentials)
        observer.onStart(Owner())
        assertEquals(1, fake.connectCalls)
        assertEquals(0, fake.disconnectCalls)
    }

    @Test
    fun `ON_START without config does not connect`() {
        val fake = FakeSocketClient()
        val credentials = FakeServerCredentialsProvider(urlValue = null, passwordValue = null)
        val observer = SocketLifecycleObserver(fake, credentials)
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
        val observer = SocketLifecycleObserver(fake, credentials)
        observer.onStop(Owner())
        assertEquals(1, fake.disconnectCalls)
    }
}
