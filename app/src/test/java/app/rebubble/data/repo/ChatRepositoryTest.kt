package app.rebubble.data.repo

import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.dao.ChatParticipantRow
import app.rebubble.data.local.dao.HandleDao
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.ChatHandleCrossRef
import app.rebubble.data.local.entity.ContactEntity
import app.rebubble.data.local.entity.HandleEntity
import app.rebubble.data.remote.dto.ChatDto
import app.rebubble.data.remote.dto.HandleDto
import app.rebubble.data.remote.dto.MessageDto
import app.rebubble.data.sync.IngestSource
import app.rebubble.data.sync.MessageIngestor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ChatRepository.observeChats] is the chat-list read path: title resolution fallbacks, group
 * flag from style 43, and reactive re-emission when Room chat rows or contact names change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ChatRepositoryTest {

    private lateinit var db: RebubbleDatabase
    private lateinit var repo: ChatRepository
    private lateinit var ingestor: MessageIngestor

    @Before
    fun setUp() {
        db = InMemoryDatabaseFactory.create()
        repo = ChatRepository(
            chatDao = db.chatDao(),
            handleDao = db.handleDao(),
            contactDao = db.contactDao(),
        )
        ingestor = MessageIngestor(
            db = db,
            messageDao = db.messageDao(),
            chatDao = db.chatDao(),
            attachmentDao = db.attachmentDao(),
            handleDao = db.handleDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun chat(
        guid: String,
        style: Int = 45,
        chatIdentifier: String? = "+15550001111",
        displayName: String? = null,
        lastMessageDate: Long? = null,
        lastMessagePreview: String? = null,
    ) = ChatEntity(
        guid = guid,
        style = style,
        chatIdentifier = chatIdentifier,
        displayName = displayName,
        isArchived = false,
        lastMessageDate = lastMessageDate,
        lastMessagePreview = lastMessagePreview,
    )

    private suspend fun seedParticipants(chatGuid: String, vararg addresses: String) {
        db.handleDao().upsert(addresses.map { HandleEntity(address = it, service = "iMessage") })
        db.handleDao().upsertChatHandleCrossRefs(
            addresses.map { ChatHandleCrossRef(chatGuid = chatGuid, address = it) },
        )
    }

    /**
     * Collects [ChatRepository.observeChats] into a channel so tests can assert successive
     * emissions without Turbine (not on the classpath).
     */
    private suspend fun <T> collectEmissions(
        flow: Flow<T>,
        block: suspend (Channel<T>) -> Unit,
    ) {
        val channel = Channel<T>(Channel.UNLIMITED)
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            flow.collect { channel.send(it) }
        }
        try {
            block(channel)
        } finally {
            job.cancel()
            channel.close()
        }
    }

    private suspend fun Channel<List<ChatListItem>>.next(): List<ChatListItem> =
        withTimeout(5_000) { receive() }

    // --- 1. reactive emit on ingest -------------------------------------------------------------

    @Test
    fun `observeChats re-emits when a message is ingested, updating order and preview`() = runBlocking {
        db.chatDao().upsert(
            listOf(
                chat("chat-old", lastMessageDate = 100L, lastMessagePreview = "older"),
                chat("chat-new", lastMessageDate = 50L, lastMessagePreview = "stale"),
            )
        )

        collectEmissions(repo.observeChats()) { emissions ->
            val first = emissions.next()
            assertEquals(listOf("chat-old", "chat-new"), first.map { it.guid })
            assertEquals("older", first[0].lastMessagePreview)

            ingestor.ingest(
                listOf(
                    MessageDto(
                        guid = "m-fresh",
                        text = "brand new",
                        chats = listOf(
                            ChatDto(guid = "chat-new", style = 45, chatIdentifier = "+15550001111"),
                        ),
                        isFromMe = false,
                        dateCreated = 200L,
                        handle = HandleDto(address = "+15550001111", service = "iMessage"),
                    )
                ),
                IngestSource.SOCKET,
            )

            // Ingest may invalidate chats and chat_handles separately; wait for the preview bump.
            val second = withTimeout(5_000) {
                var latest: List<ChatListItem>
                do {
                    latest = emissions.next()
                } while (
                    latest.firstOrNull()?.guid != "chat-new" ||
                        latest.first { it.guid == "chat-new" }.lastMessagePreview != "brand new"
                )
                latest
            }
            assertEquals(listOf("chat-new", "chat-old"), second.map { it.guid })
            assertEquals(200L, second.first { it.guid == "chat-new" }.lastMessageDate)
        }
    }

    // --- 2. title resolution fallbacks ----------------------------------------------------------

    @Test
    fun `title uses displayName when non-blank, else contact names, else addresses, and style 43 is group`() =
        runBlocking {
            db.chatDao().upsert(
                listOf(
                    chat("named", displayName = "Family Chat", style = 43, chatIdentifier = "chat.family"),
                    chat("contacts", displayName = null, style = 45, chatIdentifier = "+15551110001"),
                    chat("addresses", displayName = "  ", style = 45, chatIdentifier = "+15552220002"),
                )
            )
            seedParticipants("contacts", "+15551110001", "+15551110002")
            seedParticipants("addresses", "+15552220002", "+15552220003")
            db.contactDao().upsert(
                listOf(
                    ContactEntity(address = "+15551110001", displayName = "Alice", avatarPath = null),
                    ContactEntity(address = "+15551110002", displayName = "Bob", avatarPath = null),
                )
            )

            collectEmissions(repo.observeChats()) { emissions ->
                val items = emissions.next().associateBy { it.guid }

                assertEquals("Family Chat", items.getValue("named").title)
                assertTrue(items.getValue("named").isGroup)
                assertEquals(43, items.getValue("named").style)

                assertEquals("Alice, Bob", items.getValue("contacts").title)
                assertFalse(items.getValue("contacts").isGroup)

                assertEquals("+15552220002, +15552220003", items.getValue("addresses").title)
                assertFalse(items.getValue("addresses").isGroup)
            }
        }

    // --- 3. mixed contact + unresolved address --------------------------------------------------

    @Test
    fun `title mixes contact displayName with unresolved participant address`() = runBlocking {
        val addressA = "+15554440001"
        val addressB = "+15554440002"
        db.chatDao().upsert(listOf(chat("mixed", displayName = null, chatIdentifier = addressA)))
        seedParticipants("mixed", addressA, addressB)
        db.contactDao().upsert(
            listOf(ContactEntity(address = addressA, displayName = "John", avatarPath = null))
        )

        collectEmissions(repo.observeChats()) { emissions ->
            val item = emissions.next().single()
            assertEquals("John, $addressB", item.title)
        }
    }

    // --- 4. contact-name change propagates ------------------------------------------------------

    @Test
    fun `upserting a ContactEntity after the first emission updates the chat title`() = runBlocking {
        db.chatDao().upsert(listOf(chat("chat-1", displayName = null, chatIdentifier = "+15553330001")))
        seedParticipants("chat-1", "+15553330001")

        collectEmissions(repo.observeChats()) { emissions ->
            val first = emissions.next()
            assertEquals("+15553330001", first.single().title)

            db.contactDao().upsert(
                listOf(ContactEntity(address = "+15553330001", displayName = "Carol", avatarPath = null))
            )

            val second = emissions.next()
            assertEquals("Carol", second.single().title)
        }
    }

    // --- 5. N+1 fix: one join query, no per-chat participantsFor --------------------------------

    /**
     * Delegates to a real [HandleDao] while counting [HandleDao.participantsFor] calls so tests can
     * prove [ChatRepository] no longer N+1s on every emission.
     */
    private class CountingHandleDao(
        private val real: HandleDao,
    ) : HandleDao by real {
        val participantsForCalls = AtomicInteger(0)

        override suspend fun participantsFor(chatGuid: String): List<HandleEntity> {
            participantsForCalls.incrementAndGet()
            return real.participantsFor(chatGuid)
        }
    }

    @Test
    fun `observeAllChatParticipants returns every chat-handle join in one call`() = runBlocking {
        db.chatDao().upsert(
            listOf(
                chat("chat-a"),
                chat("chat-b"),
            )
        )
        seedParticipants("chat-a", "+15550000001", "+15550000002")
        seedParticipants("chat-b", "+15550000003")

        val rows = db.handleDao().observeAllChatParticipants().first()

        assertEquals(
            setOf(
                ChatParticipantRow(chatGuid = "chat-a", address = "+15550000001", service = "iMessage"),
                ChatParticipantRow(chatGuid = "chat-a", address = "+15550000002", service = "iMessage"),
                ChatParticipantRow(chatGuid = "chat-b", address = "+15550000003", service = "iMessage"),
            ),
            rows.toSet(),
        )
    }

    @Test
    fun `observeChats does not call participantsFor per chat and keeps mixed title resolution`() =
        runBlocking {
            val counting = CountingHandleDao(db.handleDao())
            val instrumentedRepo = ChatRepository(
                chatDao = db.chatDao(),
                handleDao = counting,
                contactDao = db.contactDao(),
            )
            val addressA = "+15554440001"
            val addressB = "+15554440002"
            db.chatDao().upsert(listOf(chat("mixed", displayName = null, chatIdentifier = addressA)))
            seedParticipants("mixed", addressA, addressB)
            db.contactDao().upsert(
                listOf(ContactEntity(address = addressA, displayName = "John", avatarPath = null))
            )

            collectEmissions(instrumentedRepo.observeChats()) { emissions ->
                val item = emissions.next().single()
                assertEquals("John, $addressB", item.title)
                assertEquals(
                    "participantsFor must not be used on the list path",
                    0,
                    counting.participantsForCalls.get(),
                )
            }
        }

    @Test
    fun `with 200 chats one ingest emission does not issue 200 participantsFor queries`() =
        runBlocking {
            val counting = CountingHandleDao(db.handleDao())
            val instrumentedRepo = ChatRepository(
                chatDao = db.chatDao(),
                handleDao = counting,
                contactDao = db.contactDao(),
            )

            val chats = (1..200).map { i ->
                chat(
                    guid = "chat-$i",
                    chatIdentifier = "+1555${i.toString().padStart(7, '0')}",
                    lastMessageDate = i.toLong(),
                    lastMessagePreview = "msg $i",
                )
            }
            db.chatDao().upsert(chats)
            for (i in 1..200) {
                val address = "+1555${i.toString().padStart(7, '0')}"
                seedParticipants("chat-$i", address)
            }

            collectEmissions(instrumentedRepo.observeChats()) { emissions ->
                emissions.next() // initial
                counting.participantsForCalls.set(0)

                ingestor.ingest(
                    listOf(
                        MessageDto(
                            guid = "m-latency",
                            text = "bump",
                            chats = listOf(
                                ChatDto(guid = "chat-1", style = 45, chatIdentifier = "+15550000001"),
                            ),
                            isFromMe = false,
                            dateCreated = 10_000L,
                            handle = HandleDto(address = "+15550000001", service = "iMessage"),
                        )
                    ),
                    IngestSource.SOCKET,
                )

                val after = emissions.next()
                assertEquals("chat-1", after.first().guid)
                assertEquals(
                    "one ingest must not N+1 participantsFor across 200 chats",
                    0,
                    counting.participantsForCalls.get(),
                )
            }
        }
}
