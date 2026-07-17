package app.rebubble.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rebubble.data.sync.SyncStatus
import app.rebubble.ui.theme.RebubbleTheme
import kotlinx.coroutines.flow.collectLatest

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.onScreenOpen()
    }

    LaunchedEffect(state.snackbarMessage) {
        val message = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearSnackbar()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is SettingsEvent.ShareLogs -> {
                    context.startActivity(Intent.createChooser(event.intent, "Export logs"))
                }
                is SettingsEvent.OpenUrl -> {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.url)))
                }
            }
        }
    }

    SettingsScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onTestConnection = viewModel::testConnection,
        onSetupNotifications = viewModel::setupNotificationsAgain,
        onSyncNow = viewModel::syncNow,
        onExportLogs = viewModel::exportLogs,
        onOpenGitHub = viewModel::openGitHub,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onTestConnection: () -> Unit,
    onSetupNotifications: () -> Unit,
    onSyncNow: () -> Unit,
    onExportLogs: () -> Unit,
    onOpenGitHub: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Server")
            ListItem(
                headlineContent = { Text("Server URL") },
                supportingContent = {
                    Text(state.serverUrl ?: "Not configured")
                },
            )
            ListItem(
                headlineContent = { Text("Server version") },
                supportingContent = {
                    Text(state.serverVersion ?: "Unknown")
                },
            )
            ListItem(
                headlineContent = { Text("macOS version") },
                supportingContent = {
                    Text(state.osVersion ?: "Unknown")
                },
            )
            ListItem(
                headlineContent = { Text("Private API") },
                supportingContent = {
                    Text(
                        when (state.privateApi) {
                            true -> "Enabled"
                            false -> "Disabled"
                            null -> "Unknown"
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text("Test connection") },
                supportingContent = {
                    if (state.connectionBusy) {
                        Text("Checking…")
                    } else {
                        Text("Ping the BlueBubbles server")
                    }
                },
                trailingContent = {
                    if (state.connectionBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.connectionBusy, onClick = onTestConnection),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("Notifications")
            ListItem(
                headlineContent = { Text("Set up notifications again") },
                supportingContent = {
                    Text(
                        if (state.notificationsBusy) {
                            "Setting up…"
                        } else {
                            "Re-register this device for push"
                        },
                    )
                },
                trailingContent = {
                    if (state.notificationsBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.notificationsBusy, onClick = onSetupNotifications),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("Diagnostics")
            ListItem(
                headlineContent = { Text("Sync now") },
                supportingContent = {
                    Text(syncStatusLabel(state.syncStatus))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSyncNow),
            )
            ListItem(
                headlineContent = { Text("Export logs") },
                supportingContent = {
                    Text("Share the recent diagnostic buffer")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExportLogs),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("About")
            ListItem(
                headlineContent = { Text("App version") },
                supportingContent = { Text(state.appVersion) },
            )
            ListItem(
                headlineContent = { Text("GitHub") },
                supportingContent = { Text("Open the Rebubble repository") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenGitHub),
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun syncStatusLabel(status: SyncStatus): String = when (status) {
    SyncStatus.Idle -> "Idle"
    SyncStatus.Syncing -> "Syncing…"
    is SyncStatus.Error -> "Error — ${status.message}"
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    RebubbleTheme(dynamicColor = false) {
        SettingsScreen(
            state = SettingsUiState(
                serverUrl = "https://bb.example.com",
                serverVersion = "1.9.0",
                osVersion = "14.0",
                privateApi = true,
                appVersion = "0.1.0",
            ),
            onBack = {},
            onTestConnection = {},
            onSetupNotifications = {},
            onSyncNow = {},
            onExportLogs = {},
            onOpenGitHub = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenBusyPreview() {
    RebubbleTheme(dynamicColor = false) {
        SettingsScreen(
            state = SettingsUiState(
                serverUrl = "https://bb.example.com",
                connectionBusy = true,
                notificationsBusy = true,
                syncStatus = SyncStatus.Syncing,
                appVersion = "0.1.0",
            ),
            onBack = {},
            onTestConnection = {},
            onSetupNotifications = {},
            onSyncNow = {},
            onExportLogs = {},
            onOpenGitHub = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenErrorPreview() {
    RebubbleTheme(dynamicColor = false) {
        SettingsScreen(
            state = SettingsUiState(
                serverUrl = "https://bb.example.com",
                privateApi = false,
                syncStatus = SyncStatus.Error("timeout", at = 0L),
                snackbarMessage = SettingsCopy.CONNECTION_FAILED,
                appVersion = "0.1.0",
            ),
            onBack = {},
            onTestConnection = {},
            onSetupNotifications = {},
            onSyncNow = {},
            onExportLogs = {},
            onOpenGitHub = {},
        )
    }
}
