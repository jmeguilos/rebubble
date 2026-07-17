package app.rebubble.data.remote.socket

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.rebubble.data.remote.api.ServerCredentialsProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground-only socket lifecycle: [ProcessLifecycleOwner] ON_START → [SocketClient.connect]
 * (only when a server is configured), ON_STOP → [SocketClient.disconnect].
 *
 * Registered from [app.rebubble.RebubbleApplication.onCreate].
 */
@Singleton
class SocketLifecycleObserver @Inject constructor(
    private val socketClient: SocketClient,
    private val credentials: ServerCredentialsProvider,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        val url = credentials.url()
        val password = credentials.password()
        if (!url.isNullOrBlank() && !password.isNullOrBlank()) {
            socketClient.connect()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        socketClient.disconnect()
    }
}
