package app.rebubble.di

import android.content.Context
import androidx.room.Room
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.dao.AttachmentDao
import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.ContactDao
import app.rebubble.data.local.dao.HandleDao
import app.rebubble.data.local.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideRebubbleDatabase(@ApplicationContext context: Context): RebubbleDatabase =
        Room.databaseBuilder(context, RebubbleDatabase::class.java, "rebubble.db").build()

    @Provides
    fun provideChatDao(database: RebubbleDatabase): ChatDao = database.chatDao()

    @Provides
    fun provideMessageDao(database: RebubbleDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideAttachmentDao(database: RebubbleDatabase): AttachmentDao = database.attachmentDao()

    @Provides
    fun provideHandleDao(database: RebubbleDatabase): HandleDao = database.handleDao()

    @Provides
    fun provideContactDao(database: RebubbleDatabase): ContactDao = database.contactDao()
}
