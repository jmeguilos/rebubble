package app.rebubble.data.remote.dto.requests

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/v1/message/text`.
 *
 * Source: packages/server/src/server/api/http/api/v1/routers/messageRouter.ts
 * sendText(), lines 238-241 (`tempGuid`, `message`, `method`, `chatGuid`
 * destructured from `ctx.request.body`; `tempGuid` is a client-generated id
 * used to reconcile the optimistic local message with the server's echoed
 * response -- see MessageDto.tempGuid).
 */
@Serializable
data class SendTextRequest(
    val chatGuid: String,
    val tempGuid: String? = null,
    val message: String,
    val method: String? = null
)
