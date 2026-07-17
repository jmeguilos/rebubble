package app.rebubble.data.remote.api

/**
 * Supplies the user-configured BlueBubbles server URL and password at request time.
 *
 * The BlueBubbles server authenticates every REST call via a `?guid=<password>` query
 * parameter, and its host/port/scheme are only known once the user has configured a server (and
 * can change at runtime, e.g. if they repoint the app at a different server). This seam lets
 * [GuidAuthInterceptor] and [DynamicBaseUrlInterceptor] read the current value on every request
 * rather than baking it into the DI graph at construction time.
 *
 * The real implementation is `ServerConfigRepository` (a later task); until then,
 * `app.rebubble.di.NetworkModule` binds a placeholder that returns null for both.
 */
interface ServerCredentialsProvider {
    /** The user-configured server base URL (e.g. `http://192.168.1.20:1234`), or null if unset. */
    fun url(): String?

    /** The user-configured server password, or null if unset. */
    fun password(): String?
}
