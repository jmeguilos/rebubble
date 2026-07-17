package app.rebubble.ui.chat

import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus

/**
 * Immutable chat-screen state. [items] is newest-first for `LazyColumn(reverseLayout = true)`.
 */
data class ChatUiState(
    val title: String = "",
    val isSms: Boolean = false,
    /** Contact photo for 1:1 chats when known; null → monogram / person glyph. */
    val avatarPath: String? = null,
    val isGroup: Boolean = false,
    val items: List<ChatUiItem> = emptyList(),
    val endReached: Boolean = false,
    val loading: Boolean = true,
    val transientError: String? = null,
    val pendingScrollToBottom: Boolean = false,
)

sealed interface ChatUiItem {
    val key: String
    val contentType: String

    data class DaySeparator(
        val label: String,
        val dayEpochDay: Long,
    ) : ChatUiItem {
        override val key: String get() = "day-$dayEpochDay"
        override val contentType: String get() = "day-separator"
    }

    data class GroupEvent(
        val guid: String,
        val label: String,
        val dateCreated: Long,
    ) : ChatUiItem {
        override val key: String get() = guid
        override val contentType: String get() = "group-event"
    }

    data class Bubble(
        val message: MessageEntity,
        val attachments: List<AttachmentEntity>,
        val showTail: Boolean,
        val isFirstInRun: Boolean,
        val isLastInRun: Boolean,
    ) : ChatUiItem {
        override val key: String get() = message.guid
        override val contentType: String
            get() = if (attachments.any { it.mimeType?.startsWith("image/") == true || it.width != null }) {
                "image"
            } else {
                "text"
            }

        val isFailed: Boolean get() = message.sendStatus == SendStatus.FAILED
        val isSending: Boolean get() = message.sendStatus == SendStatus.SENDING
    }
}
