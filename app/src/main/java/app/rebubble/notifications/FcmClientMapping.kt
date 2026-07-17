package app.rebubble.notifications

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure, Firebase-free params extracted from a BlueBubbles `GET /fcm/client` payload (the raw
 * `google-services.json` object returned as envelope `data`).
 *
 * Field mapping (Rebubble contract; see report for upstream divergence on gcmSenderId):
 * - [applicationId] ← `client[0].client_info.mobilesdk_app_id`
 * - [apiKey] ← `client[0].api_key[0].current_key`
 * - [projectId] ← `project_info.project_id`
 * - [gcmSenderId] ← `project_info.project_number`
 * - [storageBucket] ← `project_info.storage_bucket` (optional)
 * - [databaseUrl] ← `project_info.firebase_url` (optional)
 *
 * Missing `oauth_client` is tolerated — we do not read it.
 */
data class FirebaseOptionsParams(
    val apiKey: String,
    val applicationId: String,
    val projectId: String,
    val gcmSenderId: String,
    val storageBucket: String? = null,
    val databaseUrl: String? = null,
)

/** Thrown when [fcmClientToFirebaseOptions] cannot extract the required fields. */
class FcmClientMappingException(message: String) : Exception(message)

/**
 * Maps a `/fcm/client` `data` [JsonObject] to [FirebaseOptionsParams].
 * Pure / unit-testable — no Firebase classes on the hot path.
 */
fun fcmClientToFirebaseOptions(json: JsonObject): FirebaseOptionsParams {
    val projectInfo = json["project_info"]?.jsonObject
        ?: throw FcmClientMappingException("missing project_info")

    val projectId = projectInfo.requiredString("project_id")
    val gcmSenderId = projectInfo.requiredString("project_number")
    val storageBucket = projectInfo.optionalString("storage_bucket")
    val databaseUrl = projectInfo.optionalString("firebase_url")

    val clients = json["client"]?.jsonArray
        ?: throw FcmClientMappingException("missing client array")
    if (clients.isEmpty()) {
        throw FcmClientMappingException("client array is empty")
    }
    val client = clients[0].jsonObject

    val applicationId = client["client_info"]?.jsonObject?.requiredString("mobilesdk_app_id")
        ?: throw FcmClientMappingException("missing client[0].client_info.mobilesdk_app_id")

    val apiKeys = client["api_key"]?.jsonArray
        ?: throw FcmClientMappingException("missing client[0].api_key")
    if (apiKeys.isEmpty()) {
        throw FcmClientMappingException("client[0].api_key array is empty")
    }
    val apiKey = apiKeys[0].jsonObject.requiredString("current_key")

    return FirebaseOptionsParams(
        apiKey = apiKey,
        applicationId = applicationId,
        projectId = projectId,
        gcmSenderId = gcmSenderId,
        storageBucket = storageBucket,
        databaseUrl = databaseUrl,
    )
}

private fun JsonObject.requiredString(key: String): String {
    val value = this[key]?.jsonPrimitive?.content
    if (value.isNullOrBlank()) {
        throw FcmClientMappingException("missing or blank '$key'")
    }
    return value
}

private fun JsonObject.optionalString(key: String): String? =
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
