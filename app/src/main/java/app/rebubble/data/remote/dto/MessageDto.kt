package app.rebubble.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An iMessage/SMS message.
 *
 * Source: packages/server/src/server/api/serializers/MessageSerializer.ts
 * convert(), lines 130-165 (`originalROWID`, `guid`, `text` <- `universalText()`
 * which can be null for group-action/system messages, `handleId`, `attachments`,
 * `subject`, `error`, `dateCreated`/`dateRead`/`dateDelivered` as ms-epoch via
 * `.getTime()`, `isFromMe`, `itemType`, `groupTitle`, `groupActionType`,
 * `associatedMessageGuid`/`associatedMessageType` for tapback/reaction messages,
 * `expressiveSendStyleId`, `threadOriginatorGuid`); `threadOriginatorPart` (line
 * 178, only when `!isForNotification`); `chats` (line 206, only when
 * `config.includeChats`); `dateEdited`/`dateRetracted` (lines 222-223, only on
 * `isMinVentura` servers) -- all nullable-with-default since the server may omit
 * them depending on serialization config / macOS version.
 *
 * `tempGuid` is not part of the base serializer output; it is injected by the
 * router after a send completes so the client can match its optimistic local
 * message to the server's echoed copy (see messageRouter.ts sendText(), lines
 * 277-278: `data.tempGuid = tempGuid`, and chatRouter.ts create(), lines 227-231).
 */
@Serializable
data class MessageDto(
    @SerialName("originalROWID") val originalRowId: Long? = null,
    val tempGuid: String? = null,
    val guid: String,
    val text: String? = null,
    val subject: String? = null,
    val error: Int = 0,
    val dateCreated: Long? = null,
    val dateRead: Long? = null,
    val dateDelivered: Long? = null,
    val isFromMe: Boolean = false,
    val handle: HandleDto? = null,
    val handleId: Int? = null,
    val attachments: List<AttachmentDto> = emptyList(),
    val itemType: Int? = null,
    val groupTitle: String? = null,
    val groupActionType: Int? = null,
    val associatedMessageGuid: String? = null,
    val associatedMessageType: String? = null,
    val threadOriginatorGuid: String? = null,
    val threadOriginatorPart: String? = null,
    val expressiveSendStyleId: String? = null,
    val dateEdited: Long? = null,
    val dateRetracted: Long? = null,
    val chats: List<ChatDto>? = null
)
