package app.rebubble.data.repo

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.ServerCredentialsProvider
import app.rebubble.data.remote.api.apiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** The user's persisted BlueBubbles server address + password, once onboarding has completed. */
data class ServerConfig(val url: String, val password: String)

/**
 * Cached server capabilities, as last reported by `GET /api/v1/server/info`
 * (see [app.rebubble.data.remote.dto.ServerInfoDto]).
 */
data class ServerInfo(
    val serverVersion: String?,
    val privateApi: Boolean,
    val helperConnected: Boolean,
    val osVersion: String?,
)

/**
 * Thrown by [ServerConfigRepository.save] when the supplied URL or password is empty, blank, or
 * otherwise not usable (e.g. an unparseable URL, or a scheme other than `http`/`https`).
 */
class InvalidServerConfigException(message: String) : IllegalArgumentException(message)

private val URL_KEY = stringPreferencesKey("server_url")

private val SERVER_INFO_CACHED_KEY = booleanPreferencesKey("server_info_cached")
private val SERVER_INFO_VERSION_KEY = stringPreferencesKey("server_info_server_version")
private val SERVER_INFO_OS_VERSION_KEY = stringPreferencesKey("server_info_os_version")
private val SERVER_INFO_PRIVATE_API_KEY = booleanPreferencesKey("server_info_private_api")
private val SERVER_INFO_HELPER_CONNECTED_KEY = booleanPreferencesKey("server_info_helper_connected")

private const val SECRET_KEY_PASSWORD = "server_password"

private const val HTTP_PREFIX = "http://"
private const val HTTPS_PREFIX = "https://"

private const val LOG_TAG = "ServerConfigRepository"

/** Backoff between retries of the [ServerConfigRepository] init snapshot collector. */
private const val COLLECTOR_RETRY_DELAY_MS = 250L

/**
 * Owns the user's BlueBubbles server configuration (URL + password) and the last-known server
 * capabilities, and doubles as the production [ServerCredentialsProvider] — wired via a Hilt
 * `@Binds` in `app.rebubble.di.RepositoryModule`, superseding T3's null/null placeholder binding
 * that used to live in `app.rebubble.di.NetworkModule`.
 *
 * Storage is split by sensitivity:
 *  - the server URL and cached [ServerInfo] fields live in Preferences DataStore
 *    ("server_config") — non-secret, safe to keep as plain preferences.
 *  - the password lives behind [SecretStore] ("rebubble_secrets" in production, backed by
 *    [EncryptedSecretStore] / `EncryptedSharedPreferences`), since it is effectively a credential.
 *
 * ### The [ServerCredentialsProvider] seam
 * [GuidAuthInterceptor][app.rebubble.data.remote.api.GuidAuthInterceptor] and
 * [DynamicBaseUrlInterceptor][app.rebubble.data.remote.api.DynamicBaseUrlInterceptor] call
 * [url]/[password] synchronously on every request — they can't suspend to read DataStore/
 * EncryptedSharedPreferences per call. So this repository keeps an in-memory [ServerConfig]
 * snapshot ([snapshot]) that a background collector (started in [init]) refreshes from [config]
 * on every emission.
 *
 * That collector's first emission hasn't necessarily landed by the time [url]/[password] are
 * first called (e.g. very early in app startup, or in a freshly-constructed repository right
 * after a process restart). Rather than surface a stale `null` in that window, [url]/[password]
 * fall back to a one-time *synchronous* prime of [snapshot] (`runBlocking { config.first() }`)
 * the first time either is called before the async collector has emitted — see [url]'s KDoc for
 * why that's safe here specifically, but not in general. Once primed — whether synchronously or
 * by the async collector — [snapshot] is kept current by the collector alone.
 *
 * The collector is also wrapped in [kotlinx.coroutines.flow.retryWhen] with a fixed
 * [COLLECTOR_RETRY_DELAY_MS] backoff: a single transient DataStore/SecretStore failure must not
 * permanently stop future config updates for the rest of the process's lifetime.
 */
@Singleton
class ServerConfigRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
    private val apiProvider: Provider<BlueBubblesApi>,
) : ServerCredentialsProvider {

    /** Null until the user has completed onboarding. */
    val config: Flow<ServerConfig?> = dataStore.data.map { prefs ->
        val url = prefs[URL_KEY] ?: return@map null
        val password = secretStore.getString(SECRET_KEY_PASSWORD) ?: return@map null
        ServerConfig(url, password)
    }

    /** Null until [refreshServerInfo] has completed at least once; persists across restarts. */
    val serverInfo: Flow<ServerInfo?> = dataStore.data.map { prefs ->
        if (prefs[SERVER_INFO_CACHED_KEY] != true) return@map null
        ServerInfo(
            serverVersion = prefs[SERVER_INFO_VERSION_KEY],
            privateApi = prefs[SERVER_INFO_PRIVATE_API_KEY] ?: false,
            helperConnected = prefs[SERVER_INFO_HELPER_CONNECTED_KEY] ?: false,
            osVersion = prefs[SERVER_INFO_OS_VERSION_KEY],
        )
    }

    /**
     * [BlueBubblesApi] is injected as a [Provider] rather than directly: `BlueBubblesApi` sits
     * behind an `OkHttpClient` whose interceptors depend on this class's own
     * [ServerCredentialsProvider] binding, so injecting `BlueBubblesApi` directly here would be a
     * circular Dagger binding (`ServerConfigRepository` -> `BlueBubblesApi` -> `OkHttpClient` ->
     * the interceptors -> `ServerCredentialsProvider` -> `ServerConfigRepository`). A [Provider]
     * defers resolution until [refreshServerInfo] actually calls `.get()`, by which point the
     * whole graph — including this singleton — is already constructed.
     */
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var snapshot: ServerConfig? = null

    /**
     * True once [snapshot] has received at least one value (sync or async). Guards the one-time
     * synchronous prime in [primedSnapshot] so it runs at most once.
     */
    @Volatile
    private var primed = false

    /** Guards concurrent callers racing to perform the one-time synchronous prime. */
    private val primeLock = Any()

    init {
        repositoryScope.launch {
            config
                .retryWhen { cause, attempt ->
                    Log.w(
                        LOG_TAG,
                        "config collector failed (attempt ${attempt + 1}); retrying in " +
                            "${COLLECTOR_RETRY_DELAY_MS}ms",
                        cause,
                    )
                    delay(COLLECTOR_RETRY_DELAY_MS)
                    true
                }
                .catch { cause ->
                    // retryWhen above retries unconditionally (both retryWhen and catch are
                    // transparent to CancellationException, so scope cancellation still
                    // propagates normally), so reaching here should be unreachable in practice.
                    // It's a defensive backstop only, so a future change to the retry predicate
                    // can't silently resurrect the original bug (one exception permanently
                    // killing the collector for the rest of the process's lifetime).
                    Log.e(LOG_TAG, "config collector terminated unexpectedly", cause)
                }
                .collect {
                    snapshot = it
                    primed = true
                }
        }
    }

    /**
     * Sanitizes and persists the server URL + password.
     *
     * URL sanitization: trims surrounding whitespace; defaults the scheme to `https://` when the
     * input has none (a bare `host:port` becomes `https://host:port`); an explicit `http://` is
     * preserved as-is (never upgraded to `https://`); trailing slashes are stripped; a trailing
     * `api/v1` path suffix — optionally preceded by further segments, e.g. a legitimate
     * reverse-proxy prefix like `/bluebubbles` — is stripped, since
     * [DynamicBaseUrlInterceptor][app.rebubble.data.remote.api.DynamicBaseUrlInterceptor] already
     * prefixes the Retrofit-configured `/api/v1/...` path ahead of every request, so keeping a
     * pasted `api/v1` suffix verbatim would double it to `/api/v1/api/v1/...`. Throws
     * [InvalidServerConfigException] for a blank input, an unsupported scheme (only `http`/
     * `https` are accepted), or anything else that doesn't parse as a URL.
     *
     * The password is only required to be non-blank; it is not otherwise validated.
     *
     * On an invalid [url] or [password], nothing is persisted — any previously-saved config is
     * left untouched.
     */
    suspend fun save(url: String, password: String) {
        val sanitizedUrl = sanitizeUrl(url)
        val trimmedPassword = password.trim()
        if (trimmedPassword.isEmpty()) {
            throw InvalidServerConfigException("Server password must not be empty")
        }

        secretStore.putString(SECRET_KEY_PASSWORD, trimmedPassword)
        dataStore.edit { prefs -> prefs[URL_KEY] = sanitizedUrl }
    }

    /** Calls `GET /server/info`, caches the capability flags, and returns the fresh [ServerInfo]. */
    suspend fun refreshServerInfo(): ServerInfo {
        val dto = apiCall { apiProvider.get().serverInfo() }
        val info = ServerInfo(
            serverVersion = dto.serverVersion,
            privateApi = dto.privateApi,
            helperConnected = dto.helperConnected,
            osVersion = dto.osVersion,
        )

        dataStore.edit { prefs ->
            prefs[SERVER_INFO_CACHED_KEY] = true
            prefs[SERVER_INFO_PRIVATE_API_KEY] = info.privateApi
            prefs[SERVER_INFO_HELPER_CONNECTED_KEY] = info.helperConnected
            if (info.serverVersion != null) {
                prefs[SERVER_INFO_VERSION_KEY] = info.serverVersion
            } else {
                prefs.remove(SERVER_INFO_VERSION_KEY)
            }
            if (info.osVersion != null) {
                prefs[SERVER_INFO_OS_VERSION_KEY] = info.osVersion
            } else {
                prefs.remove(SERVER_INFO_OS_VERSION_KEY)
            }
        }

        return info
    }

    /**
     * See the [ServerCredentialsProvider] seam section of this class's KDoc for the one-time
     * synchronous prime this (and [password]) can trigger.
     *
     * Never call from the main thread. The first call made before the async snapshot collector
     * has emitted blocks the calling thread (via `runBlocking`) to read DataStore/SecretStore
     * synchronously. That's safe here only because the sole production callers —
     * [app.rebubble.data.remote.api.GuidAuthInterceptor] and
     * [app.rebubble.data.remote.api.DynamicBaseUrlInterceptor] — run as OkHttp interceptors on a
     * background dispatcher thread, never the main thread. Calling this from the main thread
     * could block it and risk an ANR.
     */
    override fun url(): String? = primedSnapshot()?.url

    /** See the KDoc on [url]; the same one-time synchronous-prime caveat applies here. */
    override fun password(): String? = primedSnapshot()?.password

    /**
     * Returns [snapshot], performing a one-time synchronous prime from [config] first if the
     * async collector (started in [init]) hasn't emitted yet. Safe to call redundantly/
     * concurrently: [primed] is checked under [primeLock] so at most one caller actually blocks
     * on `runBlocking` at a time.
     *
     * A failure during that synchronous prime (e.g. a transient DataStore/SecretStore exception)
     * is swallowed and logged rather than thrown: [url]/[password] must never throw to their
     * OkHttp-interceptor callers, and [primed] is deliberately left `false` on failure so a later
     * call — another synchronous attempt, or the resilient async collector — gets to try again.
     */
    private fun primedSnapshot(): ServerConfig? {
        if (!primed) {
            synchronized(primeLock) {
                if (!primed) {
                    try {
                        snapshot = runBlocking { config.first() }
                        primed = true
                    } catch (cause: Throwable) {
                        Log.w(
                            LOG_TAG,
                            "synchronous snapshot prime failed; leaving unprimed so a later " +
                                "call (sync retry, or the async collector) can try again",
                            cause,
                        )
                    }
                }
            }
        }
        return snapshot
    }
}

/**
 * See [ServerConfigRepository.save]'s KDoc for the exact sanitization rules; this is their
 * implementation.
 */
private fun sanitizeUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        throw InvalidServerConfigException("Server URL must not be empty")
    }

    val withScheme = when {
        trimmed.startsWith(HTTP_PREFIX, ignoreCase = true) ->
            HTTP_PREFIX + trimmed.substring(HTTP_PREFIX.length)
        trimmed.startsWith(HTTPS_PREFIX, ignoreCase = true) ->
            HTTPS_PREFIX + trimmed.substring(HTTPS_PREFIX.length)
        trimmed.contains("://") ->
            throw InvalidServerConfigException("Unsupported URL scheme: $trimmed")
        else -> HTTPS_PREFIX + trimmed
    }

    val sanitized = withScheme.trimEnd('/')

    // Validate via OkHttp's own URL parser rather than reinventing host/port validation. Its
    // string form isn't used as the persisted value below (it always normalizes back to a
    // trailing "/" root path, which is exactly what stripping trailing slashes above is meant to
    // avoid) — except through stripApiV1Suffix, which needs the parsed path segments anyway and
    // takes care to rebuild the string without reintroducing that trailing slash.
    val parsed = sanitized.toHttpUrlOrNull()
        ?: throw InvalidServerConfigException("Invalid server URL: $raw")

    return stripApiV1Suffix(parsed) ?: sanitized
}

/**
 * Users often paste the API base straight from BlueBubbles docs/setup screens (e.g.
 * `https://host/api/v1`), but
 * [DynamicBaseUrlInterceptor][app.rebubble.data.remote.api.DynamicBaseUrlInterceptor] already
 * prefixes every outgoing request with the Retrofit-configured `/api/v1/...` path, so storing
 * that suffix verbatim would double it to `/api/v1/api/v1/...`. If [parsedUrl]'s path ends with
 * the exact segments `api/v1` — optionally preceded by further segments, e.g. a legitimate
 * reverse-proxy prefix like `/bluebubbles` — this strips only that trailing `api/v1`, preserving
 * any earlier prefix. Returns null (meaning: keep the caller's already-sanitized string as-is)
 * when the path doesn't end that way.
 */
private fun stripApiV1Suffix(parsedUrl: HttpUrl): String? {
    val segments = parsedUrl.pathSegments.filter { it.isNotEmpty() }
    if (segments.size < 2) return null
    if (segments[segments.size - 2] != "api" || segments[segments.size - 1] != "v1") return null

    val builder = parsedUrl.newBuilder().encodedPath("/")
    segments.subList(0, segments.size - 2).forEach { builder.addPathSegment(it) }
    return builder.build().toString().trimEnd('/')
}
