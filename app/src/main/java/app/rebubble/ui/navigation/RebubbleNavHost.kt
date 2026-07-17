package app.rebubble.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.rebubble.data.repo.ServerConfigRepository
import app.rebubble.ui.chat.ChatRoute
import app.rebubble.ui.chatlist.ChatListRoute
import app.rebubble.ui.onboarding.OnboardingRoute
import app.rebubble.ui.settings.SettingsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import javax.inject.Inject

@HiltViewModel
class StartDestinationViewModel @Inject constructor(
    serverConfigRepository: ServerConfigRepository,
) : ViewModel() {
    /** Null while the first config emission is pending; then a locked start route (never updates). */
    val startDestination: StateFlow<String?> = serverConfigRepository.config
        .map { startDestinationForConfig(it) }
        .take(1)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun RebubbleNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestinationViewModel: StartDestinationViewModel = hiltViewModel(),
    pendingDeepLinkChatGuid: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    val startDestination by startDestinationViewModel.startDestination.collectAsStateWithLifecycle()

    if (startDestination == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    // Consume notification deep links only after start destination is known. Un-onboarded
    // cold starts (ONBOARDING) drop the pending guid so it cannot fire after onboarding.
    val resolvedStart = startDestination
    LaunchedEffect(resolvedStart) {
        if (resolvedStart != RebubbleRoutes.CHATS) {
            pendingDeepLinkChatGuid.value = null
            return@LaunchedEffect
        }
        pendingDeepLinkChatGuid.collect { guid ->
            if (guid == null) return@collect
            navigateNotificationDeepLink(
                navController = navController,
                chatGuid = guid,
                startDestination = resolvedStart,
            )
            pendingDeepLinkChatGuid.value = null
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination!!,
        modifier = modifier,
    ) {
        composable(RebubbleRoutes.ONBOARDING) {
            OnboardingRoute(
                onNavigateToChats = {
                    navController.navigate(RebubbleRoutes.CHATS) {
                        popUpTo(RebubbleRoutes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(RebubbleRoutes.CHATS) {
            ChatListRoute(
                onChatClick = { guid ->
                    navController.navigate(RebubbleRoutes.chat(guid))
                },
                onSettingsClick = {
                    navController.navigate(RebubbleRoutes.SETTINGS)
                },
            )
        }
        composable(
            route = RebubbleRoutes.CHAT,
            arguments = listOf(navArgument("guid") { type = NavType.StringType }),
        ) {
            ChatRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable(RebubbleRoutes.SETTINGS) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
