package app.rebubble.ui.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import app.rebubble.ui.theme.RebubbleTheme
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext

@Composable
fun ChatRoute(
    onBack: () -> Unit,
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
        onSendText = viewModel::sendText,
        onSendAttachment = viewModel::sendAttachment,
        onRetry = viewModel::retry,
        onDownloadAttachment = viewModel::ensureDownloaded,
        onLoadOlder = viewModel::loadOlder,
        imageLoader = imageLoader,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val listState = rememberLazyListState()
    var selectedGuid by remember { mutableStateOf<String?>(null) }

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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
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
                actions = {
                    // Balance the back button so the title stays visually centered.
                    Box(modifier = Modifier.padding(12.dp)) {}
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Composer(
                isSms = uiState.isSms,
                onSendText = onSendText,
                onSendAttachment = onSendAttachment,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
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
                                onLongPress = {
                                    selectedGuid =
                                        if (selectedGuid == item.message.guid) null else item.message.guid
                                },
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

@Preview(showBackground = true, name = "Chat with group + day")
@Composable
private fun ChatScreenPreview() {
    val ctx = LocalPlatformContext.current
    val now = System.currentTimeMillis()
    RebubbleTheme(dynamicColor = false) {
        ChatScreen(
            uiState = ChatUiState(
                title = "Alex",
                isSms = false,
                loading = false,
                items = listOf(
                    ChatUiItem.Bubble(
                        message = previewMsg("1", "See you there", now, true),
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
            ),
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

private fun previewMsg(
    guid: String,
    text: String,
    date: Long,
    fromMe: Boolean,
) = MessageEntity(
    guid = guid,
    chatGuid = "c",
    originalRowId = null,
    text = text,
    subject = null,
    isFromMe = fromMe,
    senderAddress = null,
    dateCreated = date,
    dateRead = null,
    dateDelivered = null,
    groupTitle = null,
    associatedMessageGuid = null,
    associatedMessageType = null,
    threadOriginatorGuid = null,
    expressiveSendStyleId = null,
    dateEdited = null,
    dateRetracted = null,
    sendStatus = SendStatus.SENT,
)
