package app.rebubble.data.remote.socket

import android.util.Log
import app.rebubble.data.logging.RingBufferLogger
import app.rebubble.data.sync.IngestSource
import app.rebubble.data.sync.MessageIngestor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Consumes [SocketClient.events] / [SocketClient.connectionState]:
 * - [SocketEvent.NewMessage] / [SocketEvent.UpdatedMessage] → [MessageIngestor.ingest] with
 *   [IngestSource.SOCKET]
 * - CONNECTED transitions *after the first* → [onReconnect] (production: Reconciler.reconcile),
 *   launched fire-and-forget on [scope] so a slow reconcile cannot miss intermediate
 *   DISCONNECTED→CONNECTED flaps (StateFlow conflation). Reconciler serializes via Mutex.
 * - typing / chat-read / send-error / unknown → log only for M1
 *
 * The events collector uses [retryWhen] (250ms backoff, unlimited) plus a per-event try/catch so
 * one poison ingest cannot permanently kill live delivery for the process lifetime. Per-event
 * catch is the primary guard ([SharedFlow] has no replay, so resubscription alone would not
 * re-deliver the failed event); [retryWhen] is the backstop for flow-level failures.
 *
 * Call [start] once from [app.rebubble.RebubbleApplication.onCreate].
 */
@Singleton
class SocketEventHandler @Inject constructor(
    private val socketClient: SocketClient,
    private val ingestor: MessageIngestor,
    private val onReconnect: SocketReconnectAction,
    private val logger: RingBufferLogger,
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
            socketClient.events
                .retryWhen { cause, attempt ->
                    Log.w(
                        LOG_TAG,
                        "events collector failed (attempt ${attempt + 1}); retrying in " +
                            "${COLLECTOR_RETRY_DELAY_MS}ms",
                        cause,
                    )
                    delay(COLLECTOR_RETRY_DELAY_MS)
                    true
                }
                .catch { cause ->
                    // retryWhen above retries unconditionally (both retryWhen and catch are
                    // transparent to CancellationException), so reaching here should be
                    // unreachable in practice — defensive backstop only.
                    Log.e(LOG_TAG, "events collector terminated unexpectedly", cause)
                }
                .collect { event -> handleEvent(event) }
        }
        scope.launch {
            socketClient.connectionState.collect { state -> handleConnectionState(state) }
        }
    }

    private suspend fun handleEvent(event: SocketEvent) {
        try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "failed to handle socket event=$event", e)
            logger.log(LOG_TAG, "poison event: ${e.message}")
        }
    }

    private fun handleConnectionState(state: ConnState) {
        if (state != ConnState.CONNECTED) return
        if (hasConnectedOnce) {
            Log.i(LOG_TAG, "socket reconnected; triggering reconcile")
            // Fire-and-forget so this collector stays responsive to DISCONNECTED→CONNECTED flaps
            // while a prior reconcile is still running (StateFlow would otherwise conflate away
            // the intermediate DISCONNECTED and skip the second CONNECTED).
            scope.launch {
                try {
                    onReconnect.onReconnect()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "reconnect reconcile failed", e)
                }
            }
        } else {
            hasConnectedOnce = true
            Log.i(LOG_TAG, "socket connected (first); skipping reconcile")
        }
    }

    private companion object {
        const val LOG_TAG = "SocketEventHandler"
        const val COLLECTOR_RETRY_DELAY_MS = 250L
    }
}

/**
 * Minimal seam so [SocketEventHandler] can trigger reconcile on reconnect without depending on
 * the full [app.rebubble.data.sync.Reconciler] construction graph in unit tests. Production binds
 * this to `reconciler::reconcile` (fire-and-forget the [app.rebubble.data.sync.SyncOutcome]).
 *
 * Also used by [IoSocketClient] when the event buffer drops a frame (overflow → reconcile).
 *
 * Chosen over `open class Reconciler` / extracting a wider interface: zero changes to the sync
 * layer, one-liner DI binding.
 */
fun interface SocketReconnectAction {
    suspend fun onReconnect()
}
