package app.rebubble.data.remote.socket

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Connection lifecycle for [SocketClient]. */
enum class ConnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

/**
 * Realtime socket seam. Production: [IoSocketClient]. Tests: [FakeSocketClient]-style fakes.
 *
 * [connect] / [disconnect] are idempotent best-effort; [connectionState] is the source of truth
 * for whether a session is live. [events] never completes for the life of the client instance.
 */
interface SocketClient {
    val events: Flow<SocketEvent>
    val connectionState: StateFlow<ConnState>
    fun connect()
    fun disconnect()
}
