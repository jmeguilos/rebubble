package app.rebubble.data.outbox

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.rebubble.data.local.dao.AttachmentDao
import app.rebubble.data.local.dao.MessageDao
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.data.remote.api.ApiException
import app.rebubble.data.remote.api.AuthError
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.apiCall
import app.rebubble.data.repo.ServerConfigRepository
import app.rebubble.data.sync.IngestSource
import app.rebubble.data.sync.MessageIngestor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * POSTs an optimistic outbox attachment send and converges the ack through
 * [MessageIngestor.ingest] with [IngestSource.SEND_ACK].
 *
 * ## Temp attachment handling on ack
 *
 * The ingestor re-parents the optimistic `temp-att-<hex>` row onto the real message guid, then
 * [AttachmentDao.insertAll] (IGNORE) inserts the server's real attachment guid. Leaving both
 * would double-render. After a successful ingest this worker transfers `localPath` /
 * `downloadState` onto the real attachment (when present) and deletes the temp-att row.
 *
 * Error taxonomy matches [SendTextWorker] via shared [OutboxRetryPolicy].
 */
@HiltWorker
class SendAttachmentWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: BlueBubblesApi,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val ingestor: MessageIngestor,
    private val serverConfigRepository: ServerConfigRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tempGuid = inputData.getString(KEY_TEMP_GUID) ?: return Result.failure()
        val chatGuid = inputData.getString(KEY_CHAT_GUID) ?: return Result.failure()
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val mimeType = inputData.getString(KEY_MIME_TYPE)

        val existing = messageDao.getByGuid(tempGuid)
        if (existing == null || existing.sendStatus == SendStatus.SENT) {
            // Socket echo already won the race (row swapped away or settled to SENT).
            // Also recover cleanup if we died after ingest but before temp-att delete.
            cleanupTempAttachment(tempGuid)
            return Result.success()
        }

        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            markFailed(tempGuid)
            return Result.failure()
        }

        val method = resolveMethod()
        val mediaType = mimeType?.toMediaTypeOrNull()
        val filePart = MultipartBody.Part.createFormData(
            PART_NAME,
            name,
            file.asRequestBody(mediaType),
        )
        val fields = buildMap {
            put(KEY_CHAT_GUID, chatGuid.toRequestBody())
            put(KEY_TEMP_GUID, tempGuid.toRequestBody())
            put(KEY_NAME, name.toRequestBody())
            put(KEY_METHOD, method.toRequestBody())
        }

        return try {
            val dto = apiCall {
                api.sendAttachment(filePart, fields)
            }
            ingestor.ingest(listOf(dto), IngestSource.SEND_ACK, fallbackChatGuid = chatGuid)
            cleanupTempAttachment(tempGuid, realMessageGuid = dto.guid)
            Result.success()
        } catch (e: IOException) {
            if (OutboxRetryPolicy.isSafeToRetry(e)) {
                Result.retry()
            } else {
                markFailed(tempGuid)
                Result.failure()
            }
        } catch (_: AuthError) {
            markFailed(tempGuid)
            Result.failure()
        } catch (e: ApiException) {
            when {
                e.status == 400 && (e.message?.contains("already queued", ignoreCase = true) == true) -> {
                    markFailed(tempGuid)
                    Result.failure()
                }
                e.status in 400..499 -> {
                    markFailed(tempGuid)
                    Result.failure()
                }
                e.status >= 500 -> {
                    if (runAttemptCount >= MAX_SERVER_ERROR_ATTEMPTS) {
                        markFailed(tempGuid)
                        Result.failure()
                    } else {
                        Result.retry()
                    }
                }
                else -> {
                    markFailed(tempGuid)
                    Result.failure()
                }
            }
        }
    }

    /**
     * After SEND_ACK ingest: reparent left `temp-att-*` on the real message, and the ack's real
     * attachment guid was inserted alongside. Transfer local download state onto the real row
     * (so the UI keeps the already-copied bytes) and delete the temp-att row to avoid
     * double-render. If the ack carried no attachments, leave the reparented temp-att alone.
     *
     * Idempotent: no-op when the temp-att row is already gone (safe on early-exit recovery).
     * When [realMessageGuid] is null (temp message already swapped away), resolves the target
     * from the temp-att's current `messageGuid` (post-reparent).
     */
    private suspend fun cleanupTempAttachment(tempGuid: String, realMessageGuid: String? = null) {
        val tempAttGuid = OutboxRepository.tempAttachmentGuid(tempGuid)
        val tempAtt = attachmentDao.getByGuid(tempAttGuid) ?: return
        val targetMessageGuid = realMessageGuid ?: tempAtt.messageGuid
        val siblings = attachmentDao.getForMessage(targetMessageGuid)
            .filter { it.guid != tempAttGuid }
        if (siblings.isEmpty()) return

        val real = siblings.first()
        if (real.localPath == null && tempAtt.localPath != null) {
            attachmentDao.update(
                real.copy(
                    localPath = tempAtt.localPath,
                    downloadState = tempAtt.downloadState,
                ),
            )
        }
        attachmentDao.deleteByGuid(tempAttGuid)
    }

    private suspend fun resolveMethod(): String {
        val info = serverConfigRepository.serverInfo.first()
        return if (info != null && info.privateApi && info.helperConnected) {
            METHOD_PRIVATE_API
        } else {
            METHOD_APPLE_SCRIPT
        }
    }

    private suspend fun markFailed(tempGuid: String) {
        val row = messageDao.getByGuid(tempGuid) ?: return
        messageDao.update(row.copy(sendStatus = SendStatus.FAILED))
    }

    companion object {
        const val KEY_TEMP_GUID = "tempGuid"
        const val KEY_CHAT_GUID = "chatGuid"
        const val KEY_FILE_PATH = "filePath"
        const val KEY_NAME = "name"
        const val KEY_MIME_TYPE = "mimeType"
        const val KEY_METHOD = "method"

        const val PART_NAME = "attachment"

        const val METHOD_PRIVATE_API = "private-api"
        const val METHOD_APPLE_SCRIPT = "apple-script"

        /** After this many attempts (0-indexed), a 5xx response permanently fails the send. */
        const val MAX_SERVER_ERROR_ATTEMPTS = 3
    }
}
