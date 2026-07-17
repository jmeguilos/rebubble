package app.rebubble.data.remote.dto

import kotlinx.serialization.json.JsonObject

/**
 * The `google-services.json` contents served by `GET /api/v1/fcm/client`.
 *
 * Source: packages/server/src/server/api/http/api/v1/routers/fcmRouter.ts
 * getClientConfig(), lines 11-32: the server reads the Google Services file
 * from disk (`FileSystem.getFCMClient()`) and returns it verbatim as `data`,
 * with no fixed schema of its own (Google can add/remove fields over time, and
 * the router itself patches in an `oauth_client` array if absent). Modeling
 * this as a raw [JsonObject] passthrough avoids brittleness against upstream
 * schema drift; callers that need a specific field (e.g. `project_info.project_id`)
 * read it directly off the JsonObject.
 */
typealias FcmClientDto = JsonObject
