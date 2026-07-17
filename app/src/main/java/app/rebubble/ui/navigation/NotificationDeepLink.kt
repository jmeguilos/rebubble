package app.rebubble.ui.navigation

import android.content.Intent
import androidx.navigation.NavController
import app.rebubble.notifications.MessageNotifier

/** Reads [MessageNotifier.EXTRA_CHAT_GUID] from a notification launch / onNewIntent Intent. */
fun chatGuidFromIntent(intent: Intent?): String? =
    intent?.getStringExtra(MessageNotifier.EXTRA_CHAT_GUID)?.takeIf { it.isNotBlank() }

/**
 * Opens [chatGuid] so Back lands on the chat list.
 *
 * No-op when [startDestination] is not [RebubbleRoutes.CHATS] (un-onboarded cold start).
 */
fun navigateNotificationDeepLink(
    navController: NavController,
    chatGuid: String,
    startDestination: String,
) {
    if (startDestination != RebubbleRoutes.CHATS) return
    navController.navigate(RebubbleRoutes.chat(chatGuid)) {
        popUpTo(RebubbleRoutes.CHATS) { inclusive = false }
        launchSingleTop = true
    }
}
