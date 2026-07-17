package app.rebubble.ui.navigation

import android.content.Intent
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import app.rebubble.notifications.MessageNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class NotificationDeepLinkTest {

    @Test
    fun `chatGuidFromIntent returns extra when present`() {
        val guid = "iMessage;-;+15551234567"
        val intent = Intent().putExtra(MessageNotifier.EXTRA_CHAT_GUID, guid)
        assertEquals(guid, chatGuidFromIntent(intent))
    }

    @Test
    fun `chatGuidFromIntent returns null when extra missing`() {
        assertNull(chatGuidFromIntent(Intent()))
        assertNull(chatGuidFromIntent(null))
    }

    @Test
    fun `chatGuidFromIntent strips extra so second read returns null`() {
        val guid = "iMessage;-;+15551234567"
        val intent = Intent().putExtra(MessageNotifier.EXTRA_CHAT_GUID, guid)

        assertEquals(guid, chatGuidFromIntent(intent))
        assertFalse(intent.hasExtra(MessageNotifier.EXTRA_CHAT_GUID))
        assertNull(chatGuidFromIntent(intent))
    }

    @Test
    fun `deep link to chat with special-char guid produces chats then chat back stack`() {
        val guid = "iMessage;-;+15551234567"
        val navController = testNav(startDestination = RebubbleRoutes.CHATS)

        navigateNotificationDeepLink(
            navController = navController,
            chatGuid = guid,
        )

        assertEquals(RebubbleRoutes.CHAT, navController.currentDestination?.route)
        assertEquals(guid, navController.currentBackStackEntry!!.arguments!!.getString("guid"))
        assertEquals(RebubbleRoutes.CHATS, navController.previousBackStackEntry?.destination?.route)
    }

    @Test
    fun `deep link does not navigate when current route is onboarding`() {
        val navController = testNav(startDestination = RebubbleRoutes.ONBOARDING)

        navigateNotificationDeepLink(
            navController = navController,
            chatGuid = "iMessage;-;+15551234567",
        )

        assertEquals(RebubbleRoutes.ONBOARDING, navController.currentDestination?.route)
        assertNull(navController.previousBackStackEntry)
    }

    @Test
    fun `warm deep link dropped on onboarding then navigates after reaching chats`() {
        val guidWhileOnboarding = "iMessage;-;+15550000001"
        val guidAfterOnboarding = "iMessage;-;+15550000002"
        val navController = testNav(startDestination = RebubbleRoutes.ONBOARDING)
        val pending = MutableStateFlow<String?>(guidWhileOnboarding)

        consumePendingNotificationDeepLink(navController, pending)

        assertEquals(RebubbleRoutes.ONBOARDING, navController.currentDestination?.route)
        assertNull(pending.value)

        navController.navigate(RebubbleRoutes.CHATS) {
            popUpTo(RebubbleRoutes.ONBOARDING) { inclusive = true }
        }
        pending.value = guidAfterOnboarding

        consumePendingNotificationDeepLink(navController, pending)

        assertEquals(RebubbleRoutes.CHAT, navController.currentDestination?.route)
        assertEquals(
            guidAfterOnboarding,
            navController.currentBackStackEntry!!.arguments!!.getString("guid"),
        )
        assertEquals(RebubbleRoutes.CHATS, navController.previousBackStackEntry?.destination?.route)
        assertNull(pending.value)
    }

    private fun testNav(startDestination: String): TestNavHostController {
        val navController = TestNavHostController(ApplicationProvider.getApplicationContext())
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        navController.graph = navController.createGraph(startDestination = startDestination) {
            composable(RebubbleRoutes.ONBOARDING) {}
            composable(RebubbleRoutes.CHATS) {}
            composable(
                route = RebubbleRoutes.CHAT,
                arguments = listOf(navArgument("guid") { type = NavType.StringType }),
            ) {}
            composable(RebubbleRoutes.SETTINGS) {}
        }
        return navController
    }
}
