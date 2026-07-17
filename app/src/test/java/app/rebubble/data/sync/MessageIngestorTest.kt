package app.rebubble.data.sync

import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.ChatEntity
import app.rebubble.data.local.entity.DownloadState
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.data.remote.dto.AttachmentDto
import app.rebubble.data.remote.dto.ChatDto
import app.rebubble.data.remote.dto.HandleDto
import app.rebubble.data.remote.dto.MessageDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MessageIngestor] is the idempotent convergence point where every inbound path (socket, FCM,
 * reconcile, send-ack, backfill) meets Room. These tests exercise the four per-message outcomes
 * (insert / merge / temp-guid swap / skip), send-ack↔echo convergence, non-regression of server
 * dates, attachment/localPath preservation, prefix normalization, chat-preview denormalization,
 * `insertedGuids`/`maxRowId` accounting, and single-transaction batch atomicity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class MessageIngestorTest {

    private lateinit var db: RebubbleDatabase
    private lateinit var ingestor: MessageIngestor

    @Before
    fun setUp() {
        db = InMemoryDatabaseFactory.create()
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

    // --- builders -------------------------------------------------------------------------------

    private fun handleDto(address: String = "+15551234567", service: String = "iMessage") =
        HandleDto(address = address, service = service)

    private fun chatDto(
        guid: String = "chat-1",
        style: Int = 45,
        chatIdentifier: String = "+15551234567",
        displayName: String? = null,
    ) = ChatDto(guid = guid, style = style, chatIdentifier = chatIdentifier, displayName = displayName)

    private fun attachmentDto(guid: String) = AttachmentDto(
        guid = guid,
        uti = "public.jpeg",
        mimeType = "image/jpeg",
        transferName = "photo.jpg",
        totalBytes = 1024L,
        height = 100,
        width = 100,
        hasLivePhoto = false,
    )

    private fun messageDto(
        guid: String,
        tempGuid: String? = null,
        text: String? = "hello",
        chats: List<ChatDto>? = listOf(chatDto()),
        isFromMe: Boolean = false,
        dateCreated: Long? = 1000L,
        dateRead: Long? = null,
        dateDelivered: Long? = null,
        originalRowId: Long? = null,
        handle: HandleDto? = handleDto(),
        attachments: List<AttachmentDto> = emptyList(),
        associatedMessageGuid: String? = null,
        associatedMessageType: String? = null,
    ) = MessageDto(
        originalRowId = originalRowId,
        tempGuid = tempGuid,
        guid = guid,
        text = text,
        chats = chats,
        isFromMe = isFromMe,
        dateCreated = dateCreated,
        dateRead = dateRead,
        dateDelivered = dateDelivered,
        handle = handle,
        attachments = attachments,
        associatedMessageGuid = associatedMessageGuid,
        associatedMessageType = associatedMessageType,
    )

    private fun seedMessage(
        guid: String,
        chatGuid: String = "chat-1",
        sendStatus: SendStatus = SendStatus.SENDING,
        isFromMe: Boolean = true,
        dateCreated: Long = 1000L,
        originalRowId: Long? = null,
    ) = MessageEntity(
        guid = guid,
        chatGuid = chatGuid,
        originalRowId = originalRowId,
        text = "draft",
        subject = null,
        isFromMe = isFromMe,
        senderAddress = null,
        dateCreated = dateCreated,
        dateRead = null,
        dateDelivered = null,
        itemType = 0,
        groupActionType = 0,
        groupTitle = null,
        associatedMessageGuid = null,
        associatedMessageType = null,
        threadOriginatorGuid = null,
        expressiveSendStyleId = null,
        dateEdited = null,
        dateRetracted = null,
        sendStatus = sendStatus,
    )

    private suspend fun messageCount(chatGuid: String = "chat-1"): Int =
        db.messageDao().observeMessages(chatGuid, limit = 1000).first().size

    // --- 1. fresh insert ------------------------------------------------------------------------

    @Test
    fun `fresh insert lands the row, reports it, and denormalizes the chat preview`() = runBlocking {
        val result = ingestor.ingest(listOf(messageDto("m1", text = "hi there")), IngestSource.SOCKET)

        val row = db.messageDao().getByGuid("m1")
        assertNotNull(row)
        assertEquals("hi there", row?.text)
        assertEquals(listOf("m1"), result.insertedGuids)
        assertEquals(0, result.updated)

        val chat = db.chatDao().getByGuid("chat-1")
        assertEquals("hi there", chat?.lastMessagePreview)
        assertEquals(1000L, chat?.lastMessageDate)
    }

    // --- 2. duplicate guid merges, no duplicate row ---------------------------------------------

    @Test
    fun `re-ingesting the same guid merges into one row and counts as updated`() = runBlocking {
        ingestor.ingest(listOf(messageDto("m1")), IngestSource.SOCKET)
        val result = ingestor.ingest(listOf(messageDto("m1", text = "edited")), IngestSource.SOCKET)

        assertEquals(1, messageCount())
        assertEquals(emptyList<String>(), result.insertedGuids)
        assertEquals(1, result.updated)
        assertEquals("edited", db.messageDao().getByGuid("m1")?.text)
    }

    // --- 3. temp-guid swap ----------------------------------------------------------------------

    @Test
    fun `temp-guid dto swaps the optimistic row to the real guid and marks it SENT`() = runBlocking {
        db.messageDao().insertAll(listOf(seedMessage("temp-abc")))

        val result = ingestor.ingest(
            // No chats[] here: chat must be resolved from the existing optimistic row.
            listOf(messageDto("REAL-1", tempGuid = "temp-abc", chats = null, isFromMe = true, originalRowId = 601L)),
            IngestSource.SOCKET,
        )

        assertNull(db.messageDao().getByGuid("temp-abc"))
        val swapped = db.messageDao().getByGuid("REAL-1")
        assertNotNull(swapped)
        assertEquals(SendStatus.SENT, swapped?.sendStatus)
        assertEquals(601L, swapped?.originalRowId)
        assertEquals("chat-1", swapped?.chatGuid)
        assertEquals(1, result.updated)
        assertEquals(emptyList<String>(), result.insertedGuids)
    }

    @Test
    fun `temp-guid swap reparents attachments onto the real guid`() = runBlocking {
        db.messageDao().insertAll(listOf(seedMessage("temp-abc")))
        db.attachmentDao().insertAll(
            listOf(
                AttachmentEntity(
                    guid = "att-1",
                    messageGuid = "temp-abc",
                    uti = null, mimeType = null, transferName = null, totalBytes = null,
                    width = null, height = null, hasLivePhoto = false,
                    localPath = "/cache/att-1.jpg", downloadState = DownloadState.DOWNLOADED,
                )
            )
        )

        ingestor.ingest(
            listOf(messageDto("REAL-1", tempGuid = "temp-abc", chats = null, isFromMe = true)),
            IngestSource.SOCKET,
        )

        val att = db.attachmentDao().getByGuid("att-1")
        assertEquals("REAL-1", att?.messageGuid)
        assertEquals("/cache/att-1.jpg", att?.localPath)
    }

    // --- 4. echo <-> ack convergence ------------------------------------------------------------

    @Test
    fun `echo then ack converge to a single SENT row`() = runBlocking {
        db.messageDao().insertAll(listOf(seedMessage("temp-abc")))

        // Socket echo carries tempGuid -> swap.
        ingestor.ingest(
            listOf(messageDto("REAL-1", tempGuid = "temp-abc", isFromMe = true)),
            IngestSource.SOCKET,
        )
        // REST send-ack for the same real guid -> merge, not a second insert.
        ingestor.ingest(
            listOf(messageDto("REAL-1", tempGuid = "temp-abc", isFromMe = true, chats = null)),
            IngestSource.SEND_ACK,
            fallbackChatGuid = "chat-1",
        )

        assertEquals(1, messageCount())
        assertNull(db.messageDao().getByGuid("temp-abc"))
        assertEquals(SendStatus.SENT, db.messageDao().getByGuid("REAL-1")?.sendStatus)
    }

    @Test
    fun `ack then echo converge to a single SENT row`() = runBlocking {
        db.messageDao().insertAll(listOf(seedMessage("temp-abc")))

        // REST send-ack arrives first, carrying tempGuid -> swap.
        ingestor.ingest(
            listOf(messageDto("REAL-1", tempGuid = "temp-abc", isFromMe = true, chats = null)),
            IngestSource.SEND_ACK,
            fallbackChatGuid = "chat-1",
        )
        // Socket echo arrives second -> merge onto the now-real row.
        ingestor.ingest(
            listOf(messageDto("REAL-1", tempGuid = "temp-abc", isFromMe = true)),
            IngestSource.SOCKET,
        )

        assertEquals(1, messageCount())
        assertNull(db.messageDao().getByGuid("temp-abc"))
        assertEquals(SendStatus.SENT, db.messageDao().getByGuid("REAL-1")?.sendStatus)
    }

    // --- 5. dates never regress -----------------------------------------------------------------

    @Test
    fun `dateRead is retained when a later ingest carries null`() = runBlocking {
        ingestor.ingest(listOf(messageDto("m1", dateRead = 5000L)), IngestSource.SOCKET)
        ingestor.ingest(listOf(messageDto("m1", dateRead = null)), IngestSource.SOCKET)

        assertEquals(5000L, db.messageDao().getByGuid("m1")?.dateRead)
    }

    // --- 6. attachment upsert preserves local download state ------------------------------------

    @Test
    fun `re-ingesting an attachment preserves a locally-set localPath and downloadState`() = runBlocking {
        ingestor.ingest(
            listOf(messageDto("m1", attachments = listOf(attachmentDto("att-1")))),
            IngestSource.SOCKET,
        )
        // Simulate the downloader persisting the file locally.
        val downloaded = db.attachmentDao().getByGuid("att-1")!!
            .copy(localPath = "/cache/att-1.jpg", downloadState = DownloadState.DOWNLOADED)
        db.attachmentDao().update(downloaded)

        // A later server refresh re-ingests the same message + attachment (no local info).
        ingestor.ingest(
            listOf(messageDto("m1", attachments = listOf(attachmentDto("att-1")))),
            IngestSource.SOCKET,
        )

        val att = db.attachmentDao().getByGuid("att-1")
        assertEquals("/cache/att-1.jpg", att?.localPath)
        assertEquals(DownloadState.DOWNLOADED, att?.downloadState)
    }

    // --- 7. prefix stripping + empty type normalization -----------------------------------------

    @Test
    fun `associatedMessageGuid prefixes are stripped and empty associatedMessageType becomes null`() = runBlocking {
        ingestor.ingest(
            listOf(
                messageDto("r1", associatedMessageGuid = "p:0/TARGET-1", associatedMessageType = "love"),
                messageDto("r2", associatedMessageGuid = "bp:TARGET-2", associatedMessageType = "like"),
                messageDto("r3", associatedMessageType = ""),
            ),
            IngestSource.SOCKET,
        )

        assertEquals("TARGET-1", db.messageDao().getByGuid("r1")?.associatedMessageGuid)
        assertEquals("TARGET-2", db.messageDao().getByGuid("r2")?.associatedMessageGuid)
        assertNull(db.messageDao().getByGuid("r3")?.associatedMessageType)
    }

    // --- 8. chat preview denormalization: only advances ------------------------------------------

    @Test
    fun `a newer message advances the preview but an older backfilled one does not`() = runBlocking {
        ingestor.ingest(
            listOf(messageDto("m1", text = "newer", dateCreated = 200L)),
            IngestSource.SOCKET,
        )
        ingestor.ingest(
            listOf(messageDto("m2", text = "older", dateCreated = 100L)),
            IngestSource.BACKFILL,
        )

        val chat = db.chatDao().getByGuid("chat-1")
        assertEquals("newer", chat?.lastMessagePreview)
        assertEquals(200L, chat?.lastMessageDate)
    }

    // --- 9. insertedGuids excludes swaps; maxRowId is the batch max ------------------------------

    @Test
    fun `insertedGuids excludes swaps and maxRowId is the batch maximum`() = runBlocking {
        db.messageDao().insertAll(listOf(seedMessage("temp-abc")))

        val result = ingestor.ingest(
            listOf(
                messageDto("REAL-1", tempGuid = "temp-abc", isFromMe = true, originalRowId = 50L),
                messageDto("n1", isFromMe = false, originalRowId = 99L),
            ),
            IngestSource.SOCKET,
        )

        assertEquals(listOf("n1"), result.insertedGuids)
        assertEquals(1, result.updated)
        assertEquals(99L, result.maxRowId)
    }

    // --- 10. existing chat row is never clobbered -----------------------------------------------

    @Test
    fun `ingesting a message never clobbers an existing chat's denormalized fields`() = runBlocking {
        db.chatDao().upsert(
            listOf(
                ChatEntity(
                    guid = "chat-1",
                    style = 45,
                    chatIdentifier = "+15551234567",
                    displayName = "My Chat",
                    isArchived = false,
                    lastMessageDate = 500L,
                    lastMessagePreview = "existing preview",
                )
            )
        )

        // Message is older than the chat's current preview; its chats[] carries a different name.
        ingestor.ingest(
            listOf(
                messageDto(
                    "m1",
                    text = "should not overwrite preview",
                    dateCreated = 200L,
                    chats = listOf(chatDto(displayName = "CLOBBERED")),
                )
            ),
            IngestSource.SOCKET,
        )

        val chat = db.chatDao().getByGuid("chat-1")
        assertEquals("My Chat", chat?.displayName)
        assertEquals("existing preview", chat?.lastMessagePreview)
        assertEquals(500L, chat?.lastMessageDate)
        // The message itself still landed.
        assertNotNull(db.messageDao().getByGuid("m1"))
    }

    // --- 11. batch atomicity: unroutable dto skipped, rest lands --------------------------------

    @Test
    fun `an unroutable dto is skipped without partial writes while the rest of the batch lands`() = runBlocking {
        db.chatDao().insertIgnore(
            listOf(ChatEntity("chat-1", 45, "+15551234567", null, false, null, null))
        )

        val result = ingestor.ingest(
            listOf(
                messageDto("good", attachments = listOf(attachmentDto("att-good"))),
                // No chats[] and (below) no fallback -> unroutable.
                messageDto("bad", chats = null, attachments = listOf(attachmentDto("att-bad"))),
            ),
            IngestSource.SOCKET,
        )

        assertNotNull(db.messageDao().getByGuid("good"))
        assertNull(db.messageDao().getByGuid("bad"))
        assertNotNull(db.attachmentDao().getByGuid("att-good"))
        assertNull(db.attachmentDao().getByGuid("att-bad"))
        assertEquals(listOf("good"), result.insertedGuids)
        assertTrue("bad" !in result.insertedGuids)
    }
}
