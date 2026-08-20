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
 *
 * `isArchived` mirrors the local `ChatEntity.isArchived` column (archiving is a Messages.app/macOS
 * action, so the server is the source of truth). Not independently re-verified against server
 * source this task (no server checkout available, same caveat T3 flagged for `ContactDto`);
 * defaults `false` so an older/differently-configured server that omits the field never regresses
 * a chat to archived. Consumed by T7's [app.rebubble.data.sync.Reconciler] chat-metadata pass.
 *
 * `messages` is the same `output.messages` field as `lastMessage`'s sibling in `ChatSerializer.ts`
 * `convert()` lines 71-78, but populated by `POST /chat/new` (chatRouter.ts `create()`, lines
 * 218-232): `ChatSerializer.serialize({chat, config:{includeParticipants:true,
 * includeMessages:true}})` embeds the chat's just-sent message(s) directly on the chat object
 * (there is no separate top-level `messages` field in that response -- `data` IS this DTO).
 * `create()` also re-injects the request's `tempGuid` onto each entry (line 230:
 * `i.tempGuid = tempGuid`) before responding, mirroring `MessageDto.tempGuid`'s doc. NOTE: other
 * endpoints are NOT uniformly null — any caller that requests messages gets an empty-or-populated
 * list; the reconciler's `POST /chat/query` with `with=lastMessage` sets
 * `includeMessages = withLastMessage` (chatInterface.ts:65), so those responses carry
 * `messages: []` (present, empty). Nothing on that path reads this field; keep it that way unless
 * the reconciler is deliberately taught to consume it.
 */
@Serializable
data class ChatDto(
    @SerialName("originalROWID") val originalRowId: Long? = null,
    val guid: String,
    val style: Int,
    val chatIdentifier: String,
    val displayName: String? = null,
    val isArchived: Boolean = false,
    val participants: List<HandleDto> = emptyList(),
    val lastMessage: MessageDto? = null,
    val messages: List<MessageDto>? = null
)
