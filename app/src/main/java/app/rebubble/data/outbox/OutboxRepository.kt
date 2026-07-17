package app.rebubble.data.outbox

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.MessageDao
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optimistic text-send outbox. Inserts a local `temp-<8hex>` [MessageEntity] at
 * [SendStatus.SENDING], updates the chat preview, and enqueues a unique
 * [SendTextWorker] keyed by that tempGuid.
 *
 * ## Server tempGuid semantics (BlueBubbles)
 *
 * Server dedup is **in-flight only** (`sendCache` in `messageRouter.ts` /
 * `messageValidator.ts`): a completed send is **not** deduped, so auto-retry after an
 * ambiguous outcome can double-send on the wire. Socket echoes for API-sent messages are
 * suppressed via the same cache; a lost HTTP ack surfaces later via reconciliation as a
 * separate real-guid row with no tempGuid link (cannot swap our temp row). A visible
 * duplicate after manual retry is acceptable M1 behavior.
 *
 * Attachment send lives in T10.
 */
@Singleton
class OutboxRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
) {

    /**
     * Optimistically inserts a SENDING row, advances the chat preview, enqueues
     * [SendTextWorker] (`ExistingWorkPolicy.KEEP`), and returns the generated tempGuid.
     */
    suspend fun sendText(chatGuid: String, text: String): String {
        val tempGuid = newTempGuid()
        val now = System.currentTimeMillis()
        messageDao.insertAll(
            listOf(
                MessageEntity(
                    guid = tempGuid,
                    chatGuid = chatGuid,
                    originalRowId = null,
                    text = text,
                    subject = null,
                    isFromMe = true,
                    senderAddress = null,
                    dateCreated = now,
                    dateRead = null,
                    dateDelivered = null,
                    groupTitle = null,
                    associatedMessageGuid = null,
                    associatedMessageType = null,
                    threadOriginatorGuid = null,
                    expressiveSendStyleId = null,
                    dateEdited = null,
                    dateRetracted = null,
                    sendStatus = SendStatus.SENDING,
                ),
            ),
        )
        chatDao.updatePreview(chatGuid, now, text)
        enqueueSendText(tempGuid, chatGuid, text, ExistingWorkPolicy.KEEP)
        return tempGuid
    }

    /**
     * Re-enqueues a previously failed send. No-op unless a row with [tempGuid] exists
     * and its [SendStatus] is [SendStatus.FAILED].
     */
    suspend fun retry(tempGuid: String) {
        val row = messageDao.getByGuid(tempGuid) ?: return
        if (row.sendStatus != SendStatus.FAILED) return
        val text = row.text ?: return
        messageDao.update(row.copy(sendStatus = SendStatus.SENDING))
        enqueueSendText(tempGuid, row.chatGuid, text, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueueSendText(
        tempGuid: String,
        chatGuid: String,
        text: String,
        policy: ExistingWorkPolicy,
    ) {
        val request = OneTimeWorkRequestBuilder<SendTextWorker>()
            .setInputData(
                workDataOf(
                    SendTextWorker.KEY_TEMP_GUID to tempGuid,
                    SendTextWorker.KEY_CHAT_GUID to chatGuid,
                    SendTextWorker.KEY_TEXT to text,
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(tempGuid, policy, request)
    }

    private fun newTempGuid(): String {
        val hex = UUID.randomUUID().toString().replace("-", "").take(TEMP_HEX_LEN).lowercase()
        return "$TEMP_PREFIX$hex"
    }

    private companion object {
        const val TEMP_PREFIX = "temp-"
        const val TEMP_HEX_LEN = 8
    }
}
