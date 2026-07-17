package app.rebubble.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * BlueBubbles REST API response envelope.
 *
 * Wire shape: `{status, message, data?, metadata?, error?:{type,message}}`.
 *
 * Source: packages/server/src/server/api/http/api/v1/responses/types.ts
 * (ResponseJson, lines 34-41: status/message/error/data/metadata) and
 * responses/success.ts (Success ctor, lines 66-73: data/metadata only present
 * when provided) and responses/errors.ts (HTTPError subclasses, e.g.
 * BadRequest lines 47-59: status + error{type,message}).
 */
@Serializable
data class Envelope<T>(
    val status: Int,
    val message: String,
    val data: T? = null,
    val metadata: PaginationMetadata? = null,
    val error: ErrorBody? = null
)

/**
 * Typed error block of the envelope.
 *
 * Source: responses/types.ts lines 29-32 (`ErrorBody = { type: ErrorTypes; message: string }`).
 */
@Serializable
data class ErrorBody(
    val type: String,
    val message: String
)

/**
 * Pagination metadata attached to list endpoints.
 *
 * Source: chatRouter.ts query() line 144 / getMessages() line 114
 * (`{ offset, limit, total, count }`) and messageRouter.ts query() line 233
 * (`{ offset, limit, total: totalCount, count: data.length }`).
 */
@Serializable
data class PaginationMetadata(
    val offset: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val count: Int? = null
)
