package app.rebubble.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rebubble.data.repo.ChatRepository
import app.rebubble.data.sync.SyncStatusTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    chatRepository: ChatRepository,
    syncStatusTracker: SyncStatusTracker,
) : ViewModel() {

    private val filter = MutableStateFlow(ChatFilter.All)

    val uiState: StateFlow<ChatListUiState> = combine(
        chatRepository.observeChats(),
        syncStatusTracker.status,
        filter,
    ) { chats, syncStatus, selectedFilter ->
        // Emptiness is decided on the *unfiltered* list: an empty Unread/Groups result is still a
        // Loaded state so the chips row survives (see ChatListUiState.Empty's KDoc).
        if (chats.isEmpty()) {
            ChatListUiState.Empty(syncStatus = syncStatus)
        } else {
            ChatListUiState.Loaded(
                items = applyChatFilter(chats, selectedFilter),
                syncStatus = syncStatus,
                filter = selectedFilter,
            )
        }
    }.distinctUntilChanged().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatListUiState.Loading,
    )

    fun onFilterSelected(selected: ChatFilter) {
        filter.value = selected
    }
}
