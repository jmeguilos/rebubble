package app.rebubble.data.media

import android.content.Context
import app.rebubble.data.local.dao.AttachmentDao
import app.rebubble.data.local.entity.DownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Size-bounded LRU over `cacheDir/attachments/`. Access time is proxied by [File.lastModified]
 * — callers should [touch] a file when serving it from cache.
 *
 * Eviction only walks the attachments cache tree; `filesDir/outbox/` lives under a different
 * root and is never considered.
 *
 * In-progress downloads use `*.tmp` (see [AttachmentDownloader]); those files count toward
 * usage so the cap stays honest, but are never eviction candidates — deleting them mid-write
 * would corrupt a concurrent download for a different guid.
 */
@Singleton
class AttachmentCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val attachmentDao: AttachmentDao,
) {
    /** Test override — production uses [DEFAULT_MAX_BYTES]. */
    internal var maxBytes: Long = DEFAULT_MAX_BYTES

    /** Serializes [enforceLimit] so concurrent finishers do not race the same walk/delete. */
    private val enforceMutex = Mutex()

    private val cacheRoot: File
        get() = File(context.cacheDir, ATTACHMENTS_DIR)

    /** Updates last-modified so LRU treats this file as most-recently accessed. */
    fun touch(file: File) {
        if (file.isFile) {
            file.setLastModified(System.currentTimeMillis())
        }
    }

    /**
     * Deletes oldest-accessed files under [cacheRoot] until total size is ≤ [maxBytes].
     * For each deleted file, clears the matching [AttachmentEntity.localPath] and resets
     * [DownloadState] to [DownloadState.NOT_DOWNLOADED].
     *
     * `*.tmp` files (in-progress downloads) count toward total usage but are never deleted.
     */
    suspend fun enforceLimit() = withContext(Dispatchers.IO) {
        enforceMutex.withLock {
            val root = cacheRoot
            if (!root.exists()) return@withLock

            val files = root.walkTopDown().filter { it.isFile }.toList()
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return@withLock

            // Count .tmp toward usage (honest cap) but never evict them mid-write.
            val oldestFirst = files
                .filter { !it.name.endsWith(".tmp") }
                .sortedBy { it.lastModified() }
            for (file in oldestFirst) {
                if (total <= maxBytes) break
                val path = file.absolutePath
                val size = file.length()
                if (!file.delete()) continue
                total -= size
                val entity = attachmentDao.getByLocalPath(path) ?: continue
                attachmentDao.update(
                    entity.copy(
                        localPath = null,
                        downloadState = DownloadState.NOT_DOWNLOADED,
                    ),
                )
            }
        }
    }

    companion object {
        const val ATTACHMENTS_DIR = "attachments"
        const val DEFAULT_MAX_BYTES: Long = 1L * 1024 * 1024 * 1024 // 1 GiB

        /** Explicit constructor for tests that need a tiny LRU cap. */
        fun forTests(
            context: Context,
            attachmentDao: AttachmentDao,
            maxBytes: Long,
        ): AttachmentCache = AttachmentCache(context, attachmentDao).also {
            it.maxBytes = maxBytes
        }
    }
}
