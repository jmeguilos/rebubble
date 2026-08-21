package app.rebubble.ui.chatlist

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private val UnreadBadgeSize = 20.dp
private val FilterChipShape = RoundedCornerShape(8.dp)

/** 56dp FAB + its 4dp bottom padding + 16dp breathing room, so it never occludes the last row. */
private val FabClearance = 76.dp

/**
 * Scroll offset (px) past which the extended FAB collapses to icon-only. Roughly one row, so a
 * fling's initial jitter can't flip the state, and returning to the top always restores the label.
 */
private const val FabCollapseScrollThresholdPx = 120
private const val MaxUnreadBadgeCount = 99

@Composable
fun ChatListRoute(
    onChatClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onStartChatClick: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel(),
    imageLoader: ImageLoader = rememberAppImageLoader(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ChatListScreen(
        uiState = uiState,
        onChatClick = onChatClick,
        onSettingsClick = onSettingsClick,
        onStartChatClick = onStartChatClick,
        onFilterSelected = viewModel::onFilterSelected,
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
    onStartChatClick: () -> Unit = {},
    onFilterSelected: (ChatFilter) -> Unit = {},
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

    // Extended FAB collapse-on-scroll. A threshold (rather than any non-zero delta) keeps the FAB
    // from thrashing between states on small fling jitter, and re-expanding at the very top is what
    // makes the expanded label the resting state a user always returns to.
    val fabExpanded by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset < FabCollapseScrollThresholdPx
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // The Scaffold's insets are zeroed (single-owner model), so the host must clear the
        // gesture-nav pill itself rather than render behind it.
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
        // Collapses to an icon-only FAB while scrolling down and re-expands on scroll up, per M3
        // and Google Messages. Driven off the list's own scroll offset rather than a
        // nested-scroll-connected top bar, since this screen has no collapsing app bar.
        floatingActionButton = {
            StartChatFab(onClick = onStartChatClick, expanded = fabExpanded)
        },
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

            // Chips only exist once there is something to filter; the empty account keeps the
            // clean "no conversations yet" screen.
            if (uiState is ChatListUiState.Loaded) {
                ChatFilterChips(
                    selected = uiState.filter,
                    onFilterSelected = onFilterSelected,
                )
            }

            SyncStatusChipSlot(status = syncStatus)

            // Sheet paints to the physical bottom edge; list/empty content pads for nav bars.
            // surfaceContainerLowest (rather than surface) keeps the sheet visibly distinct from
            // the surfaceContainer screen background above even under a dynamic (Material You)
            // palette, where surface and surfaceContainer can end up nearly identical.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = ListSheetTopShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
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
                    is ChatListUiState.Loaded -> if (uiState.items.isEmpty()) {
                        ChatFilterEmptyState(
                            filter = uiState.filter,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = navBarBottom),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // Must clear the FAB, not just the nav bar: 56dp FAB + its 4dp bottom
                            // padding + 16dp breathing room. Previously 16dp, so the FAB sat on
                            // top of the last row in a full list.
                            contentPadding = PaddingValues(bottom = FabClearance + navBarBottom),
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
            // 8dp/12dp before the gear's target slot grew from 32dp to 48dp. The Row's height is
            // set by its tallest child, so keeping the old padding moved the whole header — and the
            // list under it — down 16dp. Removing 8dp from each edge holds the band at 52dp and
            // leaves both the title and the painted gear on exactly the pixels they had before
            // (both are centre-aligned, so only the band's height moves them).
            .padding(start = 20.dp, end = 12.dp, top = 0.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Rebubble",
            style = MaterialTheme.typography.headlineSmall,
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
        // Two different properties: the card fixes the *painted* diameter at 32dp, M3 fixes the
        // *touch* target at 48dp. `minimumInteractiveComponentSize()` reconciles them by growing
        // the layout slot to 48dp and centring the 32dp circle inside it — nothing gets bigger on
        // screen. `Surface(onClick=)` does not apply it itself (only IconButton / Checkbox / … do),
        // so without this the gear is a 32dp target wedged against the screen edge.
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(HeaderIconSize),
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

/**
 * "Start chat" extended FAB (design/screens/chat-list.html `.fab`: primaryContainer /
 * onPrimaryContainer, 18dp corner radius, Material Symbol "chat" + label, bottom-end anchored).
 * Icon is [Icons.AutoMirrored.Outlined.Chat] -- the closest Compose material icon to the design's
 * Material Symbol "chat" glyph; auto-mirrored since the bubble's tail should flip under RTL.
 */
@Composable
private fun StartChatFab(
    onClick: () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(end = 4.dp, bottom = 4.dp)
            // Explicit semantics: the merged text was observed missing from the a11y tree on
            // device (unlabeled for TalkBack and undiscoverable by UI tests). Still required in
            // the collapsed state, where there is no label to merge at all.
            .semantics { contentDescription = "Start chat" },
        // M3 extended FAB uses shapes.large (16dp); the card's 18px was amended to match.
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        // M3 FAB resting elevation is level 3 (6dp), which is FloatingActionButtonDefaults' own
        // default. The previous explicit 3dp produced no visible shadow against a near-white sheet.
        icon = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Chat,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        text = { Text("Start chat") },
    )
}

/**
 * All / Unread / Groups chips (card anatomy: 32dp tall, 8dp radius, selected = secondaryContainer,
 * unselected = transparent with an outline-variant border).
 *
 * No `minimumInteractiveComponentSize()` here on purpose: measured against material3
 * 1.5.0-alpha24, `FilterChip` already lays itself out in a 48dp slot around its 32dp fill (painted
 * 32dp, slot 48dp, touch 48dp — see TouchTargetSizeTest). Adding the modifier would be a no-op, and
 * "compensating" this Row's padding for a growth that never happens would raise the chips 8dp and
 * shrink the strip.
 */
@Composable
private fun ChatFilterChips(
    selected: ChatFilter,
    onFilterSelected: (ChatFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChatFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = { onFilterSelected(filter) },
                label = { Text(text = filter.label) },
                // M3 filter chips show a leading checkmark when selected. Conveying selection by
                // fill alone is also a colour-only state indicator (WCAG 1.4.1).
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
                shape = FilterChipShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                border = if (isSelected) {
                    null
                } else {
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = false,
                        // `outline`, not `outlineVariant`: this is an interactive border and must
                        // clear WCAG 1.4.11's 3:1, which outlineVariant (a decorative divider
                        // colour) does not.
                        borderColor = MaterialTheme.colorScheme.outline,
                    )
                },
            )
        }
    }
}

/**
 * The row's single accessibility label: title, preview, relative time, unread count — in that
 * reading order, spelled out ("2 unread messages", never a bare "2").
 *
 * Parts are joined into sentences so a screen reader pauses between them, and each part is kept
 * verbatim: the title and preview substrings have to survive into the description, because
 * text-based UI automation matches on them.
 */
internal fun chatListRowDescription(
    title: String,
    preview: String?,
    relativeTime: String,
    unreadCount: Int,
): String {
    val parts = buildList {
        add(title)
        if (!preview.isNullOrBlank()) add(preview)
        if (relativeTime.isNotEmpty()) add(relativeTime)
        if (unreadCount > 0) {
            add(if (unreadCount == 1) "1 unread message" else "$unreadCount unread messages")
        }
    }
    return parts.joinToString(separator = " ") { part ->
        if (part.endsWith('.') || part.endsWith('?') || part.endsWith('!')) part else "$part."
    }
}

private val ChatFilter.label: String
    get() = when (this) {
        ChatFilter.All -> "All"
        ChatFilter.Unread -> "Unread"
        ChatFilter.Groups -> "Groups"
    }

@Composable
private fun ChatFilterEmptyState(
    filter: ChatFilter,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when (filter) {
                ChatFilter.All -> "No conversations yet"
                ChatFilter.Unread -> "No unread conversations"
                ChatFilter.Groups -> "No group conversations"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    val isUnread = item.unreadCount > 0
    val rowDescription = chatListRowDescription(
        title = item.title,
        preview = item.lastMessagePreview,
        relativeTime = timestamp,
        unreadCount = item.unreadCount,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            // A *minimum*, not a fixed height. Measured on API 35, where text scales non-linearly:
            // titleMedium + bodyMedium come to exactly 76dp at 200% font scale — the old fixed
            // height, with zero headroom — and to 88dp at 250%, which a fixed 76dp box can only
            // deliver by clipping the preview line.
            .heightIn(min = RowMinHeight)
            .clickable(onClick = onClick, onClickLabel = "Open conversation")
            // `clickable` already merges descendants, so TalkBack sees one node — but its label was
            // the raw concatenation of every child, monogram initials and a bare unread number
            // included ("MC, Maya Chen, …, 2"). An explicit description replaces that with one
            // sentence. The child Text nodes stay in the platform accessibility tree (Compose keeps
            // exposing them; merging only changes which node a screen reader *focuses*), so
            // text-based automation — the Maestro flows in `maestro/` select rows by title and
            // preview — keeps working.
            .semantics(mergeDescendants = true) { contentDescription = rowDescription }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(
            title = item.title,
            avatarPath = item.avatarPath,
            isGroup = item.isGroup,
            imageLoader = imageLoader,
            size = ChatAvatarSizeLarge,
            hueKey = item.guid,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isUnread) FontWeight.Bold else null,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.lastMessagePreview.isNullOrBlank()) {
                Text(
                    text = item.lastMessagePreview,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isUnread) FontWeight.SemiBold else null,
                    color = if (isUnread) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (timestamp.isNotEmpty() || isUnread) {
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (timestamp.isNotEmpty()) {
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isUnread) FontWeight.SemiBold else null,
                        color = if (isUnread) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (isUnread) UnreadBadge(count = item.unreadCount)
            }
        }
    }
}

/**
 * Unread count pill: 20dp minimum in both axes, primary on onPrimary, capped at `99+` so a long
 * backlog can never widen the row's trailing column.
 *
 * Both axes are minimums. The height used to be fixed at 20dp around a 12sp label: measured, that
 * label already needs 24dp at 200% font scale, so the digits were being clipped.
 */
@Composable
private fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = UnreadBadgeSize, minHeight = UnreadBadgeSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > MaxUnreadBadgeCount) "$MaxUnreadBadgeCount+" else count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
        )
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
        unreadCount = 2,
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
        unreadCount = 128,
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

@Preview(showBackground = true, name = "Unread filter · light")
@Composable
private fun ChatListUnreadFilterLightPreview() {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loaded(
                items = applyChatFilter(previewItems(now), ChatFilter.Unread),
                filter = ChatFilter.Unread,
            ),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
            nowMs = now,
        )
    }
}

@Preview(
    showBackground = true,
    name = "Groups filter · dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChatListGroupsFilterDarkPreview() {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    RebubbleTheme(darkTheme = true, dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loaded(
                items = applyChatFilter(previewItems(now), ChatFilter.Groups),
                filter = ChatFilter.Groups,
            ),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
            nowMs = now,
        )
    }
}

@Preview(showBackground = true, name = "Filter · no matches")
@Composable
private fun ChatListFilterNoMatchesPreview() {
    val context = LocalContext.current
    RebubbleTheme(darkTheme = false, dynamicColor = false) {
        ChatListScreen(
            uiState = ChatListUiState.Loaded(items = emptyList(), filter = ChatFilter.Unread),
            onChatClick = {},
            imageLoader = ImageLoader.Builder(context).build(),
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
