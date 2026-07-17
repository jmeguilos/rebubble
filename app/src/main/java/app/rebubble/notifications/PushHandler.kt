package app.rebubble.notifications

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import app.rebubble.data.logging.RingBufferLogger
import app.rebubble.data.remote.dto.MessageDto
import app.rebubble.data.sync.IngestSource
import app.rebubble.data.sync.MessageIngestor
import app.rebubble.data.sync.NewMessageAlert
import app.rebubble.data.sync.SyncScheduling
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes FCM data payloads into the sync stack.
 *
 * - `type` ∈ {`new-message`,`updated-message`} with a parseable [MessageDto] JSON `data` string →
 *   [MessageIngestor.ingest] ([IngestSource.FCM]); for newly inserted non-self guids, invoke
 *   [NewMessageAlert].
 * - Anything else (unknown type, absent / garbled / encrypt_coms AES payload) →
 *   [SyncScheduling.enqueueExpedited] as a pure wake signal.
 *
 * **Never throws** — catch-all enqueues expedited sync (reconcile backstop).
 */
@Singleton
class PushHandler @Inject constructor(
    private val ingestor: MessageIngestor,
    private val newMessageAlert: NewMessageAlert,
    private val json: Json,
    private val logger: RingBufferLogger,
    @param:ApplicationContext private val context: Context,
) {

    suspend fun handle(data: Map<String, String>) {
        try {
            val type = data["type"]
            val payload = data["data"]

            if (type == TYPE_NEW_MESSAGE || type == TYPE_UPDATED_MESSAGE) {
                val dto = payload?.let { parseMessageDto(it) }
                if (dto != null) {
                    val result = ingestor.ingest(listOf(dto), IngestSource.FCM)
                    if (!dto.isFromMe && result.insertedGuids.isNotEmpty()) {
                        newMessageAlert.onNewMessages(result.insertedGuids)
                    }
                    return
                }
            }

            Log.d(LOG_TAG, "wake-only FCM type=$type; enqueuing expedited sync")
            enqueueExpedited(reason = "wake-only type=$type")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "PushHandler failed; enqueuing expedited sync", e)
            runCatching { enqueueExpedited(reason = "handler failure: ${e.message}") }
        }
    }

    private fun parseMessageDto(payload: String): MessageDto? =
        runCatching { json.decodeFromString<MessageDto>(payload) }.getOrNull()

    private fun enqueueExpedited(reason: String) {
        logger.log(LOG_TAG, "fallback expedited sync ($reason)")
        SyncScheduling.enqueueExpedited(WorkManager.getInstance(context))
    }

    private companion object {
        const val LOG_TAG = "PushHandler"
        const val TYPE_NEW_MESSAGE = "new-message"
        const val TYPE_UPDATED_MESSAGE = "updated-message"
    }
}
