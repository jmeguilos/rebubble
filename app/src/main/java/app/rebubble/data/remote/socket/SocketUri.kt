package app.rebubble.data.remote.socket

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * Builds the socket.io connection [URI] for [IoSocketClient].
 *
 * Auth is via handshake query `guid=<password>` (percent-encoded via OkHttp's
 * [okhttp3.HttpUrl.Builder.addQueryParameter], same encoding REST uses through
 * [app.rebubble.data.remote.api.GuidAuthInterceptor]). The BlueBubbles HTTP service accepts
 * `socket.handshake.query.guid` (or `.password`) and runs `decodeURI` on it — see
 * `packages/server/src/server/api/http/index.ts` connection handler.
 *
 * @param baseUrl user-configured server base (e.g. `http://192.168.1.20:1234`); trailing slashes
 *   are stripped.
 * @param password server password (the same value REST puts in `?guid=`).
 * @throws IllegalArgumentException if [baseUrl] is not a valid http(s) URL.
 */
fun buildSocketUri(baseUrl: String, password: String): URI {
    val trimmed = baseUrl.trimEnd('/')
    val parsed = trimmed.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Invalid socket base URL: $baseUrl")
    val withGuid = parsed.newBuilder()
        .setQueryParameter("guid", password)
        .build()
    return URI(withGuid.toString())
}

/** Handshake query string (`guid=...`) extracted from [buildSocketUri], for `IO.Options.query`. */
fun buildSocketQuery(password: String): String {
    // Encode via the same OkHttp path as [buildSocketUri] so REST and socket agree.
    val encoded = okhttp3.HttpUrl.Builder()
        .scheme("http")
        .host("localhost")
        .addQueryParameter("guid", password)
        .build()
        .query
    return checkNotNull(encoded) { "OkHttp failed to encode guid query" }
}
