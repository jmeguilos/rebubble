package app.rebubble.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import app.rebubble.ui.chatlist.ChatListRoute
import app.rebubble.ui.onboarding.OnboardingRoute
import dagger.hilt.android.lifecycle.HiltViewModel
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
            )
        }
        composable(
            route = RebubbleRoutes.CHAT,
            arguments = listOf(navArgument("guid") { type = NavType.StringType }),
        ) { entry ->
            val guid = Uri.decode(entry.arguments?.getString("guid").orEmpty())
            ChatDetailPlaceholder(guid = guid)
        }
    }
}

/** T17 placeholder — T18 replaces with the real chat screen. */
@Composable
fun ChatDetailPlaceholder(
    guid: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = guid,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
