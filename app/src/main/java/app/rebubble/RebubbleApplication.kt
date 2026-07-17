package app.rebubble

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import app.rebubble.data.remote.socket.SocketEventHandler
import app.rebubble.data.remote.socket.SocketLifecycleObserver
import app.rebubble.data.repo.ServerConfigRepository
import app.rebubble.data.sync.SyncScheduling
import app.rebubble.notifications.FcmSetupResult
import app.rebubble.notifications.FirebaseBootstrapper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltAndroidApp
class RebubbleApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var socketLifecycleObserver: SocketLifecycleObserver

    @Inject
    lateinit var socketEventHandler: SocketEventHandler

    @Inject
    lateinit var serverConfigRepository: ServerConfigRepository

    @Inject
    lateinit var firebaseBootstrapper: FirebaseBootstrapper

    @Inject
    @Named("fcm")
    lateinit var fcmScope: CoroutineScope

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

        // Best-effort FCM re-bootstrap whenever a server config already exists (post-onboarding
        // cold start / process death). Failures are swallowed — periodic sync is the fallback.
        // Chosen over a persisted fcm_registered flag: setup() is idempotent (default-app early
        // out + device re-POST), and re-registering the current token after upgrade/clear-token
        // is the correct server-side state. T16 still owns the first interactive setup call.
        fcmScope.launch {
            try {
                if (serverConfigRepository.config.first() == null) return@launch
                when (val result = firebaseBootstrapper.setup()) {
                    is FcmSetupResult.Success ->
                        Log.d(LOG_TAG, "FCM bootstrap ok on app start")
                    is FcmSetupResult.Failure ->
                        Log.w(LOG_TAG, "FCM bootstrap failed at ${result.step}", result.cause)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "FCM bootstrap launch failed", e)
            }
        }
    }

    private companion object {
        const val LOG_TAG = "RebubbleApplication"
    }
}
