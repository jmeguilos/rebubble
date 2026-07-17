package app.rebubble.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rebubble.data.local.dao.AttachmentDao
import app.rebubble.data.local.dao.ContactDao
import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.outbox.OutboxRepository
import app.rebubble.data.repo.AttachmentRepository
import app.rebubble.data.repo.ChatRepository
import app.rebubble.data.repo.MessageRepository
import app.rebubble.notifications.ActiveChatTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val outboxRepository: OutboxRepository,
    private val attachmentRepository: AttachmentRepository,
    private val chatRepository: ChatRepository,
    private val attachmentDao: AttachmentDao,
    private val contactDao: ContactDao,
    private val activeChatTracker: ActiveChatTracker,
) : ViewModel() {

    val chatGuid: String = checkNotNull(savedStateHandle.get<String>("guid")) {
        "Missing nav arg guid"
    }

    private val endReached = MutableStateFlow(false)
    private val loadOlderMutex = Mutex()
    private val transientError = MutableStateFlow<String?>(null)
    private val pendingScrollToBottom = MutableStateFlow(false)

    private val chatMeta = chatRepository.observeChats()
        .map { chats -> chats.find { it.guid == chatGuid } }
        .distinctUntilChanged()

    private val messagesFlow = messageRepository.observeMessages(chatGuid)

    private val attachmentsFlow = attachmentDao.observeForChat(chatGuid)

    private val contactsFlow = contactDao.observeContacts()
        .map { list -> list.associate { it.address to (it.displayName ?: "") } }
        .distinctUntilChanged()

    val uiState: StateFlow<ChatUiState> = combine(
        messagesFlow,
        attachmentsFlow,
        chatMeta,
        contactsFlow,
        endReached,
    ) { messages, attachments, chat, contacts, reached ->
        val byMessage = attachments.groupBy { it.messageGuid }
        ChatUiState(
            title = chat?.title ?: chatGuid,
            isSms = chatGuid.startsWith(SMS_GUID_PREFIX),
            items = buildChatItems(messages, byMessage, contacts),
            endReached = reached,
            loading = false,
        )
    }.combine(transientError) { state, error ->
        state.copy(transientError = error)
    }.combine(pendingScrollToBottom) { state, scroll ->
        state.copy(pendingScrollToBottom = scroll)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState(
            title = chatGuid,
            isSms = chatGuid.startsWith(SMS_GUID_PREFIX),
            loading = true,
        ),
    )

    /** Screen enter (RESUMED) — activates T15 notification suppression for this chat. */
    fun onEnter() {
        activeChatTracker.current.value = chatGuid
    }

    /** Screen exit — clears suppression when this chat was the active one. */
    fun onExit() {
        if (activeChatTracker.current.value == chatGuid) {
            activeChatTracker.current.value = null
        }
    }

    override fun onCleared() {
        onExit()
        super.onCleared()
    }

    fun clearTransientError() {
        transientError.value = null
    }

    fun consumeScrollToBottom() {
        pendingScrollToBottom.value = false
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            pendingScrollToBottom.value = true
            outboxRepository.sendText(chatGuid, trimmed)
        }
    }

    fun sendAttachment(uri: Uri) {
        viewModelScope.launch {
            try {
                pendingScrollToBottom.value = true
                outboxRepository.sendAttachment(chatGuid, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                pendingScrollToBottom.value = false
                transientError.value = "Couldn't attach that file."
            }
        }
    }

    fun retry(tempGuid: String) {
        viewModelScope.launch {
            outboxRepository.retry(tempGuid)
        }
    }

    fun ensureDownloaded(attachmentGuid: String) {
        viewModelScope.launch {
            attachmentRepository.ensureDownloaded(attachmentGuid)
        }
    }

    /**
     * Backfill older messages. No-op while a call is in flight or after a short page marked
     * [ChatUiState.endReached]. Uses the oldest local [MessageEntity.dateCreated] as `before`.
     */
    fun loadOlder() {
        if (endReached.value) return
        if (loadOlderMutex.isLocked) return
        viewModelScope.launch {
            if (!loadOlderMutex.tryLock()) return@launch
            try {
                if (endReached.value) return@launch
                val oldest = uiState.value.items
                    .mapNotNull { item ->
                        when (item) {
                            is ChatUiItem.Bubble -> item.message.dateCreated
                            is ChatUiItem.GroupEvent -> item.dateCreated
                            is ChatUiItem.DaySeparator -> null
                        }
                    }
                    .minOrNull()
                    ?: return@launch
                val count = messageRepository.loadOlder(chatGuid, oldest, PAGE_SIZE)
                if (count < PAGE_SIZE) {
                    endReached.value = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Leave endReached=false so scroll-up can retry after reconnect.
                transientError.value = "Couldn't load older messages."
            } finally {
                loadOlderMutex.unlock()
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 50
        const val SMS_GUID_PREFIX = "SMS;"
    }
}

internal fun buildChatItems(
    messagesNewestFirst: List<MessageEntity>,
    attachmentsByMessage: Map<String, List<AttachmentEntity>>,
    contactsByAddress: Map<String, String>,
    nowMs: Long = System.currentTimeMillis(),
): List<ChatUiItem> {
    val runFlags = computeBubbleRunFlags(messagesNewestFirst)
    val withDays = insertDaySeparators(messagesNewestFirst, nowMs = nowMs)
    return withDays.map { item ->
        when (item) {
            is ChatTimelineItem.DaySeparator -> ChatUiItem.DaySeparator(
                label = item.label,
                dayEpochDay = item.dayEpochDay,
            )
            is ChatTimelineItem.Message -> {
                val entity = item.entity
                if (entity.itemType != 0) {
                    val sender = resolveGroupEventSenderName(
                        isFromMe = entity.isFromMe,
                        senderAddress = entity.senderAddress,
                        contactsByAddress = contactsByAddress,
                    )
                    ChatUiItem.GroupEvent(
                        guid = entity.guid,
                        label = formatGroupEventText(entity, sender),
                        dateCreated = entity.dateCreated,
                    )
                } else {
                    val flags = runFlags[entity.guid]
                        ?: BubbleRunFlags(showTail = true, isFirstInRun = true, isLastInRun = true)
                    ChatUiItem.Bubble(
                        message = entity,
                        attachments = attachmentsByMessage[entity.guid].orEmpty(),
                        showTail = flags.showTail,
                        isFirstInRun = flags.isFirstInRun,
                        isLastInRun = flags.isLastInRun,
                    )
                }
            }
        }
    }
}
