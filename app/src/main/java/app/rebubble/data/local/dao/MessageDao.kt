package app.rebubble.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * Newest-first window for one chat. `associatedMessageType IS NULL` excludes tapback/reaction
     * rows (rendered separately in M2) while keeping group-event rows (itemType != 0), which have
     * no associatedMessageType.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE chatGuid = :chatGuid AND associatedMessageType IS NULL
        ORDER BY dateCreated DESC
        LIMIT :limit
        """
    )
    fun observeMessages(chatGuid: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE guid = :guid")
    suspend fun getByGuid(guid: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    /**
     * Raw PK swap: Room's `@Update` can't change a primary key, so an optimistic send's
     * `temp-<8hex>` guid is rewritten to the server-assigned real guid in place once the send is
     * acknowledged (echo or REST ack, whichever arrives first).
     */
    @Query(
        """
        UPDATE messages SET guid = :realGuid, originalRowId = :originalRowId, sendStatus = :sendStatus
        WHERE guid = :tempGuid
        """
    )
    suspend fun swapGuid(
        tempGuid: String,
        realGuid: String,
        originalRowId: Long?,
        sendStatus: SendStatus,
    )

    @Query("SELECT MIN(dateCreated) FROM messages WHERE chatGuid = :chatGuid")
    suspend fun oldestDateCreated(chatGuid: String): Long?

    @Query("SELECT MAX(originalRowId) FROM messages")
    suspend fun maxOriginalRowId(): Long?
}
