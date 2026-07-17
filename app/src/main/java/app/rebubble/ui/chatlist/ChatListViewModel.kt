package app.rebubble.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rebubble.data.repo.ChatRepository
import app.rebubble.data.sync.SyncStatusTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    chatRepository: ChatRepository,
    syncStatusTracker: SyncStatusTracker,
) : ViewModel() {

    val uiState: StateFlow<ChatListUiState> = combine(
        chatRepository.observeChats(),
        syncStatusTracker.status,
    ) { chats, syncStatus ->
        if (chats.isEmpty()) {
            ChatListUiState.Empty(syncStatus = syncStatus)
        } else {
            ChatListUiState.Loaded(items = chats, syncStatus = syncStatus)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatListUiState.Loading,
    )
}
