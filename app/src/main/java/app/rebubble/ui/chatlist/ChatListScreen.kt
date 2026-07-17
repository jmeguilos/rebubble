package app.rebubble.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rebubble.data.media.CoilImageLoaderEntryPoint
import app.rebubble.data.repo.ChatListItem
import app.rebubble.data.sync.SyncStatus
import app.rebubble.ui.common.ChatAvatar
import app.rebubble.ui.common.ChatAvatarSizeLarge
import app.rebubble.ui.common.SearchConversationsPill
import app.rebubble.ui.common.SyncStatusChip
import app.rebubble.ui.theme.ListSheetTopShape
import app.rebubble.ui.theme.RebubbleTheme
import coil3.ImageLoader
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

private const val SEARCH_COMING_SOON = "Search is coming soon."
private val RowMinHeight = 76.dp
private val HeaderIconSize = 32.dp

@Composable
fun ChatListRoute(
    onChatClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel(),
    imageLoader: ImageLoader = rememberAppImageLoader(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ChatListScreen(
        uiState = uiState,
        onChatClick = onChatClick,
        onSettingsClick = onSettingsClick,
        imageLoader = imageLoader,
    )
}

@Composable
internal fun rememberAppImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, CoilImageLoaderEntryPoint::class.java)
            .imageLoader()
    }
}

@Composable
fun ChatListScreen(
    uiState: ChatListUiState,
    onChatClick: (String) -> Unit,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    onSettingsClick: () -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val syncStatus = when (uiState) {
        ChatListUiState.Loading -> SyncStatus.Idle
        is ChatListUiState.Empty -> uiState.syncStatus
        is ChatListUiState.Loaded -> uiState.syncStatus
    }
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        // Tonal Scaffold bg extends under the status bar; only the header consumes status insets.
        Column(modifier = Modifier.fillMaxSize()) {
            ChatListHeader(
                onSettingsClick = onSettingsClick,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .consumeWindowInsets(WindowInsets.statusBars),
            )

            SearchConversationsPill(
                onClick = {
                    scope.launch {
                        snackbarHostState.showSnackbar(SEARCH_COMING_SOON)
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SyncStatusChipSlot(status = syncStatus)

            // Sheet paints to the physical bottom edge; list/empty content pads for nav bars.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = ListSheetTopShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                when (uiState) {
                    ChatListUiState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = navBarBottom),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        }
                    }
                    is ChatListUiState.Empty -> {
                        ChatListEmptyState(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = navBarBottom),
                        )
                    }
                    is ChatListUiState.Loaded -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp + navBarBottom),
                        ) {
                            items(
                                items = uiState.items,
                                key = { it.guid },
                            ) { item ->
                                ChatListRow(
                                    item = item,
                                    nowMs = nowMs,
                                    imageLoader = imageLoader,
                                    onClick = { onChatClick(item.guid) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatListHeader(
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Rebubble",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SettingsAvatarButton(onClick = onSettingsClick)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsAvatarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(HeaderIconSize),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncStatusChipSlot(
    status: SyncStatus,
    modifier: Modifier = Modifier,
) {
    if (status is SyncStatus.Idle) {
        Spacer(modifier = modifier.height(12.dp))
        return
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        SyncStatusChip(status = status)
    }
}

@Composable
private fun ChatListEmptyState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No conversations yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Messages you receive will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatListRow(
    item: ChatListItem,
    nowMs: Long,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timestamp by remember(item.lastMessageDate, nowMs) {
        derivedStateOf {
            val then = item.lastMessageDate ?: return@derivedStateOf ""
            formatRelativeTimestamp(nowMs, then)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RowMinHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(
            title = item.title,
            avatarPath = item.avatarPath,
            isGroup = item.isGroup,
            imageLoader = imageLoader,
            size = ChatAvatarSizeLarge,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.lastMessagePreview.isNullOrBlank()) {
                Text(
                    text = item.lastMessagePreview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (timestamp.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// region Previews

private fun previewItems(nowMs: Long): List<ChatListItem> = listOf(
    ChatListItem(
        guid = "1",
        title = "Alice Chen",
        isGroup = false,
        lastMessageDate = nowMs - 120_000,
        lastMessagePreview = "See you soon — parking is around back",
        style = 45,
    ),
    ChatListItem(
        guid = "2",
        title = "John, Maya",
        isGroup = true,
        lastMessageDate = nowMs - 3_600_000,
        lastMessagePreview = "Photo",
        style = 43,
    ),
    ChatListItem(
        guid = "3",
        title = "+15551234567",
        isGroup = false,
        lastMessageDate = nowMs - 86_400_000,
        lastMessagePreview = "Call me when you land",
        style = 45,
    ),
    ChatListItem(
        guid = "4",
        title = "Weekend crew",
        isGroup = true,
        lastMessageDate = nowMs - 172_800_000,
        lastMessagePreview = "Sam: Bring chips",
        style = 43,
    ),
    ChatListItem(
        guid = "5",
        title = "Jordan Lee",
        isGroup = false,
        lastMessageDate = nowMs - 604_800_000,
        lastMessagePreview = "Thanks!",
        style = 45,
    ),
)

@Preview(showBackground = true, name = "Loaded · light")
@Composable
private fun ChatListLoadedLightPreview() {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loaded(items = previewItems(now)),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
            nowMs = now,
        )
    }
}

@Preview(showBackground = true, name = "Loaded · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatListLoadedDarkPreview() {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    RebubbleTheme(darkTheme = true, dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loaded(items = previewItems(now)),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
            nowMs = now,
        )
    }
}

@Preview(showBackground = true, name = "Empty · light")
@Composable
private fun ChatListEmptyLightPreview() {
    val context = LocalContext.current
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Empty(),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
        )
    }
}

@Preview(showBackground = true, name = "Empty · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatListEmptyDarkPreview() {
    val context = LocalContext.current
    RebubbleTheme(darkTheme = true, dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Empty(),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
        )
    }
}

@Preview(showBackground = true, name = "Syncing · light")
@Composable
private fun ChatListSyncingLightPreview() {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loaded(
                items = previewItems(now),
                syncStatus = SyncStatus.Syncing,
            ),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
            nowMs = now,
        )
    }
}

@Preview(showBackground = true, name = "Syncing · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatListSyncingDarkPreview() {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    RebubbleTheme(darkTheme = true, dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loaded(
                items = previewItems(now),
                syncStatus = SyncStatus.Syncing,
            ),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
            nowMs = now,
        )
    }
}

@Preview(showBackground = true, name = "Sync error · light")
@Composable
private fun ChatListSyncErrorLightPreview() {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loaded(
                items = previewItems(now),
                syncStatus = SyncStatus.Error(message = "timeout", at = 1L),
            ),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
            nowMs = now,
        )
    }
}

@Preview(showBackground = true, name = "Sync error · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatListSyncErrorDarkPreview() {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    RebubbleTheme(darkTheme = true, dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loaded(
                items = previewItems(now),
                syncStatus = SyncStatus.Error(message = "timeout", at = 1L),
            ),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
            nowMs = now,
        )
    }
}

@Preview(showBackground = true, name = "Loading")
@Composable
private fun ChatListLoadingPreview() {
    val context = LocalContext.current
    RebubbleTheme(dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loading,
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
        )
    }
}

// endregion
