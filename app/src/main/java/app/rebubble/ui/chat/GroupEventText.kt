package app.rebubble.ui.chat

import app.rebubble.data.local.entity.MessageEntity

/**
 * Human-readable centered label for a group-event row (`itemType != 0`).
 *
 * Mapping (BlueBubbles / upstream app):
 * - itemType 1 + action 0 → "{name} added {other} to the conversation"
 * - itemType 1 + action 1 → "{name} removed {other} from the conversation"
 * - itemType 2 + title → "{name} named the conversation "{title}""
 * - itemType 2 + no title → "{name} removed the name from the conversation"
 * - itemType 3 + 0 → "{name} left the conversation"
 * - itemType 3 + 1 → "{name} changed the group photo"
 * - itemType 3 + 2 → "{name} removed the group photo"
 *
 * [senderName] is the resolved display name ("You" / contact / "Someone").
 * [otherName] is the added/removed participant when known; defaults to "someone".
 */
fun formatGroupEventText(
    itemType: Int,
    groupActionType: Int,
    groupTitle: String?,
    senderName: String,
    otherName: String = "someone",
): String {
    val name = senderName.ifBlank { "Someone" }
    val other = otherName.ifBlank { "someone" }
    return when (itemType) {
        1 -> when (groupActionType) {
            0 -> "$name added $other to the conversation"
            1 -> "$name removed $other from the conversation"
            else -> "Unknown group event"
        }
        2 -> {
            val title = groupTitle?.takeIf { it.isNotBlank() }
            if (title != null) {
                "$name named the conversation \"$title\""
            } else {
                "$name removed the name from the conversation"
            }
        }
        3 -> when (groupActionType) {
            0 -> "$name left the conversation"
            1 -> "$name changed the group photo"
            2 -> "$name removed the group photo"
            else -> "Unknown group event"
        }
        else -> "Unknown group event"
    }
}

fun formatGroupEventText(
    message: MessageEntity,
    senderName: String,
    otherName: String = message.text?.takeIf { it.isNotBlank() } ?: "someone",
): String = formatGroupEventText(
    itemType = message.itemType,
    groupActionType = message.groupActionType,
    groupTitle = message.groupTitle,
    senderName = senderName,
    otherName = otherName,
)

/** Resolve a display name for the event actor. */
fun resolveGroupEventSenderName(
    isFromMe: Boolean,
    senderAddress: String?,
    contactsByAddress: Map<String, String>,
): String {
    if (isFromMe) return "You"
    val address = senderAddress?.takeIf { it.isNotBlank() } ?: return "Someone"
    return contactsByAddress[address]?.takeIf { it.isNotBlank() } ?: "Someone"
}
