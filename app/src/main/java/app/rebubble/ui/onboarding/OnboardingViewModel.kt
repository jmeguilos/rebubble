package app.rebubble.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rebubble.data.remote.api.AuthError
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.apiCall
import app.rebubble.data.remote.dto.requests.MessageQueryRequest
import app.rebubble.data.repo.InvalidServerConfigException
import app.rebubble.data.repo.ServerConfigRepository
import app.rebubble.data.sync.SyncOutcome
import app.rebubble.data.sync.SyncStatusTracker
import app.rebubble.data.sync.SyncWatermarkStore
import app.rebubble.notifications.FcmSetupResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import javax.inject.Inject

/** First-sync reconcile seam — production binds to [app.rebubble.data.sync.Reconciler.reconcile]. */
fun interface OnboardingReconcileAction {
    suspend operator fun invoke(): SyncOutcome
}

/** FCM bootstrap seam — production binds to [app.rebubble.notifications.FirebaseBootstrapper.setup]. */
fun interface OnboardingFcmSetupAction {
    suspend operator fun invoke(): FcmSetupResult
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val serverConfigRepository: ServerConfigRepository,
    private val api: BlueBubblesApi,
    private val watermarkStore: SyncWatermarkStore,
    private val syncStatusTracker: SyncStatusTracker,
    private val reconcile: OnboardingReconcileAction,
    private val setupFcm: OnboardingFcmSetupAction,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Welcome)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<OnboardingEvent>(
        replay = 2,
        extraBufferCapacity = 2,
    )
    val events: SharedFlow<OnboardingEvent> = _events.asSharedFlow()

    private var lastUrl: String = ""
    private var lastPassword: String = ""

    fun showManualEntry() {
        _uiState.value = OnboardingUiState.ManualEntry()
    }

    fun updateManualUrl(url: String) {
        _uiState.update { state ->
            if (state is OnboardingUiState.ManualEntry) state.copy(url = url, errorMessage = null)
            else state
        }
    }

    fun updateManualPassword(password: String) {
        _uiState.update { state ->
            if (state is OnboardingUiState.ManualEntry) {
                state.copy(password = password, errorMessage = null)
            } else {
                state
            }
        }
    }

    fun backToWelcome() {
        _uiState.value = OnboardingUiState.Welcome
    }

    fun connectManual(url: String, password: String) {
        viewModelScope.launch { validateAndContinue(url, password) }
    }

    fun submitManual() {
        val state = _uiState.value as? OnboardingUiState.ManualEntry ?: return
        connectManual(state.url, state.password)
    }

    fun retryConnect() {
        val (url, password) = when (val state = _uiState.value) {
            is OnboardingUiState.PasswordError -> state.url to state.password
            is OnboardingUiState.Unreachable -> state.url to state.password
            else -> lastUrl to lastPassword
        }
        if (url.isBlank() || password.isBlank()) return
        viewModelScope.launch { validateAndContinue(url, password) }
    }

    fun onQrPayload(raw: String) {
        viewModelScope.launch {
            val parsed = parseQrPayload(raw) ?: run {
                _uiState.value = OnboardingUiState.QrError(OnboardingCopy.QR_MALFORMED)
                return@launch
            }
            validateAndContinue(url = parsed.second, password = parsed.first)
        }
    }

    fun dismissQrError() {
        _uiState.value = OnboardingUiState.Welcome
    }

    private suspend fun validateAndContinue(url: String, password: String) {
        lastUrl = url
        lastPassword = password
        _uiState.value = OnboardingUiState.Validating
        try {
            serverConfigRepository.save(url, password)
            serverConfigRepository.refreshServerInfo()
        } catch (_: AuthError) {
            _uiState.value = OnboardingUiState.PasswordError(url = url, password = password)
            return
        } catch (_: IOException) {
            _uiState.value = OnboardingUiState.Unreachable(url = url, password = password)
            return
        } catch (_: InvalidServerConfigException) {
            _uiState.value = OnboardingUiState.Unreachable(url = url, password = password)
            return
        } catch (_: Exception) {
            _uiState.value = OnboardingUiState.Unreachable(url = url, password = password)
            return
        }

        _uiState.value = OnboardingUiState.Syncing
        runFirstSync()

        val fcm = setupFcm()
        val limited = fcm is FcmSetupResult.Failure
        _uiState.value = OnboardingUiState.Done(notificationsLimited = limited)
        _events.emit(OnboardingEvent.RequestNotificationPermission)
        _events.emit(OnboardingEvent.NavigateToChats)
    }

    private suspend fun runFirstSync() {
        val maxRowId = queryMaxRowId()
        watermarkStore.initializeIfAbsent(maxRowId)
        syncStatusTracker.track { reconcile() }
    }

    private suspend fun queryMaxRowId(): Long {
        val page = apiCall {
            api.queryMessages(
                MessageQueryRequest(
                    sort = "DESC",
                    limit = 1,
                    where = emptyList(),
                ),
            )
        }
        return page.firstOrNull()?.originalRowId ?: 0L
    }

    companion object {
        private val qrJson = Json { ignoreUnknownKeys = true }

        /**
         * BlueBubbles QR payload: JSON array `[password, serverUrl]` (password first).
         * Returns null when malformed.
         */
        fun parseQrPayload(raw: String): Pair<String, String>? {
            return try {
                val array = qrJson.parseToJsonElement(raw.trim()).jsonArray
                if (array.size < 2) return null
                val password = array[0].jsonPrimitive.content
                val serverUrl = array[1].jsonPrimitive.content
                if (password.isBlank() || serverUrl.isBlank()) return null
                password to serverUrl
            } catch (_: Exception) {
                null
            }
        }
    }
}
