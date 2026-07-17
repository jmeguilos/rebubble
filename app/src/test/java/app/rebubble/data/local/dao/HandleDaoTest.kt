package app.rebubble.data.local.dao

import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.ChatHandleCrossRef
import app.rebubble.data.local.entity.HandleEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [HandleDao] covers: `participantsFor` joins `handles` through `chat_handles` for a given chat. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class HandleDaoTest {

    private lateinit var db: RebubbleDatabase
    private lateinit var dao: HandleDao

    @Before
    fun setUp() {
        db = InMemoryDatabaseFactory.create()
        dao = db.handleDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `participantsFor joins handles through chat_handles for the given chat`() = runBlocking {
        dao.upsert(
            listOf(
                HandleEntity(address = "+15551234567", service = "iMessage"),
                HandleEntity(address = "+15559876543", service = "iMessage"),
                HandleEntity(address = "+15550000000", service = "iMessage"),
            )
        )
        dao.upsertChatHandleCrossRefs(
            listOf(
                ChatHandleCrossRef(chatGuid = "chat-1", address = "+15551234567"),
                ChatHandleCrossRef(chatGuid = "chat-1", address = "+15559876543"),
                ChatHandleCrossRef(chatGuid = "chat-2", address = "+15550000000"),
            )
        )

        val participants = dao.participantsFor("chat-1")

        assertEquals(
            setOf("+15551234567", "+15559876543"),
            participants.map { it.address }.toSet(),
        )
    }

    @Test
    fun `participantsFor returns an empty list for a chat with no participants`() = runBlocking {
        assertEquals(emptyList<HandleEntity>(), dao.participantsFor("no-such-chat"))
    }
}
