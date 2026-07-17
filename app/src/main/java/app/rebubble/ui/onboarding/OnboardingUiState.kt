package app.rebubble.ui.onboarding

/**
 * UI state for the onboarding flow. Screens are stateless — all state lives here.
 */
sealed interface OnboardingUiState {
    data object Welcome : OnboardingUiState

    data class ManualEntry(
        val url: String = "",
        val password: String = "",
        val isSubmitting: Boolean = false,
        val errorMessage: String? = null,
    ) : OnboardingUiState

    data class QrError(val message: String = OnboardingCopy.QR_MALFORMED) : OnboardingUiState

    data object Validating : OnboardingUiState

    data class PasswordError(
        val url: String,
        val password: String,
        val message: String = OnboardingCopy.PASSWORD_REJECTED,
    ) : OnboardingUiState

    data class Unreachable(
        val url: String,
        val password: String,
        val message: String = OnboardingCopy.UNREACHABLE,
    ) : OnboardingUiState

    data object Syncing : OnboardingUiState

    data class Done(
        val notificationsLimited: Boolean = false,
    ) : OnboardingUiState
}

/** One-shot events consumed by the UI (navigation / permission prompts). */
sealed interface OnboardingEvent {
    data object NavigateToChats : OnboardingEvent
    data object RequestNotificationPermission : OnboardingEvent
}
