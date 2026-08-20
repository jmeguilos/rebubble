package app.rebubble.ui.chatlist

import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.ChatHandleCrossRef
import app.rebubble.data.local.entity.ContactEntity
import app.rebubble.data.local.entity.HandleEntity
import app.rebubble.data.repo.ChatListItem
import app.rebubble.data.repo.ChatRepository
import app.rebubble.data.sync.SyncOutcome
import app.rebubble.data.sync.SyncStatus
import app.rebubble.data.logging.RingBufferLogger;
import app.rebubble.data.sync.SyncStatusTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ChatListViewModelTest {

    private lateinit var db: RebubbleDatabase
    private lateinit var repo: ChatRepository
    private lateinit var tracker: SyncStatusTracker

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = InMemoryDatabaseFactory.create()
        repo = ChatRepository(
            chatDao = db.chatDao(),
            handleDao = db.handleDao(),
            contactDao = db.contactDao(),
        )
        tracker = SyncStatusTracker(RingBufferLogger())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel() = ChatListViewModel(repo, tracker)

    private suspend fun awaitState(
        vm: ChatListViewModel,
        predicate: (ChatListUiState) -> Boolean,
    ): ChatListUiState = withTimeout(5_000) {
        var last: ChatListUiState = vm.uiState.value
        if (predicate(last)) return@withTimeout last
        vm.uiState.first { state ->
            last = state
            predicate(state)
        }
    }

    private fun chat(
        guid: String,
        style: Int = 45,
        displayName: String? = null,
        lastMessageDate: Long? = null,
        lastMessagePreview: String? = null,
        chatIdentifier: String? = "+15550001111",
        unreadCount: Int = 0,
    ) = ChatEntity(
        guid = guid,
        style = style,
        chatIdentifier = chatIdentifier,
        displayName = displayName,
        isArchived = false,
        lastMessageDate = lastMessageDate,
        lastMessagePreview = lastMessagePreview,
        unreadCount = unreadCount,
    )

    @Test
    fun `empty repository emission yields Empty`() = runBlocking {
        val vm = viewModel()
        val state = awaitState(vm) { it is ChatListUiState.Empty || it is ChatListUiState.Loaded }
        assertEquals(ChatListUiState.Empty(syncStatus = SyncStatus.Idle), state)
    }

    @Test
    fun `repository emission yields Loaded items ordered by lastMessageDate DESC`() = runBlocking {
        db.chatDao().upsert(
            listOf(
                chat("older", lastMessageDate = 100L, lastMessagePreview = "first", displayName = "Alpha"),
                chat("newer", lastMessageDate = 200L, lastMessagePreview = "second", displayName = "Beta"),
            )
        )
        val vm = viewModel()
        val state = awaitState(vm) { it is ChatListUiState.Loaded } as ChatListUiState.Loaded
        assertEquals(listOf("newer", "older"), state.items.map { it.guid })
        assertEquals("Beta", state.items[0].title)
        assertEquals("second", state.items[0].lastMessagePreview)
        assertEquals("Alpha", state.items[1].title)
        assertEquals("first", state.items[1].lastMessagePreview)
    }

    @Test
    fun `SyncStatus Error is carried on uiState banner and Idle carries none`() = runBlocking {
        db.chatDao().upsert(listOf(chat("c1", lastMessageDate = 1L, displayName = "One")))
        val vm = viewModel()
        awaitState(vm) { it is ChatListUiState.Loaded }

        tracker.track {
            SyncOutcome(emptyList(), error = IllegalStateException("boom"))
        }

        val withError = awaitState(vm) {
            it is ChatListUiState.Loaded && it.syncStatus is SyncStatus.Error
        } as ChatListUiState.Loaded
        assertTrue(withError.syncStatus is SyncStatus.Error)
        assertTrue(withError.showSyncBanner)

        tracker.track { SyncOutcome(emptyList(), error = null) }

        val idle = awaitState(vm) {
            it is ChatListUiState.Loaded && it.syncStatus is SyncStatus.Idle
        } as ChatListUiState.Loaded
        assertEquals(SyncStatus.Idle, idle.syncStatus)
        assertTrue(!idle.showSyncBanner)
    }

    @Test
    fun `group chat title and isGroup flag surface on Loaded items`() = runBlocking {
        val addressA = "+15554440001"
        val addressB = "+15554440002"
        db.chatDao().upsert(
            listOf(chat("mixed", style = 43, displayName = null, chatIdentifier = "chat.group", lastMessageDate = 1L))
        )
        db.handleDao().upsert(
            listOf(
                HandleEntity(address = addressA, service = "iMessage"),
                HandleEntity(address = addressB, service = "iMessage"),
            )
        )
        db.handleDao().upsertChatHandleCrossRefs(
            listOf(
                ChatHandleCrossRef(chatGuid = "mixed", address = addressA),
                ChatHandleCrossRef(chatGuid = "mixed", address = addressB),
            )
        )
        db.contactDao().upsert(
            listOf(ContactEntity(address = addressA, displayName = "John", avatarPath = null))
        )

        val vm = viewModel()
        val state = awaitState(vm) { it is ChatListUiState.Loaded } as ChatListUiState.Loaded
        val item = state.items.single()
        assertEquals("John, $addressB", item.title)
        assertTrue(item.isGroup)
    }

    // --- chips row filtering --------------------------------------------------------------------

    private fun item(guid: String, style: Int = 45, unreadCount: Int = 0) = ChatListItem(
        guid = guid,
        title = guid,
        isGroup = style == 43,
        lastMessageDate = 1L,
        lastMessagePreview = null,
        style = style,
        unreadCount = unreadCount,
    )

    @Test
    fun `applyChatFilter keeps everything for All, unread-only for Unread, style 43 for Groups`() {
        val items = listOf(
            item("dm-unread", unreadCount = 3),
            item("dm-read"),
            item("group-unread", style = 43, unreadCount = 1),
            item("group-read", style = 43),
        )

        assertEquals(items, applyChatFilter(items, ChatFilter.All))
        assertEquals(
            listOf("dm-unread", "group-unread"),
            applyChatFilter(items, ChatFilter.Unread).map { it.guid },
        )
        assertEquals(
            listOf("group-unread", "group-read"),
            applyChatFilter(items, ChatFilter.Groups).map { it.guid },
        )
    }

    @Test
    fun `applyChatFilter preserves the repository's newest-first order`() {
        val items = listOf(
            item("newest", unreadCount = 1),
            item("middle"),
            item("oldest", unreadCount = 2),
        )

        assertEquals(
            listOf("newest", "oldest"),
            applyChatFilter(items, ChatFilter.Unread).map { it.guid },
        )
    }

    @Test
    fun `selecting a chip re-emits Loaded filtered to that chip`() = runBlocking {
        db.chatDao().upsert(
            listOf(
                chat("dm", lastMessageDate = 300L, displayName = "Maya", unreadCount = 2),
                chat("group", style = 43, lastMessageDate = 200L, displayName = "Ski trip"),
                chat("quiet", lastMessageDate = 100L, displayName = "Dad"),
            )
        )
        val vm = viewModel()
        val all = awaitState(vm) { it is ChatListUiState.Loaded } as ChatListUiState.Loaded
        assertEquals(listOf("dm", "group", "quiet"), all.items.map { it.guid })
        assertEquals(ChatFilter.All, all.filter)
        assertEquals(2, all.items.first().unreadCount)

        vm.onFilterSelected(ChatFilter.Unread)
        val unread = awaitState(vm) {
            it is ChatListUiState.Loaded && it.filter == ChatFilter.Unread
        } as ChatListUiState.Loaded
        assertEquals(listOf("dm"), unread.items.map { it.guid })

        vm.onFilterSelected(ChatFilter.Groups)
        val groups = awaitState(vm) {
            it is ChatListUiState.Loaded && it.filter == ChatFilter.Groups
        } as ChatListUiState.Loaded
        assertEquals(listOf("group"), groups.items.map { it.guid })

        vm.onFilterSelected(ChatFilter.All)
        val back = awaitState(vm) {
            it is ChatListUiState.Loaded && it.filter == ChatFilter.All
        } as ChatListUiState.Loaded
        assertEquals(listOf("dm", "group", "quiet"), back.items.map { it.guid })
    }

    @Test
    fun `a filter that matches nothing stays Loaded with no items so the chips row survives`() =
        runBlocking {
            db.chatDao().upsert(listOf(chat("quiet", lastMessageDate = 100L, displayName = "Dad")))
            val vm = viewModel()
            awaitState(vm) { it is ChatListUiState.Loaded }

            vm.onFilterSelected(ChatFilter.Unread)

            val state = awaitState(vm) {
                it is ChatListUiState.Loaded && it.filter == ChatFilter.Unread
            } as ChatListUiState.Loaded
            assertTrue(state.items.isEmpty())
        }

    @Test
    fun `an account with no chats is Empty regardless of the selected chip`() = runBlocking {
        val vm = viewModel()
        awaitState(vm) { it is ChatListUiState.Empty }

        vm.onFilterSelected(ChatFilter.Groups)

        assertEquals(
            ChatListUiState.Empty(syncStatus = SyncStatus.Idle),
            awaitState(vm) { it is ChatListUiState.Empty },
        )
    }

    @Test
    fun `unread count from the entity surfaces on the loaded item`() = runBlocking {
        db.chatDao().upsert(listOf(chat("dm", lastMessageDate = 1L, displayName = "Maya")))
        val vm = viewModel()
        val initial = awaitState(vm) { it is ChatListUiState.Loaded } as ChatListUiState.Loaded
        assertEquals(0, initial.items.single().unreadCount)

        db.chatDao().incrementUnread("dm")

        val badged = awaitState(vm) {
            it is ChatListUiState.Loaded && it.items.single().unreadCount == 1
        } as ChatListUiState.Loaded
        assertEquals(1, badged.items.single().unreadCount)

        db.chatDao().clearUnread("dm")

        val cleared = awaitState(vm) {
            it is ChatListUiState.Loaded && it.items.single().unreadCount == 0
        } as ChatListUiState.Loaded
        assertEquals(0, cleared.items.single().unreadCount)
    }
}
