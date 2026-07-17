package app.rebubble.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.rebubble.data.logging.RingBufferLogger
import app.rebubble.data.logging.shareLogsIntent
import app.rebubble.data.logging.writeLogSnapshot
import app.rebubble.data.repo.ServerConfigRepository
import app.rebubble.data.sync.SyncScheduling
import app.rebubble.data.sync.SyncStatus
import app.rebubble.data.sync.SyncStatusTracker
import app.rebubble.notifications.FcmSetupResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FCM re-setup seam — production binds to [app.rebubble.notifications.FirebaseBootstrapper.setup]. */
fun interface SettingsFcmSetupAction {
    suspend operator fun invoke(): FcmSetupResult
}

fun interface SettingsAppVersionProvider {
    operator fun invoke(): String
}

object SettingsCopy {
    const val CONNECTION_FAILED = "Couldn't connect to the server."
    const val NOTIFICATIONS_READY = "Notifications ready"
    const val NOTIFICATIONS_FAILED = "Couldn't set up notifications — using periodic sync."
    const val GITHUB_URL = "https://github.com/jmeguilos/rebubble"
}

data class SettingsUiState(
    val serverUrl: String? = null,
    val serverVersion: String? = null,
    val osVersion: String? = null,
    val privateApi: Boolean? = null,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val connectionBusy: Boolean = false,
    val notificationsBusy: Boolean = false,
    val snackbarMessage: String? = null,
    val appVersion: String = "",
)

sealed interface SettingsEvent {
    data class ShareLogs(val intent: Intent) : SettingsEvent
    data class OpenUrl(val url: String) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val serverConfigRepository: ServerConfigRepository,
    private val syncStatusTracker: SyncStatusTracker,
    private val logger: RingBufferLogger,
    @param:ApplicationContext private val appContext: Context,
    private val setupFcm: SettingsFcmSetupAction,
    appVersionProvider: SettingsAppVersionProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(appVersion = appVersionProvider()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(
                serverConfigRepository.config,
                serverConfigRepository.serverInfo,
                syncStatusTracker.status,
            ) { config, info, sync ->
                Triple(config, info, sync)
            }.collect { (config, info, sync) ->
                _uiState.update { state ->
                    state.copy(
                        serverUrl = config?.url,
                        serverVersion = info?.serverVersion,
                        osVersion = info?.osVersion,
                        privateApi = info?.privateApi,
                        syncStatus = sync,
                    )
                }
            }
        }
    }

    /** Non-blocking refresh of cached server info when the settings screen opens. */
    fun onScreenOpen() {
        viewModelScope.launch {
            try {
                serverConfigRepository.refreshServerInfo()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep cached values; test-connection surfaces failures explicitly.
            }
        }
    }

    fun testConnection() {
        if (_uiState.value.connectionBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(connectionBusy = true, snackbarMessage = null) }
            try {
                val info = serverConfigRepository.refreshServerInfo()
                val version = info.serverVersion ?: "?"
                _uiState.update {
                    it.copy(
                        connectionBusy = false,
                        snackbarMessage = "Connected — server v$version",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        connectionBusy = false,
                        snackbarMessage = SettingsCopy.CONNECTION_FAILED,
                    )
                }
            }
        }
    }

    fun setupNotificationsAgain() {
        if (_uiState.value.notificationsBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(notificationsBusy = true, snackbarMessage = null) }
            val result = try {
                setupFcm()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FcmSetupResult.Failure(FcmSetupResult.Failure.Step.FETCH_CLIENT, e)
            }
            val message = when (result) {
                is FcmSetupResult.Success -> SettingsCopy.NOTIFICATIONS_READY
                is FcmSetupResult.Failure -> SettingsCopy.NOTIFICATIONS_FAILED
            }
            _uiState.update {
                it.copy(notificationsBusy = false, snackbarMessage = message)
            }
        }
    }

    fun syncNow() {
        SyncScheduling.enqueueExpedited(WorkManager.getInstance(appContext))
    }

    fun exportLogs() {
        viewModelScope.launch {
            val file = writeLogSnapshot(appContext, logger.snapshot())
            _events.emit(SettingsEvent.ShareLogs(shareLogsIntent(appContext, file)))
        }
    }

    fun openGitHub() {
        viewModelScope.launch {
            _events.emit(SettingsEvent.OpenUrl(SettingsCopy.GITHUB_URL))
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
