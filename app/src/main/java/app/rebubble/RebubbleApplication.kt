package app.rebubble

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import app.rebubble.data.remote.socket.SocketEventHandler
import app.rebubble.data.remote.socket.SocketLifecycleObserver
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
    }
}
