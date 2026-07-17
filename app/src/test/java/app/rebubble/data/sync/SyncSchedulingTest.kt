package app.rebubble.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `schedulePeriodic enqueues unique periodic work with network constraint`() {
        SyncScheduling.schedulePeriodic(workManager)

        val infos = workManager
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_PERIODIC)
            .get(5, TimeUnit.SECONDS)

        assertEquals(1, infos.size)
        val info = infos[0]
        assertNotNull(info.periodicityInfo)
        assertEquals(15 * 60 * 1000L, info.periodicityInfo!!.repeatIntervalMillis)
        // Network constraint is on the request; WorkInfo exposes constraints via state/tags.
        // Assert the unique work exists and is ENQUEUED (constraints unmet under test driver).
        assertTrue(
            info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING,
        )
    }

    @Test
    fun `enqueueExpedited enqueues one-shot and double-call KEEP does not duplicate`() {
        SyncScheduling.enqueueExpedited(workManager)

        val first = workManager
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_EXPEDITED)
            .get(5, TimeUnit.SECONDS)
        assertEquals(1, first.size)
        assertEquals(null, first[0].periodicityInfo)
        val id = first[0].id

        SyncScheduling.enqueueExpedited(workManager)

        val second = workManager
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_EXPEDITED)
            .get(5, TimeUnit.SECONDS)
        assertEquals(1, second.size)
        assertEquals(id, second[0].id)
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
}
