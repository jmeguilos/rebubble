package app.rebubble.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
 *
 * v2 adds `chats.unreadCount` (local-only unread badge state); see [MIGRATION_1_2].
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
    version = 2,
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

/**
 * v1 → v2: adds the local-only `chats.unreadCount` column.
 *
 * Written by hand rather than as an `@AutoMigration` so the column's default is spelled out in the
 * `ALTER TABLE` itself: existing rows (chats synced before this build) become "all read", which is
 * the only sane starting point — the server has no unread state to backfill from, so pretending
 * every historical message is unread would badge the entire list on first launch. `NOT NULL
 * DEFAULT 0` matches the non-null Kotlin `Int = 0` field, so the schema hash validates.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chats ADD COLUMN unreadCount INTEGER NOT NULL DEFAULT 0")
    }
}
