package app.rebubble.ui.chatlist

import app.rebubble.data.repo.ChatListItem
import app.rebubble.data.repo.GROUP_CHAT_STYLE
import app.rebubble.data.sync.SyncStatus

/** The three list filters offered by the chips row under the search pill. */
enum class ChatFilter {
    All,
    Unread,
    Groups,
}

/**
 * Pure chip filtering. [ChatFilter.Unread] keeps chats with a non-zero local unread counter;
 * [ChatFilter.Groups] keeps BlueBubbles group chats ([GROUP_CHAT_STYLE] == 43). Order is always
 * preserved — the repository already emits newest-first.
 */
fun applyChatFilter(items: List<ChatListItem>, filter: ChatFilter): List<ChatListItem> =
    when (filter) {
        ChatFilter.All -> items
        ChatFilter.Unread -> items.filter { it.unreadCount > 0 }
        ChatFilter.Groups -> items.filter { it.style == GROUP_CHAT_STYLE }
    }

/**
 * Chat list screen state. [Loading] until the first [ChatRepository.observeChats] emission;
 * then [Empty] or [Loaded]. Sync chip is derived from [syncStatus] (Idle → none).
 *
 * [Empty] means "this account has no conversations at all" — a filter that matches nothing still
 * yields [Loaded] (with empty [Loaded.items]) so the chips row stays on screen and the user can
 * switch back off it.
 */
sealed interface ChatListUiState {
    data object Loading : ChatListUiState

    data class Empty(
        val syncStatus: SyncStatus = SyncStatus.Idle,
    ) : ChatListUiState

    data class Loaded(
        /** Already filtered by [filter]. */
        val items: List<ChatListItem>,
        val syncStatus: SyncStatus = SyncStatus.Idle,
        val filter: ChatFilter = ChatFilter.All,
    ) : ChatListUiState
}

/** True when the sync-status chip slot should render something (Syncing or Error). */
val ChatListUiState.showSyncBanner: Boolean
    get() {
        val status = when (this) {
            ChatListUiState.Loading -> return false
            is ChatListUiState.Empty -> syncStatus
            is ChatListUiState.Loaded -> syncStatus
        }
        return status !is SyncStatus.Idle
    }
