package app.rebubble.data.remote.socket

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Test double for [SocketClient].
 *
 * [emit] uses suspending [MutableSharedFlow.emit] so the call waits until the handler's
 * collector has accepted the value (avoids the SharedFlow tryEmit / no-subscriber race).
 */
class FakeSocketClient : SocketClient {
    private val _events = MutableSharedFlow<SocketEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnState.DISCONNECTED)
    override val connectionState: StateFlow<ConnState> = _connectionState.asStateFlow()

    var connectCalls: Int = 0
        private set
    var disconnectCalls: Int = 0
        private set

    override fun connect() {
        connectCalls++
    }

    override fun disconnect() {
        disconnectCalls++
    }

    fun emit(event: SocketEvent) = runBlocking {
        _events.emit(event)
    }

    fun setConnectionState(state: ConnState) {
        _connectionState.value = state
    }
}
