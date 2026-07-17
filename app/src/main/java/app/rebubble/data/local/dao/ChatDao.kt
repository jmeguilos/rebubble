package app.rebubble.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.rebubble.data.local.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    /**
     * Newest-first by [ChatEntity.lastMessageDate]. SQLite's default DESC ordering already sorts
     * NULLs last (NULLs are treated as the smallest value, so they land at the end of a
     * descending sort), which is exactly the desired "no messages yet" placement.
     */
    @Query("SELECT * FROM chats ORDER BY lastMessageDate DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chats: List<ChatEntity>)

    /**
     * Insert-if-absent for the ingestor: [upsert] is REPLACE and would clobber a known chat's
     * denormalized `lastMessageDate`/`lastMessagePreview`, so a message that merely *references* a
     * chat must use this IGNORE variant to seed a minimal row only when the chat doesn't exist yet.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(chats: List<ChatEntity>)

    @Query("SELECT * FROM chats WHERE guid = :guid")
    suspend fun getByGuid(guid: String): ChatEntity?

    /**
     * Targeted metadata-only update for the reconciler's chat pass (T7): refreshes
     * `displayName`/`chatIdentifier`/`style`/`isArchived` for a chat the server already knows
     * about (renames, group-membership style changes, archive toggles) without touching the
     * denormalized `lastMessageDate`/`lastMessagePreview` — those are only ever advanced by
     * [MessageIngestor.ingest]'s [updatePreview] call, never by the chat-query pass. A no-op if
     * [guid] doesn't exist yet (the reconciler's [insertIgnore] call handles that case).
     */
    @Query(
        """
        UPDATE chats SET displayName = :displayName, chatIdentifier = :chatIdentifier,
            style = :style, isArchived = :isArchived
        WHERE guid = :guid
        """
    )
    suspend fun updateMetadata(guid: String, displayName: String?, chatIdentifier: String?, style: Int, isArchived: Boolean)

    /** Only advances the preview when [date] is newer than (or the chat currently has no) date. */
    @Query(
        """
        UPDATE chats SET lastMessageDate = :date, lastMessagePreview = :preview
        WHERE guid = :guid AND (lastMessageDate IS NULL OR :date > lastMessageDate)
        """
    )
    suspend fun updatePreview(guid: String, date: Long, preview: String?)
}
