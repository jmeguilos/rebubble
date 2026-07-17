package app.rebubble.data.remote.socket

import android.util.Log
import app.rebubble.data.remote.api.ServerCredentialsProvider
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [SocketClient] backed by `io.socket:socket.io-client` 2.x.
 *
 * Connects to [ServerCredentialsProvider.url] with handshake query `guid=<password>`
 * (see [buildSocketUri]). Transports: websocket + polling. Auto-reconnect is on so a brief
 * network blip recovers without an app-level reconnect loop — [SocketEventHandler] still runs
 * reconcile on every CONNECTED transition after the first to close any gap.
 *
 * ### Event buffer
 * Socket callbacks arrive on the engine.io worker thread. Events are offered via
 * [MutableSharedFlow.tryEmit] into a shared flow with
 * `extraBufferCapacity = [EVENT_BUFFER_CAPACITY]` (default overflow
 * [kotlinx.coroutines.channels.BufferOverflow.SUSPEND]). [tryEmit] never suspends: if the
 * buffer is full, the emission is dropped and logged — the socket thread is never blocked, and
 * drops are not silent. Under normal foreground consumption this path should not fire.
 */
@Singleton
class IoSocketClient @Inject constructor(
    private val credentials: ServerCredentialsProvider,
    private val parser: SocketPayloadParser,
) : SocketClient {

    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnState.DISCONNECTED)
    override val connectionState: StateFlow<ConnState> = _connectionState.asStateFlow()

    @Volatile
    private var socket: Socket? = null

    private val lock = Any()

    override fun connect() {
        synchronized(lock) {
            if (socket != null) return
            val url = credentials.url()
            val password = credentials.password()
            if (url.isNullOrBlank() || password.isNullOrBlank()) {
                Log.w(LOG_TAG, "connect() skipped: missing server url/password")
                return
            }
            // [buildSocketUri] is the tested assembly; IO.Options.query carries the same guid.
            val uri = buildSocketUri(url, password)
            _connectionState.value = ConnState.CONNECTING
            val options = IO.Options().apply {
                transports = arrayOf("websocket", "polling")
                reconnection = true
                forceNew = true
                query = buildSocketQuery(password)
            }
            val s = IO.socket(URI(url.trimEnd('/')), options)
            Log.d(LOG_TAG, "connecting to $uri")
            attachListeners(s)
            socket = s
            s.connect()
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            val s = socket ?: run {
                _connectionState.value = ConnState.DISCONNECTED
                return
            }
            detachListeners(s)
            s.disconnect()
            s.off()
            socket = null
            _connectionState.value = ConnState.DISCONNECTED
        }
    }

    private fun attachListeners(s: Socket) {
        s.on(Socket.EVENT_CONNECT, onConnect)
        s.on(Socket.EVENT_DISCONNECT, onDisconnect)
        s.on(Socket.EVENT_CONNECT_ERROR, onConnectError)
        for (name in WATCHED_EVENTS) {
            s.on(name, eventListener(name))
        }
    }

    private fun detachListeners(s: Socket) {
        s.off(Socket.EVENT_CONNECT, onConnect)
        s.off(Socket.EVENT_DISCONNECT, onDisconnect)
        s.off(Socket.EVENT_CONNECT_ERROR, onConnectError)
        for (name in WATCHED_EVENTS) {
            s.off(name)
        }
    }

    private val onConnect = Emitter.Listener {
        _connectionState.value = ConnState.CONNECTED
    }

    private val onDisconnect = Emitter.Listener {
        _connectionState.value = ConnState.DISCONNECTED
    }

    private val onConnectError = Emitter.Listener { args ->
        Log.w(LOG_TAG, "socket connect_error: ${args.firstOrNull()}")
        if (socket?.connected() != true && _connectionState.value == ConnState.CONNECTED) {
            _connectionState.value = ConnState.DISCONNECTED
        }
    }

    private fun eventListener(event: String) = Emitter.Listener { args ->
        val payload = args.firstOrNull()?.let { arg ->
            when (arg) {
                is JSONObject -> arg.toString()
                is String -> arg
                else -> arg.toString()
            }
        }
        val parsed = parser.parse(event, payload)
        if (!_events.tryEmit(parsed)) {
            Log.w(LOG_TAG, "event buffer full; dropping event=$event")
        }
    }

    companion object {
        private const val LOG_TAG = "IoSocketClient"

        /**
         * Extra buffer slots on [_events]. Sized for a short burst during collector startup or a
         * brief Main-thread stall without dropping; bounds memory if the collector stalls longer.
         */
        const val EVENT_BUFFER_CAPACITY = 64

        private val WATCHED_EVENTS = listOf(
            SocketPayloadParser.EVENT_NEW_MESSAGE,
            SocketPayloadParser.EVENT_UPDATED_MESSAGE,
            SocketPayloadParser.EVENT_TYPING_INDICATOR,
            SocketPayloadParser.EVENT_CHAT_READ_STATUS_CHANGED,
            SocketPayloadParser.EVENT_MESSAGE_SEND_ERROR,
        )
    }
}
