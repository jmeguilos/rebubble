package app.rebubble.data.local.dao

import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.DownloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AttachmentDao] covers: `insertAll` uses IGNORE so a server-refresh re-insert never clobbers a
 * locally-set `localPath`/`downloadState` (callers do read-modify-write via `getByGuid` + `update`
 * when they *do* want to change those fields); `reparent` re-points attachments at a swapped
 * message guid; `observeForMessage` scopes to one message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class AttachmentDaoTest {

    private lateinit var db: RebubbleDatabase
    private lateinit var dao: AttachmentDao

    @Before
    fun setUp() {
        db = InMemoryDatabaseFactory.create()
        dao = db.attachmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun attachment(
        guid: String,
        messageGuid: String,
        localPath: String? = null,
        downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    ) = AttachmentEntity(
        guid = guid,
        messageGuid = messageGuid,
        uti = "public.jpeg",
        mimeType = "image/jpeg",
        transferName = "photo.jpg",
        totalBytes = 1024L,
        width = 100,
        height = 100,
        hasLivePhoto = false,
        localPath = localPath,
        downloadState = downloadState,
    )

    @Test
    fun `insertAll ignores conflicts, preserving the existing row's localPath and downloadState`() = runBlocking {
        dao.insertAll(
            listOf(attachment("a1", "m1", localPath = "/cache/a1.jpg", downloadState = DownloadState.DOWNLOADED))
        )

        // A later server refresh re-inserts the same guid without local download info.
        dao.insertAll(listOf(attachment("a1", "m1")))

        val result = dao.getByGuid("a1")
        assertEquals("/cache/a1.jpg", result?.localPath)
        assertEquals(DownloadState.DOWNLOADED, result?.downloadState)
    }

    @Test
    fun `update overwrites an existing attachment's fields`() = runBlocking {
        dao.insertAll(listOf(attachment("a1", "m1")))

        val updated = dao.getByGuid("a1")!!.copy(
            localPath = "/cache/a1.jpg",
            downloadState = DownloadState.DOWNLOADED,
        )
        dao.update(updated)

        val result = dao.getByGuid("a1")
        assertEquals("/cache/a1.jpg", result?.localPath)
        assertEquals(DownloadState.DOWNLOADED, result?.downloadState)
    }

    @Test
    fun `reparent moves attachments from one message guid to another`() = runBlocking {
        dao.insertAll(
            listOf(
                attachment("a1", "temp-guid"),
                attachment("a2", "temp-guid"),
                attachment("a3", "other-message"),
            )
        )

        dao.reparent("temp-guid", "real-guid")

        assertEquals("real-guid", dao.getByGuid("a1")?.messageGuid)
        assertEquals("real-guid", dao.getByGuid("a2")?.messageGuid)
        assertEquals("other-message", dao.getByGuid("a3")?.messageGuid)
    }

    @Test
    fun `observeForMessage streams only attachments for the given message`() = runBlocking {
        dao.insertAll(
            listOf(
                attachment("a1", "m1"),
                attachment("a2", "m1"),
                attachment("a3", "m2"),
            )
        )

        val result = dao.observeForMessage("m1").first()

        assertEquals(setOf("a1", "a2"), result.map { it.guid }.toSet())
    }
}
