package app.rebubble.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.dao.AttachmentDao
import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.HandleDao
import app.rebubble.data.local.dao.MessageDao
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.sync.LoggingNewMessageAlert
import app.rebubble.data.sync.MessageIngestor
import app.rebubble.data.sync.NewMessageAlert
import app.rebubble.data.sync.Reconciler
import app.rebubble.data.sync.SyncWatermarkStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * DI wiring for the sync layer. [MessageIngestor], [SyncWatermarkStore], and [Reconciler] are
 * provided here rather than via constructor `@Inject` so the sync package owns its own DI seam
 * (future T8+ sync collaborators land in this module). All three are process-wide [Singleton]s:
 * [MessageIngestor] is the single idempotent convergence point for every inbound message path,
 * [SyncWatermarkStore] guards one persisted high-water mark, and [Reconciler] owns the [Mutex]
 * that must serialize every caller's reconcile (FCM wake, app-open, socket reconnect, periodic
 * WorkManager) against that single watermark.
 *
 * [NewMessageAlert] defaults to [LoggingNewMessageAlert] (T15 replaces with the real notifier).
 * [app.rebubble.data.sync.SyncStatusTracker] uses constructor `@Inject` + `@Singleton`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindNewMessageAlert(impl: LoggingNewMessageAlert): NewMessageAlert

    companion object {
        @Provides
        @Singleton
        fun provideMessageIngestor(
            db: RebubbleDatabase,
            messageDao: MessageDao,
            chatDao: ChatDao,
            attachmentDao: AttachmentDao,
            handleDao: HandleDao,
        ): MessageIngestor = MessageIngestor(db, messageDao, chatDao, attachmentDao, handleDao)

        @Provides
        @Singleton
        fun provideSyncWatermarkStore(
            @Named("sync_state") dataStore: DataStore<Preferences>,
        ): SyncWatermarkStore = SyncWatermarkStore(dataStore)

        @Provides
        @Singleton
        fun provideReconciler(
            api: BlueBubblesApi,
            watermarkStore: SyncWatermarkStore,
            ingestor: MessageIngestor,
            chatDao: ChatDao,
            handleDao: HandleDao,
            messageDao: MessageDao,
        ): Reconciler = Reconciler(api, watermarkStore, ingestor, chatDao, handleDao, messageDao)
    }
}
