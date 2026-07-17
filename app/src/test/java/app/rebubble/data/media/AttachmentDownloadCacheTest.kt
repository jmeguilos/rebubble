package app.rebubble.data.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.DownloadState
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import app.rebubble.data.repo.AttachmentRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Attachment download + cache: streaming ensureDownloaded, single-flight concurrency,
 * failure cleanup, LRU eviction that never touches `filesDir/outbox/`, and Coil ImageLoader smoke.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class AttachmentDownloadCacheTest {

    private lateinit var context: Context
    private lateinit var db: RebubbleDatabase
    private lateinit var server: MockWebServer
    private lateinit var api: BlueBubblesApi
    private lateinit var downloader: AttachmentDownloader
    private lateinit var cache: AttachmentCache
    private lateinit var repo: AttachmentRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = InMemoryDatabaseFactory.create()
        server = MockWebServer()
        server.start()
        api = testBlueBubblesApi(
            FakeServerCredentialsProvider(
                urlValue = server.url("/").toString(),
                passwordValue = "pw",
            ),
        )
        downloader = AttachmentDownloader(context, api)
        cache = AttachmentCache.forTests(
            context = context,
            attachmentDao = db.attachmentDao(),
            maxBytes = AttachmentCache.DEFAULT_MAX_BYTES,
        )
        repo = AttachmentRepository(db.attachmentDao(), downloader, cache)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        File(context.cacheDir, AttachmentCache.ATTACHMENTS_DIR).deleteRecursively()
    }

    private suspend fun seedAttachment(
        guid: String = GUID,
        transferName: String = TRANSFER_NAME,
        localPath: String? = null,
        downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    ): AttachmentEntity {
        val entity = AttachmentEntity(
            guid = guid,
            messageGuid = "msg-1",
            uti = "public.jpeg",
            mimeType = "image/jpeg",
            transferName = transferName,
            totalBytes = null,
            width = null,
            height = null,
            hasLivePhoto = false,
            localPath = localPath,
            downloadState = downloadState,
        )
        db.attachmentDao().insertAll(listOf(entity))
        return entity
    }

    private fun enqueueBytes(bytes: ByteArray, delayMs: Long = 0) {
        val response = MockResponse()
            .setResponseCode(200)
            .setBody(Buffer().write(bytes))
            .setHeader("Content-Type", "application/octet-stream")
        if (delayMs > 0) {
            response.setBodyDelay(delayMs, TimeUnit.MILLISECONDS)
        }
        server.enqueue(response)
    }

    @Test
    fun `ensureDownloaded streams to cache and marks DOWNLOADED`() = runBlocking {
        seedAttachment()
        enqueueBytes(PAYLOAD)

        val result = repo.ensureDownloaded(GUID)

        assertTrue(result.isSuccess)
        val path = result.getOrThrow()
        val file = File(path)
        assertTrue(file.isFile)
        assertArrayEquals(PAYLOAD, file.readBytes())
        assertTrue(path.contains("attachments/$GUID/"))
        assertTrue(path.endsWith(TRANSFER_NAME))

        val entity = db.attachmentDao().getByGuid(GUID)!!
        assertEquals(path, entity.localPath)
        assertEquals(DownloadState.DOWNLOADED, entity.downloadState)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/attachment/$GUID/download", request.path?.substringBefore("?"))
        assertEquals("true", request.requestUrl?.queryParameter("original"))
        assertEquals("pw", request.requestUrl?.queryParameter("guid"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `second ensureDownloaded does not hit the network`() = runBlocking {
        seedAttachment()
        enqueueBytes(PAYLOAD)

        val first = repo.ensureDownloaded(GUID).getOrThrow()
        val second = repo.ensureDownloaded(GUID).getOrThrow()

        assertEquals(first, second)
        assertEquals(1, server.requestCount)
        assertArrayEquals(PAYLOAD, File(second).readBytes())
    }

    @Test
    fun `concurrent ensureDownloaded same guid shares one network request`() = runBlocking {
        seedAttachment()
        enqueueBytes(PAYLOAD, delayMs = 150)

        val results = listOf(
            async { repo.ensureDownloaded(GUID) },
            async { repo.ensureDownloaded(GUID) },
        ).awaitAll()

        assertTrue(results.all { it.isSuccess })
        assertEquals(results[0].getOrThrow(), results[1].getOrThrow())
        assertEquals(1, server.requestCount)
        assertArrayEquals(PAYLOAD, File(results[0].getOrThrow()).readBytes())
    }

    @Test
    fun `HTTP 500 marks FAILED and leaves no final file`() = runBlocking {
        seedAttachment()
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))

        val result = repo.ensureDownloaded(GUID)

        assertTrue(result.isFailure)
        val entity = db.attachmentDao().getByGuid(GUID)!!
        assertEquals(DownloadState.FAILED, entity.downloadState)
        assertNull(entity.localPath)
        val finalFile = File(context.cacheDir, "attachments/$GUID/$TRANSFER_NAME")
        assertFalse(finalFile.exists())
        val tempFile = File(context.cacheDir, "attachments/$GUID/$TRANSFER_NAME.tmp")
        assertFalse(tempFile.exists())
    }

    @Test
    fun `connection drop mid-stream marks FAILED and leaves no final file`() = runBlocking {
        seedAttachment()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(ByteArray(64 * 1024) { 1 }))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val result = repo.ensureDownloaded(GUID)

        assertTrue(result.isFailure)
        val entity = db.attachmentDao().getByGuid(GUID)!!
        assertEquals(DownloadState.FAILED, entity.downloadState)
        assertNull(entity.localPath)
        val finalFile = File(context.cacheDir, "attachments/$GUID/$TRANSFER_NAME")
        assertFalse("partial final file must not remain", finalFile.exists())
    }

    @Test
    fun `enforceLimit evicts oldest cache files and never touches outbox`() = runBlocking {
        val tinyCache = AttachmentCache.forTests(
            context = context,
            attachmentDao = db.attachmentDao(),
            maxBytes = 30L,
        )

        val now = System.currentTimeMillis()
        fun cached(guid: String, name: String, bytes: ByteArray, modified: Long): File {
            val dir = File(context.cacheDir, "attachments/$guid")
            dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(bytes)
            file.setLastModified(modified)
            return file
        }

        val f1 = cached("g1", "a.bin", ByteArray(20) { 1 }, now - 3000)
        val f2 = cached("g2", "b.bin", ByteArray(20) { 2 }, now - 2000)
        val f3 = cached("g3", "c.bin", ByteArray(20) { 3 }, now - 1000)

        db.attachmentDao().insertAll(
            listOf(
                attachmentRow("g1", f1.absolutePath),
                attachmentRow("g2", f2.absolutePath),
                attachmentRow("g3", f3.absolutePath),
            ),
        )

        val outboxDir = File(context.filesDir, "outbox/temp-deadbeef")
        outboxDir.mkdirs()
        val outboxFile = File(outboxDir, "keep-me.bin")
        outboxFile.writeBytes(ByteArray(100) { 9 })
        val outboxModified = outboxFile.lastModified()
        val outboxBytes = outboxFile.readBytes()

        tinyCache.enforceLimit()

        assertFalse("oldest cache file should be evicted", f1.exists())
        // Cap is 30; after deleting f1 (20) total is 40; still over → delete f2 as well.
        assertFalse("second-oldest should also be evicted", f2.exists())
        assertTrue("newest cache file should remain", f3.exists())

        val e1 = db.attachmentDao().getByGuid("g1")!!
        assertNull(e1.localPath)
        assertEquals(DownloadState.NOT_DOWNLOADED, e1.downloadState)
        val e2 = db.attachmentDao().getByGuid("g2")!!
        assertNull(e2.localPath)
        assertEquals(DownloadState.NOT_DOWNLOADED, e2.downloadState)
        val e3 = db.attachmentDao().getByGuid("g3")!!
        assertEquals(f3.absolutePath, e3.localPath)
        assertEquals(DownloadState.DOWNLOADED, e3.downloadState)

        assertTrue(outboxFile.exists())
        assertArrayEquals(outboxBytes, outboxFile.readBytes())
        assertEquals(outboxModified, outboxFile.lastModified())
    }

    @Test
    fun `enforceLimit never evicts in-progress tmp files even when older`() = runBlocking {
        // Cap fits neither file alone once both count toward usage (20+20=40 > 25),
        // so eviction must run — but only the completed cache file is eligible.
        val tinyCache = AttachmentCache.forTests(
            context = context,
            attachmentDao = db.attachmentDao(),
            maxBytes = 25L,
        )
        val now = System.currentTimeMillis()
        val attachments = File(context.cacheDir, AttachmentCache.ATTACHMENTS_DIR)

        val cachedDir = File(attachments, "cached-guid")
        cachedDir.mkdirs()
        val cached = File(cachedDir, "photo.jpg")
        cached.writeBytes(ByteArray(20) { 1 })
        cached.setLastModified(now - 1000)

        // Matches AttachmentDownloader: `$transferName.tmp` under guid dir.
        // Older mtime than `cached` so LRU would prefer it as victim if eligible.
        val downloadingDir = File(attachments, "downloading-guid")
        downloadingDir.mkdirs()
        val tmp = File(downloadingDir, "photo.jpg.tmp")
        tmp.writeBytes(ByteArray(20) { 2 })
        tmp.setLastModified(now - 5000)

        db.attachmentDao().insertAll(listOf(attachmentRow("cached-guid", cached.absolutePath)))

        tinyCache.enforceLimit()

        assertTrue("in-progress .tmp must survive eviction", tmp.exists())
        assertArrayEquals(ByteArray(20) { 2 }, tmp.readBytes())
        assertFalse("completed cache file should be evicted under cap pressure", cached.exists())
    }

    @Test
    fun `concurrent enforceLimit calls serialize and end under cap`() = runBlocking {
        val tinyCache = AttachmentCache.forTests(
            context = context,
            attachmentDao = db.attachmentDao(),
            maxBytes = 30L,
        )
        val now = System.currentTimeMillis()
        val attachments = File(context.cacheDir, AttachmentCache.ATTACHMENTS_DIR)

        fun place(guid: String, bytes: ByteArray, modified: Long): File {
            val dir = File(attachments, guid)
            dir.mkdirs()
            val file = File(dir, "a.bin")
            file.writeBytes(bytes)
            file.setLastModified(modified)
            return file
        }

        val files = listOf(
            place("c1", ByteArray(20) { 1 }, now - 3000),
            place("c2", ByteArray(20) { 2 }, now - 2000),
            place("c3", ByteArray(20) { 3 }, now - 1000),
        )
        db.attachmentDao().insertAll(
            files.mapIndexed { i, f -> attachmentRow("c${i + 1}", f.absolutePath) },
        )

        val jobs = List(2) { launch { tinyCache.enforceLimit() } }
        jobs.joinAll()

        val remaining = attachments.walkTopDown().filter { it.isFile }.toList()
        assertTrue(remaining.sumOf { it.length() } <= 30L)
        assertTrue(remaining.all { it.exists() })
    }

    @Test
    fun `Coil ImageLoader builds with AttachmentEntityMapper`() {
        val loader = CoilSetup.createImageLoader(context)
        assertNotNull(loader)
        loader.shutdown()
    }

    private fun attachmentRow(guid: String, localPath: String) = AttachmentEntity(
        guid = guid,
        messageGuid = "msg-$guid",
        uti = null,
        mimeType = "application/octet-stream",
        transferName = File(localPath).name,
        totalBytes = null,
        width = null,
        height = null,
        hasLivePhoto = false,
        localPath = localPath,
        downloadState = DownloadState.DOWNLOADED,
    )

    companion object {
        private const val GUID = "att-guid-1"
        private const val TRANSFER_NAME = "photo.jpg"
        private val PAYLOAD = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xFF.toByte())
    }
}
