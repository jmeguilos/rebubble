package app.rebubble

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
import app.rebubble.ui.onboarding.OnboardingScreen
import app.rebubble.ui.onboarding.OnboardingUiState
import app.rebubble.ui.theme.RebubbleTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RebubbleTheme {
                RebubbleApp()
            }
        }
    }
}

@Composable
fun RebubbleApp() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold { innerPadding ->
            RebubbleNavHost(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
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
