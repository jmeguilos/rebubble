package app.rebubble.ui.chatlist

import app.rebubble.data.repo.ChatListItem
import app.rebubble.data.sync.SyncStatus

/**
 * Chat list screen state. [Loading] until the first [ChatRepository.observeChats] emission;
 * then [Empty] or [Loaded]. Sync banner is derived from [syncStatus] (Idle → none).
 */
sealed interface ChatListUiState {
    data object Loading : ChatListUiState

    data class Empty(
        val syncStatus: SyncStatus = SyncStatus.Idle,
    ) : ChatListUiState

    data class Loaded(
        val items: List<ChatListItem>,
        val syncStatus: SyncStatus = SyncStatus.Idle,
    ) : ChatListUiState
}

/** True when the sync-status banner slot should render something (Syncing or Error). */
val ChatListUiState.showSyncBanner: Boolean
    get() {
        val status = when (this) {
            ChatListUiState.Loading -> return false
            is ChatListUiState.Empty -> syncStatus
            is ChatListUiState.Loaded -> syncStatus
        }
        return status !is SyncStatus.Idle
    }
