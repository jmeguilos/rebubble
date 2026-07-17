package app.rebubble.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rebubble.data.sync.SyncStatus
import app.rebubble.ui.theme.ListSheetTopShape
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
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        .padding(top = 8.dp, bottom = 24.dp),
                ) {
                    SectionHeader("Server")
                    SettingsRow(
                        headline = "Server URL",
                        supporting = state.serverUrl ?: "Not configured",
                        icon = Icons.Outlined.Dns,
                    )
                    SettingsRow(
                        headline = "Server version",
                        supporting = state.serverVersion ?: "Unknown",
                        icon = Icons.Outlined.Cloud,
                    )
                    SettingsRow(
                        headline = "macOS version",
                        supporting = state.osVersion ?: "Unknown",
                        icon = Icons.Outlined.PhoneAndroid,
                    )
                    SettingsRow(
                        headline = "Private API",
                        supporting = when (state.privateApi) {
                            true -> "Enabled"
                            false -> "Disabled"
                            null -> "Unknown"
                        },
                        icon = Icons.Outlined.VerifiedUser,
                    )
                    SettingsRow(
                        headline = "Test connection",
                        supporting = if (state.connectionBusy) {
                            "Checking…"
                        } else {
                            "Ping the BlueBubbles server"
                        },
                        icon = Icons.Outlined.Sync,
                        trailingBusy = state.connectionBusy,
                        onClick = if (!state.connectionBusy) onTestConnection else null,
                    )

                    SectionHeader("Notifications")
                    SettingsRow(
                        headline = "Set up notifications again",
                        supporting = if (state.notificationsBusy) {
                            "Setting up…"
                        } else {
                            "Re-register this device for push"
                        },
                        icon = Icons.Outlined.Notifications,
                        trailingBusy = state.notificationsBusy,
                        onClick = if (!state.notificationsBusy) onSetupNotifications else null,
                    )

                    SectionHeader("Diagnostics")
                    SettingsRow(
                        headline = "Sync now",
                        supporting = syncStatusLabel(state.syncStatus),
                        icon = Icons.Outlined.BugReport,
                        onClick = onSyncNow,
                    )
                    SettingsRow(
                        headline = "Export logs",
                        supporting = "Share the recent diagnostic buffer",
                        icon = Icons.Outlined.Share,
                        onClick = onExportLogs,
                    )

                    SectionHeader("About")
                    SettingsRow(
                        headline = "App version",
                        supporting = state.appVersion,
                        icon = Icons.Outlined.Info,
                    )
                    SettingsRow(
                        headline = "GitHub",
                        supporting = "Open the Rebubble repository",
                        icon = Icons.Outlined.Code,
                        onClick = onOpenGitHub,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingsRow(
    headline: String,
    supporting: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailingBusy: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        leadingContent = { SettingsLeadingIcon(icon = icon) },
        trailingContent = {
            if (trailingBusy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
    )
}

@Composable
private fun SettingsLeadingIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun syncStatusLabel(status: SyncStatus): String = when (status) {
    SyncStatus.Idle -> "Idle"
    SyncStatus.Syncing -> "Syncing…"
    is SyncStatus.Error -> "Error — ${status.message}"
}

@Preview(showBackground = true, name = "Settings · light")
@Composable
private fun SettingsScreenPreview() {
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
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

@Preview(
    showBackground = true,
    name = "Settings · dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsScreenDarkPreview() {
    RebubbleTheme(darkTheme = true, dynamicColor = false) {
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

@Preview(showBackground = true, name = "Settings · busy")
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

@Preview(showBackground = true, name = "Settings · error")
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
