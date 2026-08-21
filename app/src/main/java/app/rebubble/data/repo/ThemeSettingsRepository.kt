package app.rebubble.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** The user's "App color" choice — see [ThemeSettingsRepository]'s KDoc. */
enum class AppColorMode {
    /** The card palette (`design/_tokens.css`) — [app.rebubble.ui.theme.RebubbleTheme]'s fallback
     *  `LightColorScheme`/`DarkColorScheme`. Default: this is what the approved design cards show. */
    REBUBBLE,

    /** Android 12+ wallpaper-derived Material You palette. */
    DYNAMIC,
}

private val APP_COLOR_MODE_KEY = stringPreferencesKey("app_color_mode")

/**
 * Persists the "App color" setting (Settings screen, T-D): "Rebubble" (the approved card palette,
 * default) vs. "Material You (dynamic)". Backed by the same `"server_config"` Preferences
 * DataStore [ServerConfigRepository] uses for the server URL/password/serverInfo — kept as its own
 * small repository rather than folded into [ServerConfigRepository] so that class's already-dense
 * KDoc (server connection state) doesn't also have to explain an unrelated UI preference.
 *
 * SDK-version gating of dynamic color itself still lives in
 * [app.rebubble.ui.theme.RebubbleTheme] (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`) — this
 * repository only persists the user's *choice*, so a pre-S device that somehow has `DYNAMIC`
 * persisted (e.g. a downgrade) still falls back to the fallback scheme rather than crashing.
 */
@Singleton
class ThemeSettingsRepository @Inject constructor(
    @param:Named("server_config") private val dataStore: DataStore<Preferences>,
) {

    /** Defaults to [AppColorMode.REBUBBLE] when unset — matches the approved design cards. */
    val appColorMode: Flow<AppColorMode> = dataStore.data.map { prefs ->
        when (prefs[APP_COLOR_MODE_KEY]) {
            AppColorMode.DYNAMIC.name -> AppColorMode.DYNAMIC
            else -> AppColorMode.REBUBBLE
        }
    }

    suspend fun setAppColorMode(mode: AppColorMode) {
        dataStore.edit { prefs -> prefs[APP_COLOR_MODE_KEY] = mode.name }
    }
}
