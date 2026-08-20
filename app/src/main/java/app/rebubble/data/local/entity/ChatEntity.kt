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
    /**
     * Local-only unread counter (schema v2). Incremented by
     * [app.rebubble.data.sync.MessageIngestor] for each newly inserted incoming, non-reaction
     * message while the chat is not on screen, and zeroed when the chat is opened. Never sourced
     * from the server — BlueBubbles has no per-chat unread state this client can trust.
     */
    val unreadCount: Int = 0,
)
