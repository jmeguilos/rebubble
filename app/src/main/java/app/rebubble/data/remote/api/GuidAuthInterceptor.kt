package app.rebubble.data.remote.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Sets the BlueBubbles server's required `guid=<password>` query parameter on every outgoing
 * request, overwriting any existing `guid` value rather than appending a duplicate.
 *
 * If no password is configured yet (e.g. before onboarding completes), the request is sent
 * unmodified rather than crashing the interceptor chain — the server will reject it (401), which
 * [apiCall] maps to [AuthError].
 */
class GuidAuthInterceptor(
    private val credentials: ServerCredentialsProvider
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val password = credentials.password() ?: return chain.proceed(original)

        val newUrl = original.url.newBuilder()
            .setQueryParameter("guid", password)
            .build()

        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}

/**
 * Rewrites the scheme/host/port (and, when the configured URL has a non-root path, that path as
 * a prefix) of every outgoing request from the Retrofit-configured placeholder base URL
 * (`http://placeholder.invalid/api/v1/`) to the user-configured server URL, resolved at request
 * time via [ServerCredentialsProvider.url].
 *
 * This is the standard pattern for a Retrofit client whose server address is only known at
 * runtime and can change: Retrofit itself is wired against a stable placeholder base URL, and
 * this interceptor swaps in the real target host on every call.
 */
class DynamicBaseUrlInterceptor(
    private val credentials: ServerCredentialsProvider
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val configuredUrl = credentials.url()
            ?: throw IOException("Server URL is not configured")
        val configured = configuredUrl.toHttpUrlOrNull()
            ?: throw IOException("Invalid server URL: $configuredUrl")

        val prefixSegments = configured.pathSegments.filter { it.isNotEmpty() }
        val originalSegments = original.url.pathSegments

        val newUrlBuilder = original.url.newBuilder()
            .scheme(configured.scheme)
            .host(configured.host)
            .port(configured.port)

        if (prefixSegments.isNotEmpty()) {
            newUrlBuilder.encodedPath("/")
            (prefixSegments + originalSegments).forEach { newUrlBuilder.addPathSegment(it) }
        }

        val newRequest = original.newBuilder().url(newUrlBuilder.build()).build()
        return chain.proceed(newRequest)
    }
}
