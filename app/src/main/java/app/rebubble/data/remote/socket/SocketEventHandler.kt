package app.rebubble.data.remote.socket

import android.util.Log
import app.rebubble.data.sync.IngestSource
import app.rebubble.data.sync.MessageIngestor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Consumes [SocketClient.events] / [SocketClient.connectionState]:
 * - [SocketEvent.NewMessage] / [SocketEvent.UpdatedMessage] → [MessageIngestor.ingest] with
 *   [IngestSource.SOCKET]
 * - CONNECTED transitions *after the first* → [onReconnect] (production: Reconciler.reconcile)
 * - typing / chat-read / send-error / unknown → log only for M1
 *
 * Call [start] once from [app.rebubble.RebubbleApplication.onCreate].
 */
@Singleton
class SocketEventHandler @Inject constructor(
    private val socketClient: SocketClient,
    private val ingestor: MessageIngestor,
    private val onReconnect: SocketReconnectAction,
    @param:Named("socket") private val scope: CoroutineScope,
) {
    @Volatile
    private var started = false

    /** True once we have observed CONNECTED at least once this process. */
    @Volatile
    private var hasConnectedOnce = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            socketClient.events.collect { event -> handleEvent(event) }
        }
        scope.launch {
            socketClient.connectionState.collect { state -> handleConnectionState(state) }
        }
    }

    private suspend fun handleEvent(event: SocketEvent) {
        when (event) {
            is SocketEvent.NewMessage -> {
                ingestor.ingest(listOf(event.dto), IngestSource.SOCKET)
            }
            is SocketEvent.UpdatedMessage -> {
                ingestor.ingest(listOf(event.dto), IngestSource.SOCKET)
            }
            is SocketEvent.TypingIndicator -> {
                Log.d(
                    LOG_TAG,
                    "typing-indicator chatGuid=${event.chatGuid} display=${event.display} (M1 no-op)",
                )
            }
            is SocketEvent.ChatReadStatusChanged -> {
                Log.d(
                    LOG_TAG,
                    "chat-read-status-changed chatGuid=${event.chatGuid} read=${event.read} (M1 no-op)",
                )
            }
            is SocketEvent.MessageSendError -> {
                Log.w(LOG_TAG, "message-send-error (M1 no-op): ${event.raw}")
            }
            is SocketEvent.Unknown -> {
                Log.d(LOG_TAG, "unknown/unparsed socket event=${event.event}")
            }
        }
    }

    private suspend fun handleConnectionState(state: ConnState) {
        if (state != ConnState.CONNECTED) return
        if (hasConnectedOnce) {
            Log.i(LOG_TAG, "socket reconnected; triggering reconcile")
            onReconnect.onReconnect()
        } else {
            hasConnectedOnce = true
            Log.i(LOG_TAG, "socket connected (first); skipping reconcile")
        }
    }

    private companion object {
        const val LOG_TAG = "SocketEventHandler"
    }
}

/**
 * Minimal seam so [SocketEventHandler] can trigger reconcile on reconnect without depending on
 * the full [app.rebubble.data.sync.Reconciler] construction graph in unit tests. Production binds
 * this to `reconciler::reconcile` (fire-and-forget the [app.rebubble.data.sync.SyncOutcome]).
 *
 * Chosen over `open class Reconciler` / extracting a wider interface: zero changes to the sync
 * layer, one-liner DI binding.
 */
fun interface SocketReconnectAction {
    suspend fun onReconnect()
}
