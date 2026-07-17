package app.rebubble.data.remote.dto.requests

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/v1/fcm/device`.
 *
 * Source: packages/server/src/server/api/http/api/v1/routers/fcmRouter.ts
 * registerDevice(), line 36 (`const { name, identifier } = ctx.request?.body`).
 */
@Serializable
data class FcmDeviceRequest(
    val name: String,
    val identifier: String
)
