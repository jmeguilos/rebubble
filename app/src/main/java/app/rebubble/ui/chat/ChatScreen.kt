package app.rebubble.ui.chat

import android.animation.ValueAnimator
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.ui.chatlist.rememberAppImageLoader
import app.rebubble.ui.common.ChatAvatar
import app.rebubble.ui.common.ChatAvatarSizeCompact
import app.rebubble.ui.theme.ListSheetTopShape
import app.rebubble.ui.theme.RebubbleTheme
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext

@Composable
fun ChatRoute(
    onBack: () -> Unit,
    onSettingsClick: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
    imageLoader: ImageLoader = rememberAppImageLoader(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel.chatGuid) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onEnter()
                Lifecycle.Event.ON_PAUSE -> viewModel.onExit()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.onEnter()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onExit()
        }
    }

    ChatScreen(
        uiState = uiState,
        onBack = onBack,
        onSettingsClick = onSettingsClick,
        onSendText = viewModel::sendText,
        onSendAttachment = viewModel::sendAttachment,
        onRetry = viewModel::retry,
        onDownloadAttachment = viewModel::ensureDownloaded,
        onLoadOlder = viewModel::loadOlder,
        onTransientErrorShown = viewModel::clearTransientError,
        onScrollToBottomConsumed = viewModel::consumeScrollToBottom,
        imageLoader = imageLoader,
    )
}

@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onBack: () -> Unit,
    onSendText: (String) -> Unit,
    onSendAttachment: (Uri) -> Unit,
    onRetry: (String) -> Unit,
    onDownloadAttachment: (String) -> Unit,
    onLoadOlder: () -> Unit,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    onSettingsClick: () -> Unit = {},
    onTransientErrorShown: () -> Unit = {},
    onScrollToBottomConsumed: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    var selectedGuid by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Newest-first list: first own bubble is the latest outbound message.
    val latestOwnGuid = remember(uiState.items) { latestOwnMessageGuid(uiState.items) }

    val shouldLoadOlder by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            val total = info.totalItemsCount
            total > 0 && lastVisible >= total - 4
        }
    }
    LaunchedEffect(shouldLoadOlder, uiState.endReached) {
        if (shouldLoadOlder && !uiState.endReached) {
            onLoadOlder()
        }
    }

    LaunchedEffect(uiState.transientError) {
        val message = uiState.transientError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onTransientErrorShown()
    }

    val firstKey = uiState.items.firstOrNull()?.key
    LaunchedEffect(firstKey, uiState.pendingScrollToBottom) {
        if (!uiState.pendingScrollToBottom) return@LaunchedEffect
        val first = uiState.items.firstOrNull() as? ChatUiItem.Bubble ?: return@LaunchedEffect
        if (!first.message.isFromMe) return@LaunchedEffect
        if (ValueAnimator.areAnimatorsEnabled()) {
            listState.animateScrollToItem(0)
        } else {
            listState.scrollToItem(0)
        }
        onScrollToBottomConsumed()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { _ ->
        // Tonal Scaffold bg extends under the status bar; only the app bar consumes status insets.
        Column(modifier = Modifier.fillMaxSize()) {
            ChatAppBar(
                title = uiState.title,
                avatarPath = uiState.avatarPath,
                isGroup = uiState.isGroup,
                imageLoader = imageLoader,
                onBack = onBack,
                onSettingsClick = onSettingsClick,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .consumeWindowInsets(WindowInsets.statusBars),
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = ListSheetTopShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (uiState.loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            contentPadding = PaddingValues(bottom = 12.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { selectedGuid = null })
                                },
                        ) {
                            items(
                                items = uiState.items,
                                key = { it.key },
                                contentType = { it.contentType },
                            ) { item ->
                                when (item) {
                                    is ChatUiItem.DaySeparator -> DaySeparatorRow(label = item.label)
                                    is ChatUiItem.GroupEvent -> GroupEventRow(label = item.label)
                                    is ChatUiItem.Bubble -> MessageBubble(
                                        item = item,
                                        isSms = uiState.isSms,
                                        selected = selectedGuid == item.message.guid,
                                        showDeliveryReceipt = item.message.guid == latestOwnGuid,
                                        onLongPress = {
                                            selectedGuid =
                                                if (selectedGuid == item.message.guid) {
                                                    null
                                                } else {
                                                    item.message.guid
                                                }
                                        },
                                        onTap = { selectedGuid = null },
                                        onRetry = { onRetry(item.message.guid) },
                                        onDownloadAttachment = onDownloadAttachment,
                                        imageLoader = imageLoader,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Composer(
                isSms = uiState.isSms,
                onSendText = onSendText,
                onSendAttachment = onSendAttachment,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        }
    }
}

/** Compact ~72dp header on the tonal layer: back · centered avatar+title · overflow. */
@Composable
private fun ChatAppBar(
    title: String,
    avatarPath: String?,
    isGroup: Boolean,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChatAvatar(
                title = title,
                avatarPath = avatarPath,
                isGroup = isGroup,
                imageLoader = imageLoader,
                size = ChatAvatarSizeCompact,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "More options",
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = {
                        menuExpanded = false
                        onSettingsClick()
                    },
                )
            }
        }
    }
}

@Composable
internal fun DaySeparatorRow(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun GroupEventRow(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

/**
 * Newest-first chat list: the first own bubble is the latest outbound message.
 * Stable when older pages are appended at the tail ([ChatViewModel.loadOlder]).
 */
internal fun latestOwnMessageGuid(items: List<ChatUiItem>): String? =
    items.asSequence()
        .filterIsInstance<ChatUiItem.Bubble>()
        .firstOrNull { it.message.isFromMe }
        ?.message
        ?.guid

// region Previews

private fun previewMsg(
    guid: String,
    text: String,
    date: Long,
    fromMe: Boolean,
    dateDelivered: Long? = null,
    dateRead: Long? = null,
    status: SendStatus = SendStatus.SENT,
) = MessageEntity(
    guid = guid,
    chatGuid = "c",
    originalRowId = null,
    text = text,
    subject = null,
    isFromMe = fromMe,
    senderAddress = null,
    dateCreated = date,
    dateRead = dateRead,
    dateDelivered = dateDelivered,
    groupTitle = null,
    associatedMessageGuid = null,
    associatedMessageType = null,
    threadOriginatorGuid = null,
    expressiveSendStyleId = null,
    dateEdited = null,
    dateRetracted = null,
    sendStatus = status,
)

private fun previewThread(isSms: Boolean = false): ChatUiState {
    val now = System.currentTimeMillis()
    return ChatUiState(
        title = if (isSms) "+15559876543" else "Alex",
        isSms = isSms,
        isGroup = false,
        loading = false,
        items = listOf(
            ChatUiItem.Bubble(
                message = previewMsg(
                    "1",
                    "See you there",
                    now,
                    true,
                    dateDelivered = now - 1_000,
                    dateRead = now - 500,
                ),
                attachments = emptyList(),
                showTail = true,
                isFirstInRun = false,
                isLastInRun = true,
            ),
            ChatUiItem.Bubble(
                message = previewMsg("2", "On my way!", now - 30_000, true),
                attachments = emptyList(),
                showTail = false,
                isFirstInRun = true,
                isLastInRun = false,
            ),
            ChatUiItem.Bubble(
                message = previewMsg("3", "Are you close?", now - 60_000, false),
                attachments = emptyList(),
                showTail = true,
                isFirstInRun = true,
                isLastInRun = true,
            ),
            ChatUiItem.Bubble(
                message = previewMsg(
                    "4",
                    "Couldn't send this",
                    now - 90_000,
                    true,
                    status = SendStatus.FAILED,
                ),
                attachments = emptyList(),
                showTail = true,
                isFirstInRun = true,
                isLastInRun = true,
            ),
            ChatUiItem.DaySeparator(label = "Today", dayEpochDay = 0),
            ChatUiItem.GroupEvent(
                guid = "g1",
                label = "Alex named the conversation \"Ski trip\"",
                dateCreated = now - 86_400_000,
            ),
            ChatUiItem.DaySeparator(label = "Yesterday", dayEpochDay = -1),
        ),
    )
}

@Preview(showBackground = true, name = "Thread · light iMessage")
@Composable
private fun ChatScreenLightPreview() {
    val ctx = LocalPlatformContext.current
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
        ChatScreen(
            uiState = previewThread(isSms = false),
            onBack = {},
            onSendText = {},
            onSendAttachment = {},
            onRetry = {},
            onDownloadAttachment = {},
            onLoadOlder = {},
            imageLoader = ImageLoader.Builder(ctx).build(),
        )
    }
}

@Preview(
    showBackground = true,
    name = "Thread · dark SMS",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChatScreenDarkSmsPreview() {
    val ctx = LocalPlatformContext.current
    RebubbleTheme(darkTheme = true, dynamicColor = false) {
        ChatScreen(
            uiState = previewThread(isSms = true),
            onBack = {},
            onSendText = {},
            onSendAttachment = {},
            onRetry = {},
            onDownloadAttachment = {},
            onLoadOlder = {},
            imageLoader = ImageLoader.Builder(ctx).build(),
        )
    }
}

// endregion
