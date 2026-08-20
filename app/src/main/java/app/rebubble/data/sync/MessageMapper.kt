package app.rebubble.data.sync

import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.DownloadState
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.data.remote.dto.MessageDto

/**
 * Pure `MessageDto -> MessageEntity` (and attachment) mapping used by [MessageIngestor].
 *
 * The two normalizations here are load-bearing for downstream queries (see T4 forward-flags):
 *  - [stripAssociatedPrefix] removes BlueBubbles' `p:<part>/` and `bp:` prefixes so a tapback's
 *    `associatedMessageGuid` matches the plain guid of the message it targets.
 *  - an empty-string `associatedMessageType` is coerced to `null` because
 *    `MessageDao.observeMessages` filters on `associatedMessageType IS NULL` to hide reaction rows;
 *    an empty string would incorrectly leave a reaction visible in the main thread.
 */
object MessageMapper {

    private val PART_PREFIX = Regex("^p:\\d+/")

    internal const val ATTACHMENT_PREVIEW = "📎 Attachment"

    /** Freshly-mapped entity carries [SendStatus.SENT]; the ingestor overrides it where needed. */
    fun toEntity(dto: MessageDto, chatGuid: String): MessageEntity = MessageEntity(
        guid = dto.guid,
        chatGuid = chatGuid,
        originalRowId = dto.originalRowId,
        text = dto.text,
        subject = dto.subject,
        isFromMe = dto.isFromMe,
        senderAddress = dto.handle?.address,
        dateCreated = dto.dateCreated ?: 0L,
        dateRead = dto.dateRead,
        dateDelivered = dto.dateDelivered,
        error = dto.error,
        itemType = dto.itemType ?: 0,
        groupActionType = dto.groupActionType ?: 0,
        groupTitle = dto.groupTitle,
        associatedMessageGuid = stripAssociatedPrefix(dto.associatedMessageGuid),
        associatedMessageType = dto.associatedMessageType?.takeIf { it.isNotEmpty() },
        threadOriginatorGuid = dto.threadOriginatorGuid,
        expressiveSendStyleId = dto.expressiveSendStyleId,
        dateEdited = dto.dateEdited,
        dateRetracted = dto.dateRetracted,
        sendStatus = SendStatus.SENT,
    )

    fun toAttachmentEntities(dto: MessageDto, messageGuid: String): List<AttachmentEntity> =
        dto.attachments.map { a ->
            AttachmentEntity(
                guid = a.guid,
                messageGuid = messageGuid,
                uti = a.uti,
                mimeType = a.mimeType,
                transferName = a.transferName,
                totalBytes = a.totalBytes,
                width = a.width,
                height = a.height,
                hasLivePhoto = a.hasLivePhoto,
                // Local-only fields are never sourced from the server; a re-insert must not clobber
                // them (see AttachmentDao.insertAll = IGNORE).
                localPath = null,
                downloadState = DownloadState.NOT_DOWNLOADED,
            )
        }

    /**
     * Preview text for the denormalized `chats.lastMessagePreview`: message text, else an
     * attachment marker, else a group-event summary, else "".
     *
     * Shared by both writers of that column so they can never drift: [MessageIngestor.ingest]
     * (every inbound message) and [Reconciler]'s chat pass (the `lastMessage` attached to a
     * `POST /chat/query` row, which is how a fresh install gets previews before backfill runs).
     */
    fun previewFor(dto: MessageDto, mapped: MessageEntity): String {
        val text = mapped.text?.takeIf { it.isNotBlank() }
        return when {
            text != null -> text
            dto.attachments.isNotEmpty() -> ATTACHMENT_PREVIEW
            mapped.itemType != 0 -> groupEventSummary(mapped)
            else -> ""
        }
    }

    private fun groupEventSummary(m: MessageEntity): String =
        m.groupTitle?.takeIf { it.isNotBlank() }?.let { "Named the conversation \"$it\"" } ?: "Group event"

    /**
     * Strips the reply-part prefix (`p:<n>/`) and/or the "before part" prefix (`bp:`) that
     * BlueBubbles attaches to `associatedMessageGuid`, e.g. `p:0/ABC-123` -> `ABC-123`,
     * `bp:ABC-123` -> `ABC-123`.
     */
    fun stripAssociatedPrefix(raw: String?): String? {
        if (raw == null) return null
        val withoutPart = PART_PREFIX.replaceFirst(raw, "")
        return withoutPart.removePrefix("bp:")
    }
}
