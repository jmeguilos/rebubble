package app.rebubble.ui.newchat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rebubble.ui.theme.ListSheetTopShape
import app.rebubble.ui.theme.RebubbleTheme
import kotlinx.coroutines.flow.collectLatest

private val CtaHeight = 56.dp
private val ScreenRhythm = 24.dp

@Composable
fun NewChatRoute(
    onBack: () -> Unit,
    onChatCreated: (String) -> Unit,
    viewModel: NewChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is NewChatEvent.NavigateToChat -> onChatCreated(event.chatGuid)
            }
        }
    }

    NewChatScreen(
        state = state,
        onBack = onBack,
        onServiceSelected = viewModel::onServiceSelected,
        onSend = viewModel::send,
    )
}

/**
 * "Start chat" flow: recipient + message + iMessage/SMS toggle, backed by [NewChatViewModel.send].
 *
 * Recipient/message are local `rememberSaveable` state, exactly like
 * [app.rebubble.ui.onboarding.OnboardingScreen]'s `ManualPane` -- binding a `TextField`'s `value`
 * to a collected `StateFlow` drops/reorders keystrokes under fast input, so [state] only supplies
 * [NewChatUiState.isSending]/[NewChatUiState.errorMessage]/[NewChatUiState.service]; the two text
 * fields are read directly off local state and passed to [onSend] as plain arguments, never
 * round-tripped through the ViewModel on every keystroke.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    state: NewChatUiState,
    onBack: () -> Unit,
    onServiceSelected: (NewChatService) -> Unit,
    onSend: (recipient: String, message: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var recipient by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }

    val canSend = isValidNewChatRecipient(recipient) && message.isNotBlank() && !state.isSending

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(text = "New message", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = ListSheetTopShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = ScreenRhythm, vertical = ScreenRhythm),
                ) {
                    OutlinedTextField(
                        value = recipient,
                        onValueChange = { recipient = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("To: phone number or email") },
                        singleLine = true,
                        enabled = !state.isSending,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            capitalization = KeyboardCapitalization.None,
                        ),
                    )
                    VerticalSpace(20.dp)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        NewChatService.entries.forEachIndexed { index, service ->
                            SegmentedButton(
                                selected = state.service == service,
                                onClick = { onServiceSelected(service) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = NewChatService.entries.size,
                                ),
                                enabled = !state.isSending,
                            ) {
                                Text(if (service == NewChatService.IMessage) "iMessage" else "SMS")
                            }
                        }
                    }
                    VerticalSpace(20.dp)
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Message") },
                        minLines = 3,
                        maxLines = 6,
                        enabled = !state.isSending,
                    )
                    if (state.errorMessage != null) {
                        VerticalSpace(12.dp)
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    VerticalSpace(ScreenRhythm)
                    Button(
                        onClick = { onSend(recipient, message) },
                        enabled = canSend,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CtaHeight),
                    ) {
                        if (state.isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Send")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalSpace(size: Dp) {
    Spacer(modifier = Modifier.size(size))
}

// region Previews

@Preview(showBackground = true, name = "New chat · empty · light")
@Composable
private fun NewChatEmptyLightPreview() {
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
        NewChatScreen(
            state = NewChatUiState(),
            onBack = {},
            onServiceSelected = {},
            onSend = { _, _ -> },
        )
    }
}

@Preview(showBackground = true, name = "New chat · empty · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewChatEmptyDarkPreview() {
    RebubbleTheme(darkTheme = true, dynamicColor = false) {
        NewChatScreen(
            state = NewChatUiState(),
            onBack = {},
            onServiceSelected = {},
            onSend = { _, _ -> },
        )
    }
}

@Preview(showBackground = true, name = "New chat · valid · light")
@Composable
private fun NewChatValidLightPreview() {
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
        NewChatScreen(
            state = NewChatUiState(service = NewChatService.Sms),
            onBack = {},
            onServiceSelected = {},
            onSend = { _, _ -> },
        )
    }
}

@Preview(showBackground = true, name = "New chat · sending · light")
@Composable
private fun NewChatSendingLightPreview() {
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
        NewChatScreen(
            state = NewChatUiState(isSending = true),
            onBack = {},
            onServiceSelected = {},
            onSend = { _, _ -> },
        )
    }
}

@Preview(showBackground = true, name = "New chat · sending · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewChatSendingDarkPreview() {
    RebubbleTheme(darkTheme = true, dynamicColor = false) {
        NewChatScreen(
            state = NewChatUiState(isSending = true),
            onBack = {},
            onServiceSelected = {},
            onSend = { _, _ -> },
        )
    }
}

@Preview(showBackground = true, name = "New chat · error · light")
@Composable
private fun NewChatErrorLightPreview() {
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
        NewChatScreen(
            state = NewChatUiState(errorMessage = "Couldn't reach the server. Check your connection and try again."),
            onBack = {},
            onServiceSelected = {},
            onSend = { _, _ -> },
        )
    }
}

@Preview(showBackground = true, name = "New chat · error · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewChatErrorDarkPreview() {
    RebubbleTheme(darkTheme = true, dynamicColor = false) {
        NewChatScreen(
            state = NewChatUiState(errorMessage = "Couldn't reach the server. Check your connection and try again."),
            onBack = {},
            onServiceSelected = {},
            onSend = { _, _ -> },
        )
    }
}

// endregion
