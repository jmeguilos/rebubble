package app.rebubble.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the chat guid currently visible in the conversation UI (set by T18).
 * [MessageNotifier] suppresses notifications for [current]'s value.
 */
@Singleton
class ActiveChatTracker @Inject constructor() {
    val current: MutableStateFlow<String?> = MutableStateFlow(null)
}
