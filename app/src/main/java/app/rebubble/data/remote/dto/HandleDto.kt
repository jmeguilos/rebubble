package app.rebubble.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A contact/handle (phone number or email) participating in a chat or message.
 *
 * Source: packages/server/src/server/api/serializers/HandleSerializer.ts
 * convert(), lines 54-58 (`originalROWID`, `address` <- `handle.id`, `service`).
 * `uncanonicalizedId`/`country` are only added when `!isForNotification`
 * (lines 78-86) and are omitted here since the client doesn't rely on them.
 */
@Serializable
data class HandleDto(
    @SerialName("originalROWID") val originalRowId: Long? = null,
    val address: String,
    val service: String
)
