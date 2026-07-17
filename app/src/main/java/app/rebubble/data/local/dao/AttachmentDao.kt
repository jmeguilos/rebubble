package app.rebubble.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.rebubble.data.local.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE guid = :guid")
    suspend fun getByGuid(guid: String): AttachmentEntity?

    /**
     * IGNORE on conflict: a server-sourced re-insert of an already-known attachment must never
     * clobber a locally-set `localPath`/`downloadState`. Callers that *do* want to update those
     * fields read the existing row via [getByGuid] and write it back via [update].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Update
    suspend fun update(attachment: AttachmentEntity)

    /** Re-points attachments at a message's real guid once a temp-guid send is acknowledged. */
    @Query("UPDATE attachments SET messageGuid = :newMessageGuid WHERE messageGuid = :oldMessageGuid")
    suspend fun reparent(oldMessageGuid: String, newMessageGuid: String)

    @Query("SELECT * FROM attachments WHERE messageGuid = :messageGuid")
    fun observeForMessage(messageGuid: String): Flow<List<AttachmentEntity>>
}
