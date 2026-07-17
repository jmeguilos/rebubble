package app.rebubble.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.rebubble.data.local.dao.AttachmentDao
import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.ContactDao
import app.rebubble.data.local.dao.HandleDao
import app.rebubble.data.local.dao.MessageDao
import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.ChatHandleCrossRef
import app.rebubble.data.local.entity.ContactEntity
import app.rebubble.data.local.entity.HandleEntity
import app.rebubble.data.local.entity.MessageEntity

/**
 * v1 schema deliberately includes M2 fields (reactions, threads, edits) so M2 needs no migration.
 * Schema is exported to `app/schemas/` (see the `room.schemaLocation` KSP arg in
 * `app/build.gradle.kts`) and the generated JSON is committed.
 */
@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        HandleEntity::class,
        ChatHandleCrossRef::class,
        ContactEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RebubbleDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun handleDao(): HandleDao
    abstract fun contactDao(): ContactDao
}
