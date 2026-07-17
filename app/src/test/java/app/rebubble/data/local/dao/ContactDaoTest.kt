package app.rebubble.data.local.dao

import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [ContactDao] covers: `upsert` replaces on conflict, `observeContacts` streams current rows. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ContactDaoTest {

    private lateinit var db: RebubbleDatabase
    private lateinit var dao: ContactDao

    @Before
    fun setUp() {
        db = InMemoryDatabaseFactory.create()
        dao = db.contactDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `observeContacts emits upserted contacts, replacing on conflict`() = runBlocking {
        dao.upsert(listOf(ContactEntity(address = "+15551234567", displayName = "Alice", avatarPath = null)))
        dao.upsert(
            listOf(ContactEntity(address = "+15551234567", displayName = "Alice Updated", avatarPath = "/a.png"))
        )

        val result = dao.observeContacts().first()

        assertEquals(1, result.size)
        assertEquals("Alice Updated", result.single().displayName)
        assertEquals("/a.png", result.single().avatarPath)
    }
}
