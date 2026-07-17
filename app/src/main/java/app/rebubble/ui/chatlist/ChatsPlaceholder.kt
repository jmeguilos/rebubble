package app.rebubble.ui.chatlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.rebubble.data.repo.ChatRepository
import app.rebubble.ui.theme.RebubbleTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * T16 placeholder so navigation to "chats" is provable. T17 replaces this with the real list.
 */
@HiltViewModel
class ChatsPlaceholderViewModel @Inject constructor(
    chatRepository: ChatRepository,
) : ViewModel() {
    val chatCount: StateFlow<Int> = chatRepository.observeChats()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@Composable
fun ChatsPlaceholderRoute(
    viewModel: ChatsPlaceholderViewModel = hiltViewModel(),
) {
    val count by viewModel.chatCount.collectAsStateWithLifecycle()
    ChatsPlaceholderScreen(chatCount = count)
}

@Composable
fun ChatsPlaceholderScreen(
    chatCount: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (chatCount == 0) {
                "No conversations yet"
            } else {
                "$chatCount conversations"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatsPlaceholderPreview() {
    RebubbleTheme(dynamicColor = false) {
        ChatsPlaceholderScreen(chatCount = 3)
    }
}
