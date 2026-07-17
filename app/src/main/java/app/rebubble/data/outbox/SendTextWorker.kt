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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * POSTs an optimistic outbox text send and converges the ack through
 * [MessageIngestor.ingest] with [IngestSource.SEND_ACK].
 *
 * ## Server tempGuid / sendCache semantics (BlueBubbles)
 *
 * Server dedup is **in-flight only**: `sendCache.add(tempGuid)` at send start and
 * `remove(tempGuid)` on completion/error (`messageRouter.ts` sendText). The validator
 * rejects a duplicate tempGuid with 400 "Message is already queued to be sent!" **only**
 * while the original is still in the cache (`messageValidator.ts` ~113–115). A
 * **completed** send is **not** deduped — retrying the same tempGuid after the server
 * finished processing re-sends the message on the wire.
 *
 * Socket echoes for API-sent messages are suppressed via the same sendCache, so a lost
 * HTTP response gets **no** echo. Reconciliation later ingests the sent message as a new
 * real-guid row with no tempGuid link (it cannot swap our temp row). After a manual retry
 * that double-sends, a duplicate may be visible — acceptable M1 behavior.
 *
 * If the socket echo already swapped the temp row to a real guid, this worker exits
 * [Result.success] without calling the network.
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
        } catch (e: IOException) {
            if (isSafeToRetry(e)) {
                Result.retry()
            } else {
                // Ambiguous: request may have reached the server. Auto-retry would risk a
                // silent double-send (completed sends are not deduped). Mark FAILED for
                // honest manual retry.
                markFailed(tempGuid)
                Result.failure()
            }
        } catch (_: AuthError) {
            markFailed(tempGuid)
            Result.failure()
        } catch (e: ApiException) {
            when {
                // In-flight duplicate: earlier attempt still in sendCache. Outcome is
                // ambiguous (may still complete); do not keep auto-retrying.
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

        /**
         * Whether an [IOException] proves the HTTP request never reached the BlueBubbles
         * server, so [Result.retry] cannot double-send.
         *
         * Server sendCache dedup is **in-flight only** (`messageRouter.ts` add/remove around
         * sendText; `messageValidator.ts` ~113–115 rejects duplicates only while queued). A
         * completed send is not deduped — retrying the same tempGuid re-sends on the wire.
         * API-sent socket echoes are suppressed via sendCache, so a lost ack yields no echo
         * and reconciliation cannot swap our temp row. Therefore only failures that cannot
         * have delivered the request body are auto-retried:
         * [ConnectException], [UnknownHostException], [SSLHandshakeException] /
         * handshake-[SSLException], and [SocketTimeoutException] whose message contains
         * `"connect"`. All other IOExceptions (read timeouts, connection reset mid-response,
         * `NO_RESPONSE`-style) are ambiguous → caller marks FAILED.
         */
        internal fun isSafeToRetry(e: IOException): Boolean {
            return when (e) {
                is ConnectException -> true
                is UnknownHostException -> true
                is SSLHandshakeException -> true
                is SSLException -> e.message?.contains("handshake", ignoreCase = true) == true
                is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
                else -> false
            }
        }
    }
}
