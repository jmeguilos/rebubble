package app.rebubble.data.repo

import app.rebubble.data.local.dao.AttachmentDao
import app.rebubble.data.local.entity.DownloadState
import app.rebubble.data.media.AttachmentCache
import app.rebubble.data.media.AttachmentDownloader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures an attachment's bytes are present on disk under the size-bounded cache.
 *
 * Returns [Result.success] with the absolute local path, or [Result.failure] on missing entity /
 * download error. [Result] is preferred over a custom sealed type here: callers only need the
 * path string on success, and the existing exception surface (HTTP / IO) is enough for M1.
 *
 * Concurrent [ensureDownloaded] calls for the same guid share one in-flight download via a
 * per-guid [Mutex] map — the first caller streams; waiters re-check disk after acquiring the lock.
 */
@Singleton
class AttachmentRepository @Inject constructor(
    private val attachmentDao: AttachmentDao,
    private val downloader: AttachmentDownloader,
    private val cache: AttachmentCache,
) {

    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun ensureDownloaded(guid: String): Result<String> {
        val mutex = locks.getOrPut(guid) { Mutex() }
        return mutex.withLock {
            val entity = attachmentDao.getByGuid(guid)
                ?: return Result.failure(IllegalArgumentException("Unknown attachment: $guid"))

            val existingPath = entity.localPath
            if (existingPath != null) {
                val existing = File(existingPath)
                if (existing.isFile) {
                    cache.touch(existing)
                    return Result.success(existing.absolutePath)
                }
            }

            attachmentDao.update(entity.copy(downloadState = DownloadState.DOWNLOADING))

            val downloadResult = downloader.download(entity)
            downloadResult.fold(
                onSuccess = { file ->
                    cache.touch(file)
                    attachmentDao.update(
                        entity.copy(
                            localPath = file.absolutePath,
                            downloadState = DownloadState.DOWNLOADED,
                        ),
                    )
                    cache.enforceLimit()
                    Result.success(file.absolutePath)
                },
                onFailure = { error ->
                    attachmentDao.update(
                        entity.copy(
                            localPath = null,
                            downloadState = DownloadState.FAILED,
                        ),
                    )
                    Result.failure(error)
                },
            )
        }
    }
}
