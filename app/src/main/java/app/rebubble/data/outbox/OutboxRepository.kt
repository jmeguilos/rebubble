package app.rebubble.data.outbox

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.rebubble.data.local.dao.AttachmentDao
import app.rebubble.data.local.dao.ChatDao
import app.rebubble.data.local.dao.MessageDao
import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.DownloadState
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optimistic send outbox for text and attachments. Inserts a local `temp-<8hex>`
 * [MessageEntity] at [SendStatus.SENDING], updates the chat preview, and enqueues a unique
 * WorkManager worker keyed by that tempGuid.
 *
 * ## Server tempGuid semantics (BlueBubbles)
 *
 * Server dedup is **in-flight only** (`sendCache` in `messageRouter.ts` /
 * `messageValidator.ts`): a completed send is **not** deduped, so auto-retry after an
 * ambiguous outcome can double-send on the wire. Socket echoes for API-sent messages are
 * suppressed via the same cache; a lost HTTP ack surfaces later via reconciliation as a
 * separate real-guid row with no tempGuid link (cannot swap our temp row). A visible
 * duplicate after manual retry is acceptable M1 behavior.
 */
@Singleton
class OutboxRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val attachmentDao: AttachmentDao,
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
     * Copies [uri] bytes into app-private storage, inserts an optimistic SENDING message plus
     * a local [AttachmentEntity], advances the chat preview, enqueues [SendAttachmentWorker],
     * and returns the generated tempGuid.
     *
     * The content URI is copied **before** enqueue — picker URIs expire and must not be read
     * later from the worker.
     */
    suspend fun sendAttachment(
        chatGuid: String,
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): String {
        val tempGuid = newTempGuid()
        // ContentResolver + byte copy can be large (videos); never run on the caller's dispatcher.
        val (name, resolvedMime, dest) = withContext(Dispatchers.IO) {
            val resolvedName = displayName ?: resolveDisplayName(uri) ?: "attachment"
            val resolvedType = mimeType ?: context.contentResolver.getType(uri)
            val copied = copyUriToOutbox(uri, tempGuid, resolvedName)
            Triple(resolvedName, resolvedType, copied)
        }
        val now = System.currentTimeMillis()
        val attGuid = tempAttachmentGuid(tempGuid)

        messageDao.insertAll(
            listOf(
                MessageEntity(
                    guid = tempGuid,
                    chatGuid = chatGuid,
                    originalRowId = null,
                    text = null,
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
        attachmentDao.insertAll(
            listOf(
                AttachmentEntity(
                    guid = attGuid,
                    messageGuid = tempGuid,
                    uti = null,
                    mimeType = resolvedMime,
                    transferName = name,
                    totalBytes = dest.length(),
                    width = null,
                    height = null,
                    hasLivePhoto = false,
                    localPath = dest.absolutePath,
                    downloadState = DownloadState.DOWNLOADED,
                ),
            ),
        )
        chatDao.updatePreview(chatGuid, now, ATTACHMENT_PREVIEW)
        enqueueSendAttachment(
            tempGuid = tempGuid,
            chatGuid = chatGuid,
            filePath = dest.absolutePath,
            name = name,
            mimeType = resolvedMime,
            policy = ExistingWorkPolicy.KEEP,
        )
        return tempGuid
    }

    /**
     * Re-enqueues a previously failed send. No-op unless a row with [tempGuid] exists
     * and its [SendStatus] is [SendStatus.FAILED].
     *
     * Infers worker type from attachment children: if an [AttachmentEntity] exists for the
     * message, re-enqueues [SendAttachmentWorker] with input rebuilt from that row; otherwise
     * [SendTextWorker] (requires non-null text).
     */
    suspend fun retry(tempGuid: String) {
        val row = messageDao.getByGuid(tempGuid) ?: return
        if (row.sendStatus != SendStatus.FAILED) return

        val attachments = attachmentDao.getForMessage(tempGuid)
        if (attachments.isNotEmpty()) {
            val att = attachments.first()
            val filePath = att.localPath ?: return
            messageDao.update(row.copy(sendStatus = SendStatus.SENDING))
            enqueueSendAttachment(
                tempGuid = tempGuid,
                chatGuid = row.chatGuid,
                filePath = filePath,
                name = att.transferName ?: "attachment",
                mimeType = att.mimeType,
                policy = ExistingWorkPolicy.REPLACE,
            )
            return
        }

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

    private fun enqueueSendAttachment(
        tempGuid: String,
        chatGuid: String,
        filePath: String,
        name: String,
        mimeType: String?,
        policy: ExistingWorkPolicy,
    ) {
        val dataBuilder = androidx.work.Data.Builder()
            .putString(SendAttachmentWorker.KEY_TEMP_GUID, tempGuid)
            .putString(SendAttachmentWorker.KEY_CHAT_GUID, chatGuid)
            .putString(SendAttachmentWorker.KEY_FILE_PATH, filePath)
            .putString(SendAttachmentWorker.KEY_NAME, name)
        if (mimeType != null) {
            dataBuilder.putString(SendAttachmentWorker.KEY_MIME_TYPE, mimeType)
        }
        val request = OneTimeWorkRequestBuilder<SendAttachmentWorker>()
            .setInputData(dataBuilder.build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(tempGuid, policy, request)
    }

    private fun copyUriToOutbox(uri: Uri, tempGuid: String, name: String): File {
        val dir = File(context.filesDir, "outbox/$tempGuid")
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, name)
        val input = when (uri.scheme) {
            "file" -> FileInputStream(File(requireNotNull(uri.path)))
            else -> context.contentResolver.openInputStream(uri)
                ?: error("Unable to open content URI: $uri")
        }
        input.use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
        return dest
    }

    private fun resolveDisplayName(uri: Uri): String? {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    return cursor.getString(idx)?.takeIf { it.isNotBlank() }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun newTempGuid(): String {
        val hex = UUID.randomUUID().toString().replace("-", "").take(TEMP_HEX_LEN).lowercase()
        return "$TEMP_PREFIX$hex"
    }

    internal companion object {
        const val TEMP_PREFIX = "temp-"
        const val TEMP_HEX_LEN = 8
        const val ATTACHMENT_PREVIEW = "📎 Attachment"
        const val TEMP_ATTACHMENT_PREFIX = "temp-att-"

        fun tempAttachmentGuid(tempGuid: String): String {
            val hex = tempGuid.removePrefix(TEMP_PREFIX)
            return "$TEMP_ATTACHMENT_PREFIX$hex"
        }
    }
}
