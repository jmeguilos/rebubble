package app.rebubble.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rebubble.ui.theme.RebubbleTheme
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.flow.collectLatest

@Composable
fun OnboardingRoute(
    onNavigateToChats: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            viewModel.onQrPayload(contents)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* denial is fine — continue */ }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                OnboardingEvent.NavigateToChats -> onNavigateToChats()
                OnboardingEvent.RequestNotificationPermission -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }

    LaunchedEffect(state) {
        val done = state as? OnboardingUiState.Done ?: return@LaunchedEffect
        if (done.notificationsLimited) {
            snackbarHostState.showSnackbar(OnboardingCopy.NOTIFICATIONS_LIMITED)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        OnboardingScreen(
            state = state,
            onScanQr = { qrLauncher.launch(rebubbleQrScanOptions()) },
            onEnterManual = viewModel::showManualEntry,
            onUrlChange = viewModel::updateManualUrl,
            onPasswordChange = viewModel::updateManualPassword,
            onConnect = viewModel::submitManual,
            onRetry = viewModel::retryConnect,
            onBack = viewModel::backToWelcome,
            onDismissQrError = viewModel::dismissQrError,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onScanQr: () -> Unit,
    onEnterManual: () -> Unit,
    onUrlChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onDismissQrError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface,
        ),
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradient),
    ) {
        AnimatedContent(
            targetState = state,
            contentKey = { screen ->
                when (screen) {
                    OnboardingUiState.Welcome,
                    is OnboardingUiState.QrError,
                    -> "welcome"
                    is OnboardingUiState.ManualEntry -> "manual"
                    OnboardingUiState.Validating -> "validating"
                    is OnboardingUiState.PasswordError,
                    is OnboardingUiState.Unreachable,
                    -> "connect_error"
                    OnboardingUiState.Syncing -> "syncing"
                    is OnboardingUiState.SyncError -> "sync_error"
                    is OnboardingUiState.Done -> "done"
                }
            },
            transitionSpec = {
                val fadeSpec = spring<Float>(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
                val slideSpec = spring<IntOffset>(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
                (fadeIn(animationSpec = fadeSpec) +
                    slideInVertically(animationSpec = slideSpec) { it / 8 }) togetherWith
                    (fadeOut() + slideOutVertically { -it / 12 })
            },
            label = "onboarding",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            when (current) {
                OnboardingUiState.Welcome,
                is OnboardingUiState.QrError,
                -> WelcomePane(
                    qrError = (current as? OnboardingUiState.QrError)?.message,
                    onScanQr = onScanQr,
                    onEnterManual = onEnterManual,
                    onDismissQrError = onDismissQrError,
                )

                is OnboardingUiState.ManualEntry -> ManualPane(
                    state = current,
                    onUrlChange = onUrlChange,
                    onPasswordChange = onPasswordChange,
                    onConnect = onConnect,
                    onBack = onBack,
                )

                OnboardingUiState.Validating -> StatusPane(
                    title = OnboardingCopy.SCREEN_TITLE,
                    body = "Checking your server…",
                    showProgress = true,
                )

                is OnboardingUiState.PasswordError -> ErrorPane(
                    message = current.message,
                    onRetry = onRetry,
                    onBack = onBack,
                )

                is OnboardingUiState.Unreachable -> ErrorPane(
                    message = current.message,
                    onRetry = onRetry,
                    onBack = onBack,
                )

                OnboardingUiState.Syncing -> StatusPane(
                    title = OnboardingCopy.SYNCING,
                    body = null,
                    showProgress = true,
                )

                is OnboardingUiState.SyncError -> ErrorPane(
                    message = current.message,
                    onRetry = onRetry,
                    onBack = onBack,
                )

                is OnboardingUiState.Done -> StatusPane(
                    title = "You're connected",
                    body = if (current.notificationsLimited) {
                        OnboardingCopy.NOTIFICATIONS_LIMITED
                    } else {
                        null
                    },
                    showProgress = false,
                )
            }
        }
    }
}

@Composable
private fun VerticalSpace(size: Dp) {
    Spacer(modifier = Modifier.size(size))
}

@Composable
private fun WelcomePane(
    qrError: String?,
    onScanQr: () -> Unit,
    onEnterManual: () -> Unit,
    onDismissQrError: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        BrandMark()
        VerticalSpace(28.dp)
        Text(
            text = OnboardingCopy.BRAND,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        VerticalSpace(12.dp)
        Text(
            text = OnboardingCopy.SCREEN_TITLE,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        VerticalSpace(8.dp)
        Text(
            text = OnboardingCopy.SCREEN_SUBTITLE,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (qrError != null) {
            VerticalSpace(16.dp)
            Text(
                text = qrError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onDismissQrError) {
                Text(OnboardingCopy.BACK)
            }
        }
        VerticalSpace(36.dp)
        Button(
            onClick = onScanQr,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Text(OnboardingCopy.SCAN_QR)
        }
        VerticalSpace(12.dp)
        OutlinedButton(
            onClick = onEnterManual,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Text(OnboardingCopy.ENTER_MANUAL)
        }
    }
}

@Composable
private fun ManualPane(
    state: OnboardingUiState.ManualEntry,
    onUrlChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    // Text fields must own synchronous local state — binding value to a StateFlow
    // drops/reorders keystrokes under fast input / UIAutomator (Compose async text bug).
    // Seed once per ManualEntry visit; leaving the pane disposes this composition so a
    // later re-entry (QR prefill / showManualEntry) reseeds from uiState.
    var url by rememberSaveable { mutableStateOf(state.url) }
    var password by rememberSaveable { mutableStateOf(state.password) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = OnboardingCopy.ENTER_MANUAL,
            style = MaterialTheme.typography.headlineSmall,
        )
        VerticalSpace(20.dp)
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                onUrlChange(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(OnboardingCopy.URL_LABEL) },
            singleLine = true,
            enabled = !state.isSubmitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        VerticalSpace(12.dp)
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                onPasswordChange(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(OnboardingCopy.PASSWORD_LABEL) },
            singleLine = true,
            enabled = !state.isSubmitting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        state.errorMessage?.let { message ->
            VerticalSpace(12.dp)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        VerticalSpace(24.dp)
        Button(
            onClick = onConnect,
            enabled = !state.isSubmitting && url.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(OnboardingCopy.CONNECT)
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(OnboardingCopy.BACK)
        }
    }
}

@Composable
private fun StatusPane(
    title: String,
    body: String?,
    showProgress: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showProgress) {
            // M3 1.4.0: CircularWavyProgressIndicator / MotionScheme are not public yet.
            CircularProgressIndicator(modifier = Modifier.size(56.dp), strokeWidth = 4.dp)
            VerticalSpace(24.dp)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            VerticalSpace(8.dp)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorPane(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        VerticalSpace(24.dp)
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(OnboardingCopy.RETRY)
        }
        TextButton(onClick = onBack) {
            Text(OnboardingCopy.BACK)
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "R",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Preview(showBackground = true, name = "Welcome")
@Composable
private fun WelcomePreview() {
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

@Preview(showBackground = true, name = "Manual")
@Composable
private fun ManualPreview() {
    RebubbleTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState.ManualEntry(url = "https://mac.local:1234"),
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

@Preview(showBackground = true, name = "Syncing")
@Composable
private fun SyncingPreview() {
    RebubbleTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState.Syncing,
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

@Preview(showBackground = true, name = "Password error")
@Composable
private fun PasswordErrorPreview() {
    RebubbleTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState.PasswordError(url = "https://h", password = "x"),
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

@Preview(showBackground = true, name = "Qr error")
@Composable
private fun QrErrorPreview() {
    RebubbleTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState.QrError(),
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

@Preview(showBackground = true, name = "Validating")
@Composable
private fun ValidatingPreview() {
    RebubbleTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState.Validating,
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

@Preview(showBackground = true, name = "Unreachable")
@Composable
private fun UnreachablePreview() {
    RebubbleTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState.Unreachable(url = "https://h", password = "x"),
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

@Preview(showBackground = true, name = "Done")
@Composable
private fun DonePreview() {
    RebubbleTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState.Done(),
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

@Preview(showBackground = true, name = "Done notifications limited")
@Composable
private fun DoneNotificationsLimitedPreview() {
    RebubbleTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState.Done(notificationsLimited = true),
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

@Preview(showBackground = true, name = "Sync error")
@Composable
private fun SyncErrorPreview() {
    RebubbleTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState.SyncError(),
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
