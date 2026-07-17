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
 * The real implementation is `app.rebubble.data.repo.ServerConfigRepository`, bound in
 * `app.rebubble.di.RepositoryModule` (superseding T3's null/null placeholder that used to live in
 * `app.rebubble.di.NetworkModule`). Both methods are synchronous reads of an in-memory snapshot
 * that repository keeps current from its persisted config — see its KDoc for how it avoids a
 * cold-start null on an already-configured server (a one-time synchronous prime, safe only
 * because both callers below run on a background thread) and for its retry-on-failure behavior.
 */
interface ServerCredentialsProvider {
    /** The user-configured server base URL (e.g. `http://192.168.1.20:1234`), or null if unset. */
    fun url(): String?

    /** The user-configured server password, or null if unset. */
    fun password(): String?
}
