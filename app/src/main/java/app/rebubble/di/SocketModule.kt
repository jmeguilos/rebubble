package app.rebubble.di

import app.rebubble.data.remote.socket.IoSocketClient
import app.rebubble.data.remote.socket.SocketClient
import app.rebubble.data.remote.socket.SocketReconnectAction
import app.rebubble.data.sync.Reconciler
import app.rebubble.data.sync.SyncStatusTracker
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Named
import javax.inject.Singleton

/**
 * Socket.io client + reconnect-action wiring. The process-scoped [CoroutineScope] named
 * `"socket"` drives [app.rebubble.data.remote.socket.SocketEventHandler] collectors (never
 * cancelled for the process lifetime).
 *
 * [SocketReconnectAction] runs reconcile under [SyncStatusTracker.track] so reconnect and
 * overflow paths drive the same Idle→Syncing→Idle/Error status as [app.rebubble.data.sync.SyncWorker]
 * without touching [app.rebubble.data.remote.socket.SocketEventHandler].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SocketModule {

    @Binds
    @Singleton
    abstract fun bindSocketClient(impl: IoSocketClient): SocketClient

    companion object {
        @Provides
        @Singleton
        @Named("socket")
        fun provideSocketScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        @Singleton
        fun provideSocketReconnectAction(
            reconciler: Reconciler,
            tracker: SyncStatusTracker,
        ): SocketReconnectAction =
            SocketReconnectAction { tracker.track { reconciler.reconcile() } }
    }
}
