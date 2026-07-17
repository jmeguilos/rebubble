package app.rebubble.data.sync

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues unique WorkManager work for [SyncWorker].
 *
 * - Periodic: every 15 minutes, unique name [UNIQUE_PERIODIC],
 *   [ExistingPeriodicWorkPolicy.KEEP].
 * - Expedited one-shot: unique name [UNIQUE_EXPEDITED],
 *   [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST], [ExistingWorkPolicy.KEEP]
 *   (KEEP avoids canceling an in-flight expedited sync; [Reconciler]'s mutex already makes
 *   overlap safe, but REPLACE would cancel-storm under reconnect flaps).
 *
 * No [androidx.work.NetworkType.CONNECTED] constraint: Android marks isolated LAN (no
 * captive-portal / no internet) as connected-without-internet, which would starve sync for
 * the core BlueBubbles LAN-only use case. Attempts without any network fail fast with
 * ConnectException and WorkManager retries with backoff.
 */
object SyncScheduling {
    const val UNIQUE_PERIODIC = "rebubble-sync"
    const val UNIQUE_EXPEDITED = "rebubble-sync-now"

    fun schedulePeriodic(workManager: WorkManager) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
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
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_EXPEDITED,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
