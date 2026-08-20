package app.rebubble.data.remote.dto.requests

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/v1/chat/new`.
 *
 * Source: packages/server/src/server/api/http/api/v1/routers/chatRouter.ts
 * create(), lines 193-215 (`addresses`, `message`, `method`, `service`, `tempGuid`, `subject`,
 * `effectId`, `attributedBody` destructured from `ctx.request.body` and passed straight through
 * to `ChatInterface.create`); validators/chatValidator.ts `createRules`, lines 43-51
 * (`addresses` required array, `message`/`method`/`service`/`tempGuid`/`effectId`/`subject` all
 * optional strings; `method` restricted to `apple-script|private-api`, `service` to
 * `iMessage|SMS`). `subject`/`effectId`/`attributedBody` are accepted by the server but unused by
 * this client (no MMS-subject or Send-with-effect UI yet) and therefore omitted here.
 *
 * `method` is left `null` (server default `"apple-script"`, chatInterface.ts `create()` line
 * 285) rather than hardcoded, matching [SendTextRequest.method]'s same omit-and-let-the-server-
 * decide convention -- this client has no UI to choose Private API vs AppleScript per send.
 */
@Serializable
data class CreateChatRequest(
    val addresses: List<String>,
    val message: String,
    val method: String? = null,
    val service: String = "iMessage",
    val tempGuid: String? = null
)
