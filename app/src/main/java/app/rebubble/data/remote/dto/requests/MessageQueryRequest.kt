package app.rebubble.data.remote.dto.requests

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A single SQL-ish filter clause for [MessageQueryRequest.where].
 *
 * Source: packages/server/src/server/databases/imessage/types.ts lines 15-18
 * (`DBWhereItem = { statement: string; args: { [key: string]: string | number } }`),
 * e.g. `{ statement: "message.text LIKE :term COLLATE NOCASE", args: { term: "%hi%" } }`
 * per the sample in messageRouter.ts query(), line 160. `args` values are either
 * strings or numbers, so a [JsonObject] is used to avoid a custom sealed-type
 * serializer for a request-only field.
 */
@Serializable
data class WhereClause(
    val statement: String,
    val args: JsonObject = JsonObject(emptyMap())
)

/**
 * Request body for `POST /api/v1/message/query`.
 *
 * Source: packages/server/src/server/api/http/api/v1/routers/messageRouter.ts
 * query(), lines 114-125 (`chatGuid`, `with`, `offset`, `limit`, `where`, `sort`,
 * `after`, `before` destructured from `ctx.request.body`).
 */
@Serializable
data class MessageQueryRequest(
    val chatGuid: String? = null,
    val with: List<String> = emptyList(),
    val where: List<WhereClause> = emptyList(),
    val sort: String? = null,
    val after: Long? = null,
    val before: Long? = null,
    val offset: Int? = null,
    val limit: Int? = null
)
