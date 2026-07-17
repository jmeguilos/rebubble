package app.rebubble.ui.navigation

import android.net.Uri
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip coverage for [RebubbleRoutes.chat] encode → navigation-compose path-arg decode.
 *
 * Verifies navigation-common 2.8.5 [androidx.navigation.NavDeepLink] Uri.decodes path args, so
 * [app.rebubble.ui.navigation.RebubbleNavHost] must not call Uri.decode again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class RebubbleChatRouteNavTest {

    @Test
    fun `chat route with semicolon and plus round-trips through TestNavHostController`() {
        val guid = "iMessage;-;+15551234567"
        assertEquals(guid, navigateAndReadGuid(guid))
    }

    @Test
    fun `chat route with percent semicolon and plus round-trips without double-decode`() {
        val guid = "iMessage;-;+1555%test"
        val received = navigateAndReadGuid(guid)
        assertEquals(guid, received)
        // `%te` is valid hex, so a redundant Uri.decode in NavHost would corrupt this guid.
        assertTrue(
            "NavHost must not Uri.decode again; second decode changes the value",
            Uri.decode(received) != received,
        )
    }

    @Test
    fun `RebubbleRoutes chat encodes special characters in the path segment`() {
        val guid = "iMessage;-;+15551234567"
        val route = RebubbleRoutes.chat(guid)
        assertTrue(route.startsWith("chat/"))
        assertEquals(guid, Uri.decode(route.removePrefix("chat/")))
        assertTrue(route.contains("%3B") || route.contains("%2B"))
    }

    private fun navigateAndReadGuid(guid: String): String {
        val navController = TestNavHostController(ApplicationProvider.getApplicationContext())
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        navController.graph = navController.createGraph(startDestination = RebubbleRoutes.CHATS) {
            composable(RebubbleRoutes.CHATS) {}
            composable(
                route = RebubbleRoutes.CHAT,
                arguments = listOf(navArgument("guid") { type = NavType.StringType }),
            ) {}
        }
        navController.navigate(RebubbleRoutes.chat(guid))
        return navController.currentBackStackEntry!!.arguments!!.getString("guid")!!
    }
}
