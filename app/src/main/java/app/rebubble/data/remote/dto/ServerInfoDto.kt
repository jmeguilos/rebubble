package app.rebubble.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * BlueBubbles server metadata, as returned from `GET /api/v1/server/info`.
 *
 * Source: packages/server/src/server/api/interfaces/generalInterface.ts
 * getServerMetadata(), lines 13-27. Wire field names are snake_case; this DTO
 * maps them to idiomatic camelCase Kotlin properties via @SerialName.
 */
@Serializable
data class ServerInfoDto(
    @SerialName("computer_id") val computerId: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("server_version") val serverVersion: String,
    @SerialName("private_api") val privateApi: Boolean = false,
    @SerialName("helper_connected") val helperConnected: Boolean = false,
    @SerialName("proxy_service") val proxyService: String? = null,
    @SerialName("detected_icloud") val detectedIcloud: String? = null,
    @SerialName("detected_imessage") val detectedImessage: String? = null,
    @SerialName("macos_time_sync") val macosTimeSync: Long? = null,
    @SerialName("local_ipv4s") val localIpv4s: List<String> = emptyList(),
    @SerialName("local_ipv6s") val localIpv6s: List<String> = emptyList()
)
