package app.rebubble.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide sync UI/state signal: Idle → Syncing while a tracked block runs → Idle on a clean
 * [SyncOutcome], or [SyncStatus.Error] when [SyncOutcome.error] is non-null.
 *
 * Driven by [SyncWorker] and by reconnect/overflow paths (see [app.rebubble.di.SocketModule]'s
 * [app.rebubble.data.remote.socket.SocketReconnectAction] binding) rather than by modifying
 * [Reconciler] itself.
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

    /**
     * Sets [SyncStatus.Syncing], runs [block], then Idle (clean outcome) or
     * [SyncStatus.Error] (outcome with [SyncOutcome.error]). Returns the outcome unchanged.
     *
     * [CancellationException] restores Idle and rethrows so cooperative cancel stays intact.
     */
    suspend fun track(block: suspend () -> SyncOutcome): SyncOutcome {
        _status.value = SyncStatus.Syncing
        val outcome = try {
            block()
        } catch (cancellation: CancellationException) {
            _status.value = SyncStatus.Idle
            throw cancellation
        }
        _status.value = when (val error = outcome.error) {
            null -> SyncStatus.Idle
            else -> SyncStatus.Error(
                message = error.message ?: error.toString(),
                at = System.currentTimeMillis(),
            )
        }
        return outcome
    }
}
