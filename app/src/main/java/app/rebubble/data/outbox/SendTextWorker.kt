package app.rebubble.data.outbox

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.rebubble.data.local.dao.MessageDao
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.data.remote.api.ApiException
import app.rebubble.data.remote.api.AuthError
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.apiCall
import app.rebubble.data.remote.dto.requests.SendTextRequest
import app.rebubble.data.repo.ServerConfigRepository
import app.rebubble.data.sync.IngestSource
import app.rebubble.data.sync.MessageIngestor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * POSTs an optimistic outbox text send and converges the ack through
 * [MessageIngestor.ingest] with [IngestSource.SEND_ACK].
 *
 * [tempGuid] is the server-side idempotency key: retries after an ambiguous timeout cannot
 * double-send. If the socket echo already swapped the temp row to a real guid, this worker
 * exits [Result.success] without calling the network.
 */
@HiltWorker
class SendTextWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: BlueBubblesApi,
    private val messageDao: MessageDao,
    private val ingestor: MessageIngestor,
    private val serverConfigRepository: ServerConfigRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tempGuid = inputData.getString(KEY_TEMP_GUID) ?: return Result.failure()
        val chatGuid = inputData.getString(KEY_CHAT_GUID) ?: return Result.failure()
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()

        val existing = messageDao.getByGuid(tempGuid)
        if (existing == null || existing.sendStatus == SendStatus.SENT) {
            // Socket echo already won the race (row swapped away or settled to SENT).
            return Result.success()
        }

        val method = resolveMethod()

        return try {
            val dto = apiCall {
                api.sendText(
                    SendTextRequest(
                        chatGuid = chatGuid,
                        tempGuid = tempGuid,
                        message = text,
                        method = method,
                    ),
                )
            }
            ingestor.ingest(listOf(dto), IngestSource.SEND_ACK, fallbackChatGuid = chatGuid)
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: AuthError) {
            markFailed(tempGuid)
            Result.failure()
        } catch (e: ApiException) {
            when {
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
        const val KEY_TEXT = "text"

        const val METHOD_PRIVATE_API = "private-api"
        const val METHOD_APPLE_SCRIPT = "apple-script"

        /** After this many attempts (0-indexed), a 5xx response permanently fails the send. */
        const val MAX_SERVER_ERROR_ATTEMPTS = 3
    }
}
