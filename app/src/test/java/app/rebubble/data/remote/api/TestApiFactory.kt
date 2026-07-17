package app.rebubble.data.remote.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private const val PLACEHOLDER_BASE_URL = "http://placeholder.invalid/api/v1/"

/**
 * Builds a [BlueBubblesApi] wired exactly the way [app.rebubble.di.NetworkModule] wires the
 * production one (placeholder base URL + base-url-rewrite interceptor + guid-auth interceptor),
 * so tests exercise the real interceptor chain rather than a hand-rolled fake.
 */
fun testBlueBubblesApi(credentials: ServerCredentialsProvider): BlueBubblesApi {
    val json = Json { ignoreUnknownKeys = true }
    val client = OkHttpClient.Builder()
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
