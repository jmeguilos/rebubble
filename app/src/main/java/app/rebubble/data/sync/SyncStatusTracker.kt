package app.rebubble.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide sync UI/state signal: Idle → Syncing while a tracked block runs → Idle on a clean
 * [SyncOutcome], or [SyncStatus.Error] when [SyncOutcome.error] is non-null.
 *
 * Driven by [SyncWorker] and by reconnect/overflow paths (see [app.rebubble.di.SocketModule]'s
 * [app.rebubble.data.remote.socket.SocketReconnectAction] binding) rather than by modifying
 * [Reconciler] itself.
 *
 * Overlapping [track] calls share an active-count: status stays [SyncStatus.Syncing] until the
 * count drains to 0. Drain rule: store `lastError` on error outcomes / non-cancel throws; a later
 * successful [track] clears it; when count reaches 0, status is `Error(lastError)` if set, else Idle.
 */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Error(val message: String, val at: Long) : SyncStatus
}

@Singleton
class SyncStatusTracker @Inject constructor() {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val activeCount = AtomicInteger(0)
    @Volatile
    private var lastError: String? = null

    /**
     * Increments the active sync count, sets [SyncStatus.Syncing], runs [block], then on exit
     * decrements. While count > 0 status stays Syncing. When count reaches 0: Idle if no
     * unconsumed `lastError`, else [SyncStatus.Error]. A successful outcome clears `lastError`.
     *
     * Non-[CancellationException] throws record Error(message) via `lastError` and rethrow.
     * [CancellationException] restores drain state (no new error) and rethrows.
     */
    suspend fun track(block: suspend () -> SyncOutcome): SyncOutcome {
        activeCount.incrementAndGet()
        _status.value = SyncStatus.Syncing
        try {
            val outcome = try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                lastError = failure.message ?: failure.toString()
                throw failure
            }
            val error = outcome.error
            if (error != null) {
                lastError = error.message ?: error.toString()
            } else {
                lastError = null
            }
            return outcome
        } finally {
            if (activeCount.decrementAndGet() == 0) {
                val errorMessage = lastError
                _status.value = if (errorMessage != null) {
                    SyncStatus.Error(
                        message = errorMessage,
                        at = System.currentTimeMillis(),
                    )
                } else {
                    SyncStatus.Idle
                }
            } else {
                _status.value = SyncStatus.Syncing
            }
        }
    }
}
