package app.rebubble.ui.newchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rebubble.data.remote.api.ApiException
import app.rebubble.data.remote.api.AuthError
import app.rebubble.data.repo.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** iMessage/SMS toggle shown as a [androidx.compose.material3.SegmentedButton] pair. */
enum class NewChatService(val wireValue: String) {
    IMessage("iMessage"),
    Sms("SMS"),
}

data class NewChatUiState(
    val service: NewChatService = NewChatService.IMessage,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface NewChatEvent {
    data class NavigateToChat(val chatGuid: String) : NewChatEvent
}

/**
 * Basic recipient validation shared by [NewChatScreen]'s Send-button enablement and
 * [NewChatViewModel.send]'s defense-in-depth guard: non-blank, and either an email-ish address
 * (contains `@`) or a phone number with at least 7 digits once formatting (spaces, dashes,
 * parens, a leading `+`) is stripped. Deliberately loose -- the server (`getiMessageAddressFormat`,
 * chatInterface.ts `create()` line 315) does the real canonicalization; this is just enough to
 * keep an obviously-empty or single-digit entry from round-tripping to the server.
 */
fun isValidNewChatRecipient(input: String): Boolean {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.contains("@")) return true
    return trimmed.count { it.isDigit() } >= 7
}

@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewChatUiState())
    val uiState: StateFlow<NewChatUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<NewChatEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<NewChatEvent> = _events.asSharedFlow()

    fun onServiceSelected(service: NewChatService) {
        _uiState.update { it.copy(service = service) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * [recipient]/[message] are passed in directly from the screen's own local text-field state
     * (never round-tripped through [uiState] -- see [NewChatScreen]'s KDoc) so this is a plain
     * function call, not a state-driven one.
     */
    fun send(recipient: String, message: String) {
        if (_uiState.value.isSending) return
        val address = recipient.trim()
        val trimmedMessage = message.trim()
        if (!isValidNewChatRecipient(address) || trimmedMessage.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, errorMessage = null) }
            try {
                val guid = chatRepository.startChat(
                    addresses = listOf(address),
                    message = trimmedMessage,
                    service = _uiState.value.service.wireValue,
                )
                _uiState.update { it.copy(isSending = false) }
                _events.emit(NewChatEvent.NavigateToChat(guid))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthError) {
                _uiState.update {
                    it.copy(isSending = false, errorMessage = "Check your server password in Settings.")
                }
            } catch (e: ApiException) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        errorMessage = e.errorMessage ?: "Couldn't start the chat. Try again.",
                    )
                }
            } catch (e: IOException) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        errorMessage = "Couldn't reach the server. Check your connection and try again.",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSending = false, errorMessage = "Couldn't start the chat. Try again.")
                }
            }
        }
    }
}
