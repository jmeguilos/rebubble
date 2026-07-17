package app.rebubble.data.local.dao

import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.ChatEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ChatDao] is Room's DAO for [ChatEntity]. Covers: `observeChats` ordering (newest first, a
 * NULL `lastMessageDate` sorts last), `getByGuid`, `upsert` conflict semantics, and
 * `updatePreview`'s "only if newer" SQL guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ChatDaoTest {

    private lateinit var db: RebubbleDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        db = InMemoryDatabaseFactory.create()
        dao = db.chatDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun chat(
        guid: String,
        lastMessageDate: Long?,
        preview: String? = null,
    ) = ChatEntity(
        guid = guid,
        style = 45,
        chatIdentifier = "id-$guid",
        displayName = null,
        lastMessageDate = lastMessageDate,
        lastMessagePreview = preview,
    )

    @Test
    fun `observeChats orders by lastMessageDate DESC with a null-date chat last`() = runBlocking {
        dao.upsert(
            listOf(
                chat("older", 100L),
                chat("no-date", null),
                chat("newest", 300L),
                chat("middle", 200L),
            )
        )

        val result = dao.observeChats().first()

        assertEquals(listOf("newest", "middle", "older", "no-date"), result.map { it.guid })
    }

    @Test
    fun `getByGuid returns the matching chat or null when missing`() = runBlocking {
        dao.upsert(listOf(chat("a", 1L)))

        assertEquals("a", dao.getByGuid("a")?.guid)
        assertNull(dao.getByGuid("missing"))
    }

    @Test
    fun `upsert replaces an existing chat on guid conflict`() = runBlocking {
        dao.upsert(listOf(chat("a", 1L, "first")))
        dao.upsert(listOf(chat("a", 2L, "second")))

        val result = dao.getByGuid("a")
        assertEquals(2L, result?.lastMessageDate)
        assertEquals("second", result?.lastMessagePreview)
    }

    @Test
    fun `updatePreview only advances lastMessageDate and preview when the new date is newer`() = runBlocking {
        dao.upsert(listOf(chat("a", 100L, "old preview")))

        dao.updatePreview("a", 50L, "stale")
        var result = dao.getByGuid("a")
        assertEquals(100L, result?.lastMessageDate)
        assertEquals("old preview", result?.lastMessagePreview)

        dao.updatePreview("a", 150L, "fresh")
        result = dao.getByGuid("a")
        assertEquals(150L, result?.lastMessageDate)
        assertEquals("fresh", result?.lastMessagePreview)
    }

    @Test
    fun `updatePreview applies when the existing lastMessageDate is null`() = runBlocking {
        dao.upsert(listOf(chat("a", null)))

        dao.updatePreview("a", 10L, "first message")

        val result = dao.getByGuid("a")
        assertEquals(10L, result?.lastMessageDate)
        assertEquals("first message", result?.lastMessagePreview)
    }
}
