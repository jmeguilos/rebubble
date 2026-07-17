package app.rebubble.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.rebubble.data.repo.ServerConfigRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * Periodic / expedited WorkManager entry that runs [Reconciler.reconcile] under
 * [SyncStatusTracker.track]. Does **not** post notifications itself — when
 * [SyncOutcome.newMessageGuids] is non-empty it calls [NewMessageAlert.onNewMessages]
 * (default: log-only; T15 replaces with the real notifier).
 *
 * Result semantics (reconcile itself never throws):
 * - [SyncOutcome.error] == null → [Result.success]
 * - error is [IOException] (or subclass) → [Result.retry]
 * - any other error → [Result.failure]
 *
 * When no server config is present yet (pre-onboarding), returns success immediately so the
 * unconditionally-scheduled periodic work (see [SyncScheduling]) does not retry-storm on
 * [IOException] from the auth interceptor. Uses the suspend [ServerConfigRepository.config]
 * flow (not [ServerConfigRepository.url]) to avoid a nested `runBlocking` prime on the
 * worker thread.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reconciler: Reconciler,
    private val tracker: SyncStatusTracker,
    private val newMessageAlert: NewMessageAlert,
    private val serverConfigRepository: ServerConfigRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (serverConfigRepository.config.first() == null) {
            return Result.success()
        }

        val outcome = tracker.track { reconciler.reconcile() }

        if (outcome.newMessageGuids.isNotEmpty()) {
            newMessageAlert.onNewMessages(outcome.newMessageGuids)
        }

        val error = outcome.error ?: return Result.success()
        return if (error is IOException) Result.retry() else Result.failure()
    }
}
