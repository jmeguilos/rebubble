package app.rebubble.data.remote.api

import app.rebubble.data.remote.dto.ChatDto
import app.rebubble.data.remote.dto.ContactDto
import app.rebubble.data.remote.dto.Envelope
import app.rebubble.data.remote.dto.MessageDto
import app.rebubble.data.remote.dto.ServerInfoDto
import app.rebubble.data.remote.dto.requests.ChatQueryRequest
import app.rebubble.data.remote.dto.requests.FcmDeviceRequest
import app.rebubble.data.remote.dto.requests.MessageQueryRequest
import app.rebubble.data.remote.dto.requests.SendTextRequest
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * BlueBubbles server REST API surface used by the app.
 *
 * Method paths are relative (no leading slash) and are resolved against the placeholder base
 * URL `http://placeholder.invalid/api/v1/` configured in `app.rebubble.di.NetworkModule`. Every
 * request is additionally routed to the user-configured server host by
 * [DynamicBaseUrlInterceptor] and gets a `?guid=<password>` query parameter appended by
 * [GuidAuthInterceptor].
 *
 * Source: task-3-brief.md (interface signature reproduced verbatim).
 */
interface BlueBubblesApi {
    @GET("server/info")
    suspend fun serverInfo(): Envelope<ServerInfoDto>

    @POST("chat/query")
    suspend fun queryChats(@Body b: ChatQueryRequest): Envelope<List<ChatDto>>

    @GET("chat/{guid}/message")
    suspend fun chatMessages(
        @Path("guid") g: String,
        @Query("with") with: String,
        @Query("sort") sort: String,
        @Query("before") before: Long?,
        @Query("limit") limit: Int
    ): Envelope<List<MessageDto>>

    @POST("message/query")
    suspend fun queryMessages(@Body b: MessageQueryRequest): Envelope<List<MessageDto>>

    @POST("message/text")
    suspend fun sendText(@Body b: SendTextRequest): Envelope<MessageDto>

    @Multipart
    @POST("message/attachment")
    suspend fun sendAttachment(
        @Part file: MultipartBody.Part,
        @PartMap fields: Map<String, RequestBody>
    ): Envelope<MessageDto>

    @GET("fcm/client")
    suspend fun fcmClient(): Envelope<JsonObject>

    @POST("fcm/device")
    suspend fun addFcmDevice(@Body b: FcmDeviceRequest): Envelope<JsonObject?>

    @GET("contact")
    suspend fun contacts(): Envelope<List<ContactDto>>
}
