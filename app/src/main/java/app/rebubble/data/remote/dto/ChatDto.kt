package app.rebubble.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An iMessage/SMS chat (conversation).
 *
 * Source: packages/server/src/server/api/serializers/ChatSerializer.ts
 * convert(), lines 54-61 (`originalROWID`, `guid`, `style`, `chatIdentifier`,
 * `displayName`; `style` is 43 for a group chat, 45 for a 1:1 DM per the
 * comment in MessageSerializer.ts:58-60) and lines 63-74 (`participants`,
 * only populated when `config.includeParticipants`, hence default-empty here).
 * `lastMessage` is attached ad hoc by chatRouter.ts find() when `?with=lastmessage`
 * is requested (see routers/chatRouter.ts, `withLastMessage` branch).
 */
@Serializable
data class ChatDto(
    @SerialName("originalROWID") val originalRowId: Long? = null,
    val guid: String,
    val style: Int,
    val chatIdentifier: String,
    val displayName: String? = null,
    val participants: List<HandleDto> = emptyList(),
    val lastMessage: MessageDto? = null
)
