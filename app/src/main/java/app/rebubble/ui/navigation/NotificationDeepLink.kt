package app.rebubble.ui.navigation

import android.content.Intent
import androidx.navigation.NavController
import app.rebubble.notifications.MessageNotifier
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Reads [MessageNotifier.EXTRA_CHAT_GUID] from a notification launch / onNewIntent Intent,
 * then strips the extra so Activity recreation cannot re-fire the same deep link.
 */
fun chatGuidFromIntent(intent: Intent?): String? {
    if (intent == null) return null
    if (!intent.hasExtra(MessageNotifier.EXTRA_CHAT_GUID)) return null
    val guid = intent.getStringExtra(MessageNotifier.EXTRA_CHAT_GUID)?.takeIf { it.isNotBlank() }
    intent.removeExtra(MessageNotifier.EXTRA_CHAT_GUID)
    return guid
}

/**
 * Opens [chatGuid] so Back lands on the chat list.
 *
 * No-op when the current back-stack route is [RebubbleRoutes.ONBOARDING]
 * (guid must be dropped by the caller, not queued).
 */
fun navigateNotificationDeepLink(
    navController: NavController,
    chatGuid: String,
) {
    if (navController.currentDestination?.route == RebubbleRoutes.ONBOARDING) return
    navController.navigate(RebubbleRoutes.chat(chatGuid)) {
        popUpTo(RebubbleRoutes.CHATS) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * Consumes one pending notification guid: navigates when CHATS is reachable,
 * otherwise drops it. Always clears [pendingDeepLinkChatGuid].
 */
fun consumePendingNotificationDeepLink(
    navController: NavController,
    pendingDeepLinkChatGuid: MutableStateFlow<String?>,
) {
    val guid = pendingDeepLinkChatGuid.value ?: return
    navigateNotificationDeepLink(navController, guid)
    pendingDeepLinkChatGuid.value = null
}
