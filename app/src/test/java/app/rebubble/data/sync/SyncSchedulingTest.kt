package app.rebubble.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SyncSchedulingTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `schedulePeriodic enqueues unique periodic work without network constraint`() {
        SyncScheduling.schedulePeriodic(workManager)

        val infos = workManager
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_PERIODIC)
            .get(5, TimeUnit.SECONDS)

        assertEquals(1, infos.size)
        val info = infos[0]
        assertNotNull(info.periodicityInfo)
        assertEquals(15 * 60 * 1000L, info.periodicityInfo!!.repeatIntervalMillis)
        // No NetworkType.CONNECTED: LAN-only BlueBubbles (captive-portal "no internet") must still sync.
        assertEquals(NetworkType.NOT_REQUIRED, info.constraints.requiredNetworkType)
        assertTrue(
            info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING,
        )
    }

    @Test
    fun `enqueueExpedited enqueues one-shot without network constraint`() {
        SyncScheduling.enqueueExpedited(workManager)

        val infos = workManager
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_EXPEDITED)
            .get(5, TimeUnit.SECONDS)
        assertEquals(1, infos.size)
        assertNull(infos[0].periodicityInfo)
        assertEquals(NetworkType.NOT_REQUIRED, infos[0].constraints.requiredNetworkType)
    }

    @Test
    fun `schedulePeriodic KEEP does not replace existing unique periodic work`() {
        SyncScheduling.schedulePeriodic(workManager)
        val firstId = workManager
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_PERIODIC)
            .get(5, TimeUnit.SECONDS)[0].id

        SyncScheduling.schedulePeriodic(workManager)
        val secondId = workManager
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_PERIODIC)
            .get(5, TimeUnit.SECONDS)[0].id

        assertEquals(firstId, secondId)
    }

    @Test
    fun `enqueueExpedited KEEP is a no-op while prior expedited work is unfinished`() {
        // Gate on unmet storage constraint so the first request stays pending; KEEP while
        // unfinished must not replace. (Production has no network constraint; this only
        // exercises ExistingWorkPolicy.KEEP. Expedited work may only use network/storage
        // constraints.)
        val gated = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(
            SyncScheduling.UNIQUE_EXPEDITED,
            androidx.work.ExistingWorkPolicy.KEEP,
            gated,
        )
        val firstId = workManager
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_EXPEDITED)
            .get(5, TimeUnit.SECONDS)[0].id

        SyncScheduling.enqueueExpedited(workManager)

        val second = workManager
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_EXPEDITED)
            .get(5, TimeUnit.SECONDS)
        assertEquals(1, second.size)
        assertEquals(firstId, second[0].id)
    }
}
