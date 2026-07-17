package app.rebubble.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.rebubble.data.local.entity.ChatHandleCrossRef
import app.rebubble.data.local.entity.HandleEntity
import kotlinx.coroutines.flow.Flow

/** One row from [HandleDao.observeAllChatParticipants] — chat ↔ handle join. */
data class ChatParticipantRow(
    val chatGuid: String,
    val address: String,
    val service: String,
)

@Dao
interface HandleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsert(handles: List<HandleEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertChatHandleCrossRefs(crossRefs: List<ChatHandleCrossRef>)

    @Query(
        """
        SELECT handles.* FROM handles
        INNER JOIN chat_handles ON handles.address = chat_handles.address
        WHERE chat_handles.chatGuid = :chatGuid
        """
    )
    suspend fun participantsFor(chatGuid: String): List<HandleEntity>

    /**
     * All chat↔handle joins in one query. Used by [app.rebubble.data.repo.ChatRepository] so the
     * conversation list does not N+1 [participantsFor] per chat on every emission.
     */
    @Query(
        """
        SELECT chat_handles.chatGuid AS chatGuid,
               handles.address AS address,
               handles.service AS service
        FROM chat_handles
        INNER JOIN handles ON handles.address = chat_handles.address
        """
    )
    fun observeAllChatParticipants(): Flow<List<ChatParticipantRow>>
}
