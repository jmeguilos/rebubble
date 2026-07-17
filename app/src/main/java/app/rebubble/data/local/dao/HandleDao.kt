package app.rebubble.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.rebubble.data.local.entity.ChatHandleCrossRef
import app.rebubble.data.local.entity.HandleEntity

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
}
