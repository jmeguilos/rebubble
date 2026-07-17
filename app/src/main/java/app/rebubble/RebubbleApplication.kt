package app.rebubble

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import app.rebubble.data.remote.socket.SocketEventHandler
import app.rebubble.data.remote.socket.SocketLifecycleObserver
import app.rebubble.data.sync.SyncScheduling
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RebubbleApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var socketLifecycleObserver: SocketLifecycleObserver

    @Inject
    lateinit var socketEventHandler: SocketEventHandler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(socketLifecycleObserver)
        socketEventHandler.start()
        // Unconditional: cold-start config is Flow-based; priming url() on the main thread is
        // unsafe. SyncWorker no-ops with Result.success when config is null, so pre-onboarding
        // periodic ticks stay cheap and do not retry-storm.
        SyncScheduling.schedulePeriodic(WorkManager.getInstance(this))
    }
}
