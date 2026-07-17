package app.rebubble.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val guid: String,
    val style: Int,
    val chatIdentifier: String?,
    val displayName: String?,
    val isArchived: Boolean = false,
    val lastMessageDate: Long?,
    val lastMessagePreview: String?, // denormalized by the ingestor
)
