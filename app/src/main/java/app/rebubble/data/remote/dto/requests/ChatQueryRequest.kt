package app.rebubble.data.remote.dto.requests

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/v1/chat/query`.
 *
 * Source: packages/server/src/server/api/http/api/v1/routers/chatRouter.ts
 * query(), lines 118-127 (`body?.with` parsed via `parseWithQuery`, `body?.sort`,
 * `body.offset`, `body.limit`).
 */
@Serializable
data class ChatQueryRequest(
    val with: List<String> = emptyList(),
    val sort: String? = null,
    val offset: Int? = null,
    val limit: Int? = null
)
