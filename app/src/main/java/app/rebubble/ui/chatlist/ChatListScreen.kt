package app.rebubble.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rebubble.data.media.CoilImageLoaderEntryPoint
import app.rebubble.data.repo.ChatListItem
import app.rebubble.data.sync.SyncStatus
import app.rebubble.ui.theme.RebubbleTheme
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dagger.hilt.android.EntryPointAccessors
import java.io.File

private const val EMPTY_COPY = "No conversations yet. Messages you receive will appear here."
private const val SYNC_ERROR_COPY = "Sync issue — retrying automatically"

@Composable
fun ChatListRoute(
    onChatClick: (String) -> Unit,
    viewModel: ChatListViewModel = hiltViewModel(),
    imageLoader: ImageLoader = rememberAppImageLoader(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ChatListScreen(
        uiState = uiState,
        onChatClick = onChatClick,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    uiState: ChatListUiState,
    onChatClick: (String) -> Unit,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
    syncStatusBanner: @Composable (SyncStatus) -> Unit = { status ->
        SyncStatusBanner(status = status)
    },
) {
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val syncStatus = when (uiState) {
        ChatListUiState.Loading -> SyncStatus.Idle
        is ChatListUiState.Empty -> uiState.syncStatus
        is ChatListUiState.Loaded -> uiState.syncStatus
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
                LargeTopAppBar(
                title = {
                    Text(
                        text = "Rebubble",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            syncStatusBanner(syncStatus)

            when (uiState) {
                ChatListUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.4f))
                    }
                }
                is ChatListUiState.Empty -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = EMPTY_COPY,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is ChatListUiState.Loaded -> {
                    val items = uiState.items
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(
                            items = items,
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

@Composable
fun SyncStatusBanner(
    status: SyncStatus,
    modifier: Modifier = Modifier,
    onDismissError: (() -> Unit)? = null,
) {
    when (status) {
        SyncStatus.Idle -> Unit
        SyncStatus.Syncing -> {
            LinearProgressIndicator(
                modifier = modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
        is SyncStatus.Error -> {
            var dismissed by remember(status.at) { mutableStateOf(false) }
            if (!dismissed) {
                Surface(
                    modifier = modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = SYNC_ERROR_COPY,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                dismissed = true
                                onDismissError?.invoke()
                            },
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
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
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(
            title = item.title,
            avatarPath = item.avatarPath,
            isGroup = item.isGroup,
            imageLoader = imageLoader,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (timestamp.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
    }
}

@Composable
private fun ChatAvatar(
    title: String,
    avatarPath: String?,
    isGroup: Boolean,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    val initials = remember(title) { titleInitials(title) }
    Box(
        modifier = modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarPath.isNullOrBlank() && File(avatarPath).isFile) {
            AsyncImage(
                model = File(avatarPath),
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        } else if (isGroup) {
            StackedMonogram(initials = initials)
        } else {
            MonogramCircle(initials = initials, size = 40.dp)
        }
    }
}

@Composable
private fun MonogramCircle(
    initials: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Quiet group treatment: two overlapping tonal monogram discs. */
@Composable
private fun StackedMonogram(
    initials: String,
    modifier: Modifier = Modifier,
) {
    val primary = initials.take(1).ifEmpty { "?" }
    val secondary = initials.drop(1).take(1).ifEmpty { primary }
    Box(modifier = modifier.size(40.dp)) {
        MonogramCircle(
            initials = secondary,
            size = 28.dp,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        MonogramCircle(
            initials = primary,
            size = 28.dp,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

internal fun titleInitials(title: String): String {
    val parts = title.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> buildString {
            append(parts[0].first().uppercaseChar())
            append(parts[1].first().uppercaseChar())
        }
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

@Preview(showBackground = true, name = "Empty")
@Composable
private fun ChatListEmptyPreview() {
    val context = LocalContext.current
    RebubbleTheme(dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Empty(),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
        )
    }
}

@Preview(showBackground = true, name = "Loaded")
@Composable
private fun ChatListLoadedPreview() {
    val context = LocalContext.current
    RebubbleTheme(dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loaded(
                items = listOf(
                    ChatListItem(
                        guid = "1",
                        title = "Alice",
                        isGroup = false,
                        lastMessageDate = System.currentTimeMillis() - 120_000,
                        lastMessagePreview = "See you soon",
                        style = 45,
                    ),
                    ChatListItem(
                        guid = "2",
                        title = "John, +15551212",
                        isGroup = true,
                        lastMessageDate = System.currentTimeMillis() - 86_400_000,
                        lastMessagePreview = "Photo",
                        style = 43,
                    ),
                ),
            ),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
            nowMs = System.currentTimeMillis(),
        )
    }
}

@Preview(showBackground = true, name = "With banner")
@Composable
private fun ChatListBannerPreview() {
    val context = LocalContext.current
    RebubbleTheme(dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loaded(
                items = listOf(
                    ChatListItem(
                        guid = "1",
                        title = "Alice",
                        isGroup = false,
                        lastMessageDate = System.currentTimeMillis(),
                        lastMessagePreview = "Hi",
                        style = 45,
                    ),
                ),
                syncStatus = SyncStatus.Error(message = "timeout", at = 1L),
            ),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
        )
    }
}
