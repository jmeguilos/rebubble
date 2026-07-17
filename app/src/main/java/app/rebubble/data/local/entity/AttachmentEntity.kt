package app.rebubble.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadState { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, FAILED }

@Entity(tableName = "attachments", indices = [Index("messageGuid")])
data class AttachmentEntity(
    @PrimaryKey val guid: String,
    val messageGuid: String,
    val uti: String?,
    val mimeType: String?,
    val transferName: String?,
    val totalBytes: Long?,
    val width: Int?,
    val height: Int?,
    val hasLivePhoto: Boolean,
    val localPath: String?,
    val downloadState: DownloadState,
)
