package app.rebubble.data.remote.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

private const val PLACEHOLDER_BASE_URL = "http://placeholder.invalid/api/v1/"

/**
 * Builds a [BlueBubblesApi] wired exactly the way [app.rebubble.di.NetworkModule] wires the
 * production one (placeholder base URL + base-url-rewrite interceptor + guid-auth interceptor),
 * so tests exercise the real interceptor chain rather than a hand-rolled fake.
 *
 * Optional timeouts let tests force ambiguous mid-response failures (e.g. MockWebServer
 * [okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE] with a short read timeout).
 */
fun testBlueBubblesApi(
    credentials: ServerCredentialsProvider,
    connectTimeoutMs: Long = 10_000,
    readTimeoutMs: Long = 10_000,
    writeTimeoutMs: Long = 10_000,
): BlueBubblesApi {
    val json = Json { ignoreUnknownKeys = true }
    val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
        .addInterceptor(DynamicBaseUrlInterceptor(credentials))
        .addInterceptor(GuidAuthInterceptor(credentials))
        .build()
    val retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
    return retrofit.create(BlueBubblesApi::class.java)
}
