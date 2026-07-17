package app.rebubble.data.repo

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
 * ### The [ServerCredentialsProvider] seam and its cold-start caveat
 * [GuidAuthInterceptor][app.rebubble.data.remote.api.GuidAuthInterceptor] and
 * [DynamicBaseUrlInterceptor][app.rebubble.data.remote.api.DynamicBaseUrlInterceptor] call
 * [url]/[password] synchronously on every request — they can't suspend to read DataStore/
 * EncryptedSharedPreferences per call. So this repository keeps an in-memory [ServerConfig]
 * snapshot ([snapshot]) that a background collector refreshes from [config] on every emission.
 *
 * That collector starts in [init], but its first emission hasn't necessarily landed by the time
 * [url]/[password] are first called (e.g. very early in app startup) — until it has, both return
 * `null` even if a server *is* already configured on disk. Any HTTP request made in that narrow
 * window fails with a 401 that [app.rebubble.data.remote.api.apiCall] maps to
 * [app.rebubble.data.remote.api.AuthError]. This is considered acceptable: the window is a
 * DataStore-first-read race measured in milliseconds, any caller affected can simply retry, and
 * it never actually happens pre-onboarding (there is genuinely nothing configured yet, so
 * returning null is the correct answer, not a race artifact).
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

    init {
        repositoryScope.launch {
            config.collect { snapshot = it }
        }
    }

    /**
     * Sanitizes and persists the server URL + password.
     *
     * URL sanitization: trims surrounding whitespace; defaults the scheme to `https://` when the
     * input has none (a bare `host:port` becomes `https://host:port`); an explicit `http://` is
     * preserved as-is (never upgraded to `https://`); trailing slashes are stripped. Throws
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

    /** See the cold-start caveat in this class's KDoc. */
    override fun url(): String? = snapshot?.url

    /** See the cold-start caveat in this class's KDoc. */
    override fun password(): String? = snapshot?.password
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
    // string form isn't used as the persisted value (it always normalizes back to a trailing
    // "/" root path, which is exactly what stripping trailing slashes above is meant to avoid).
    if (sanitized.toHttpUrlOrNull() == null) {
        throw InvalidServerConfigException("Invalid server URL: $raw")
    }

    return sanitized
}
