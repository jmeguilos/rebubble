package app.rebubble.di

import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.dao.AttachmentDao
import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.HandleDao
import app.rebubble.data.local.dao.MessageDao
import app.rebubble.data.sync.MessageIngestor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * DI wiring for the sync layer. [MessageIngestor] is the single idempotent convergence point for
 * every inbound message path (FCM, socket, reconcile, send-acks, backfill), so it is a
 * process-wide [Singleton]. It is provided here rather than via constructor `@Inject` so the sync
 * package owns its own DI seam (future T7/T8 sync collaborators land in this module).
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideMessageIngestor(
        db: RebubbleDatabase,
        messageDao: MessageDao,
        chatDao: ChatDao,
        attachmentDao: AttachmentDao,
        handleDao: HandleDao,
    ): MessageIngestor = MessageIngestor(db, messageDao, chatDao, attachmentDao, handleDao)
}
