package app.rebubble.data.remote.socket

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.rebubble.data.remote.api.ServerCredentialsProvider
import app.rebubble.data.repo.ServerConfig
import app.rebubble.data.repo.ServerConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Foreground-only socket lifecycle: [ProcessLifecycleOwner] ON_START → [SocketClient.connect]
 * (only when a server is configured), ON_STOP → [SocketClient.disconnect].
 *
 * Also watches [ServerConfigRepository.config]: when the process is already STARTED and config
 * transitions null → non-null (fresh onboarding), calls [SocketClient.connect] so realtime works
 * without requiring a background/foreground cycle. [IoSocketClient.connect] is idempotent
 * (`if (socket != null) return`), so overlapping ON_START + config paths are safe in production;
 * the collector only treats an *observed* null→present transition as a connect trigger so tests
 * (and a non-idempotent [SocketClient]) do not double-connect when config was already present.
 *
 * Registered from [app.rebubble.RebubbleApplication.onCreate].
 */
@Singleton
class SocketLifecycleObserver(
    private val socketClient: SocketClient,
    private val credentials: ServerCredentialsProvider,
    config: Flow<ServerConfig?>,
    scope: CoroutineScope,
) : DefaultLifecycleObserver {

    @Inject
    constructor(
        socketClient: SocketClient,
        serverConfigRepository: ServerConfigRepository,
        @Named("socket") scope: CoroutineScope,
    ) : this(
        socketClient,
        serverConfigRepository,
        serverConfigRepository.config,
        scope,
    )

    @Volatile
    private var isStarted = false

    init {
        scope.launch {
            var previous: ServerConfig? = null
            var hasEmitted = false
            config
                .retryWhen { cause, attempt ->
                    Log.w(
                        LOG_TAG,
                        "config collector failed (attempt ${attempt + 1}); retrying in " +
                            "${COLLECTOR_RETRY_DELAY_MS}ms",
                        cause,
                    )
                    delay(COLLECTOR_RETRY_DELAY_MS)
                    true
                }
                .catch { cause ->
                    // retryWhen above retries unconditionally (both retryWhen and catch are
                    // transparent to CancellationException, so scope cancellation still
                    // propagates normally), so reaching here should be unreachable in practice —
                    // defensive backstop only.
                    Log.e(LOG_TAG, "config collector terminated unexpectedly", cause)
                }
                .collect { cfg ->
                    val transitionedToPresent = hasEmitted && previous == null && cfg != null
                    if (isStarted && transitionedToPresent) {
                        socketClient.connect()
                    }
                    previous = cfg
                    hasEmitted = true
                }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        isStarted = true
        val url = credentials.url()
        val password = credentials.password()
        if (!url.isNullOrBlank() && !password.isNullOrBlank()) {
            socketClient.connect()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        isStarted = false
        socketClient.disconnect()
    }

    private companion object {
        const val LOG_TAG = "SocketLifecycleObserver"
        const val COLLECTOR_RETRY_DELAY_MS = 250L
    }
}
