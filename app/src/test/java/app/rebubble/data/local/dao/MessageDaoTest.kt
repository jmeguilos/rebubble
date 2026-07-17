package app.rebubble.data.local.dao

import android.database.sqlite.SQLiteConstraintException
import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MessageDao] covers: `observeMessages` filters reaction rows (`associatedMessageType` set) but
 * keeps group-event rows (`itemType != 0`), newest-first, respecting `limit`; `insertAll` ABORTs
 * on a guid conflict; `swapGuid`'s raw PK-swap query; `oldestDateCreated`/`maxOriginalRowId`
 * aggregates used by the reconciler/backfill (T6/T8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class MessageDaoTest {

    private lateinit var db: RebubbleDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        db = InMemoryDatabaseFactory.create()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun message(
        guid: String,
        chatGuid: String = "chat-1",
        dateCreated: Long,
        itemType: Int = 0,
        associatedMessageType: String? = null,
        originalRowId: Long? = null,
        sendStatus: SendStatus = SendStatus.SENT,
    ) = MessageEntity(
        guid = guid,
        chatGuid = chatGuid,
        originalRowId = originalRowId,
        text = "text-$guid",
        subject = null,
        isFromMe = false,
        senderAddress = "+15551234567",
        dateCreated = dateCreated,
        dateRead = null,
        dateDelivered = null,
        itemType = itemType,
        groupActionType = 0,
        groupTitle = null,
        associatedMessageGuid = null,
        associatedMessageType = associatedMessageType,
        threadOriginatorGuid = null,
        expressiveSendStyleId = null,
        dateEdited = null,
        dateRetracted = null,
        sendStatus = sendStatus,
    )

    @Test
    fun `observeMessages filters reaction rows but keeps group event rows, newest first`() = runBlocking {
        dao.insertAll(
            listOf(
                message("m1", dateCreated = 100L),
                message("reaction", dateCreated = 150L, associatedMessageType = "love"),
                message("group-event", dateCreated = 200L, itemType = 2),
                message("m2", dateCreated = 300L),
            )
        )

        val result = dao.observeMessages("chat-1", limit = 10).first()

        assertEquals(listOf("m2", "group-event", "m1"), result.map { it.guid })
    }

    @Test
    fun `observeMessages respects the limit`() = runBlocking {
        dao.insertAll(
            listOf(
                message("m1", dateCreated = 100L),
                message("m2", dateCreated = 200L),
                message("m3", dateCreated = 300L),
            )
        )

        val result = dao.observeMessages("chat-1", limit = 2).first()

        assertEquals(listOf("m3", "m2"), result.map { it.guid })
    }

    @Test
    fun `insertAll aborts on a guid conflict`() {
        runBlocking { dao.insertAll(listOf(message("m1", dateCreated = 100L))) }

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.insertAll(listOf(message("m1", dateCreated = 999L))) }
        }
    }

    @Test
    fun `swapGuid moves the primary key and sets originalRowId and sendStatus`() = runBlocking {
        dao.insertAll(listOf(message("temp-abc123", dateCreated = 100L, sendStatus = SendStatus.SENDING)))

        dao.swapGuid("temp-abc123", "real-guid-1", originalRowId = 42L, sendStatus = SendStatus.SENT)

        assertNull(dao.getByGuid("temp-abc123"))
        val swapped = dao.getByGuid("real-guid-1")
        assertEquals("real-guid-1", swapped?.guid)
        assertEquals(42L, swapped?.originalRowId)
        assertEquals(SendStatus.SENT, swapped?.sendStatus)
    }

    @Test
    fun `oldestDateCreated returns the minimum dateCreated for a chat, or null when empty`() = runBlocking {
        assertNull(dao.oldestDateCreated("chat-1"))

        dao.insertAll(
            listOf(
                message("m1", dateCreated = 300L),
                message("m2", dateCreated = 100L),
                message("m3", dateCreated = 200L),
            )
        )

        assertEquals(100L, dao.oldestDateCreated("chat-1"))
    }

    @Test
    fun `maxOriginalRowId returns the largest originalRowId across all messages, or null when empty`() = runBlocking {
        assertNull(dao.maxOriginalRowId())

        dao.insertAll(
            listOf(
                message("m1", dateCreated = 100L, originalRowId = 5L),
                message("m2", dateCreated = 200L, originalRowId = 20L),
                message("m3", dateCreated = 300L, originalRowId = null),
            )
        )

        assertEquals(20L, dao.maxOriginalRowId())
    }

    @Test
    fun `update overwrites message fields for an existing guid`() = runBlocking {
        dao.insertAll(listOf(message("m1", dateCreated = 100L)))

        val updated = dao.getByGuid("m1")!!.copy(text = "edited", dateEdited = 500L)
        dao.update(updated)

        val result = dao.getByGuid("m1")
        assertEquals("edited", result?.text)
        assertEquals(500L, result?.dateEdited)
    }
}
