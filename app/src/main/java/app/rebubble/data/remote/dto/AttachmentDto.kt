package app.rebubble.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An attachment on a message.
 *
 * Source: packages/server/src/server/api/serializers/AttachmentSerializer.ts
 * convert(): `originalROWID`/`guid`/`uti`/`mimeType`/`transferName`/`totalBytes`
 * (lines 99-106, always present); `hasLivePhoto` (line 117, only when
 * `!isForNotification`, hence nullable-with-default here); `height`/`width`
 * (lines 130-131, only when `config.loadMetadata`, hence nullable-with-default).
 */
@Serializable
data class AttachmentDto(
    @SerialName("originalROWID") val originalRowId: Long? = null,
    val guid: String,
    val uti: String? = null,
    val mimeType: String? = null,
    val transferName: String? = null,
    val totalBytes: Long? = null,
    val height: Int? = null,
    val width: Int? = null,
    val hasLivePhoto: Boolean = false
)
