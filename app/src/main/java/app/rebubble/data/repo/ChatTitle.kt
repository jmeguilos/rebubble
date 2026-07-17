package app.rebubble.data.repo

import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.ContactEntity
import app.rebubble.data.local.entity.HandleEntity

/** BlueBubbles / iMessage group-chat style (`ChatEntity.style`). */
internal const val GROUP_CHAT_STYLE = 43

/**
 * Shared chat title resolution used by [ChatRepository] (list UI) and the notification pipeline
 * (T15 MessagingStyle conversation title). Do not fork this fallback chain.
 *
 * Order: non-blank [ChatEntity.displayName] → per-participant labels (contact displayName when
 * present, else address) joined ", " → [ChatEntity.chatIdentifier] → guid.
 */
internal fun resolveChatTitle(
    chat: ChatEntity,
    participants: List<HandleEntity>,
    contactsByAddress: Map<String, ContactEntity>,
): String {
    chat.displayName?.takeIf { it.isNotBlank() }?.let { return it }

    if (participants.isNotEmpty()) {
        return participants.joinToString(", ") { handle ->
            contactsByAddress[handle.address]?.displayName?.takeIf { it.isNotBlank() }
                ?: handle.address
        }
    }

    return chat.chatIdentifier?.takeIf { it.isNotBlank() } ?: chat.guid
}
