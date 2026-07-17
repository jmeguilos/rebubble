package app.rebubble

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.rebubble.ui.navigation.RebubbleNavHost
import app.rebubble.ui.navigation.chatGuidFromIntent
import app.rebubble.ui.onboarding.OnboardingScreen
import app.rebubble.ui.onboarding.OnboardingUiState
import app.rebubble.ui.theme.RebubbleTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Pending notification chat guid; consumed by [RebubbleNavHost] after start destination resolves. */
    private val pendingDeepLinkChatGuid = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLinkChatGuid.value = chatGuidFromIntent(intent)
        enableEdgeToEdge()
        setContent {
            RebubbleTheme {
                RebubbleApp(pendingDeepLinkChatGuid = pendingDeepLinkChatGuid)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLinkChatGuid.value = chatGuidFromIntent(intent)
    }
}

@Composable
fun RebubbleApp(
    pendingDeepLinkChatGuid: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold { innerPadding ->
            RebubbleNavHost(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                pendingDeepLinkChatGuid = pendingDeepLinkChatGuid,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun RebubbleAppPreview() {
    RebubbleTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState.Welcome,
            onScanQr = {},
            onEnterManual = {},
            onUrlChange = {},
            onPasswordChange = {},
            onConnect = {},
            onRetry = {},
            onBack = {},
            onDismissQrError = {},
        )
    }
}
