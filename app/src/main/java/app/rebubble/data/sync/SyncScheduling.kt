package app.rebubble.data.sync

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues unique WorkManager work for [SyncWorker].
 *
 * - Periodic: every 15 minutes, [NetworkType.CONNECTED], unique name [UNIQUE_PERIODIC],
 *   [ExistingPeriodicWorkPolicy.KEEP].
 * - Expedited one-shot: unique name [UNIQUE_EXPEDITED],
 *   [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST], [ExistingWorkPolicy.KEEP]
 *   (KEEP avoids canceling an in-flight expedited sync; [Reconciler]'s mutex already makes
 *   overlap safe, but REPLACE would cancel-storm under reconnect flaps).
 */
object SyncScheduling {
    const val UNIQUE_PERIODIC = "rebubble-sync"
    const val UNIQUE_EXPEDITED = "rebubble-sync-now"

    private val networkConstraints: Constraints
        get() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    fun schedulePeriodic(workManager: WorkManager) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueExpedited(workManager: WorkManager) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_EXPEDITED,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
