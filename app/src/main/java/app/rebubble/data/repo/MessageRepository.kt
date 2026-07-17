package app.rebubble.data.repo

import app.rebubble.data.local.dao.MessageDao
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.apiCall
import app.rebubble.data.sync.IngestSource
import app.rebubble.data.sync.MessageIngestor
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read path for a single chat's message window, plus older-page backfill.
 *
 * [observeMessages] is a thin [MessageDao.observeMessages] delegate (newest-first, non-reaction
 * rows, limited window).
 *
 * [loadOlder] fetches one DESC page from `GET /chat/{guid}/message` and pipes it through
 * [MessageIngestor.ingest] with [IngestSource.BACKFILL] (and `fallbackChatGuid`) so historical
 * pages land in Room without advancing [app.rebubble.data.sync.SyncWatermarkStore] — BACKFILL
 * intentionally does not touch the watermark, and this repository never calls it either.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val api: BlueBubblesApi,
    private val messageDao: MessageDao,
    private val ingestor: MessageIngestor,
) {

    fun observeMessages(chatGuid: String, limit: Int = 100): Flow<List<MessageEntity>> =
        messageDao.observeMessages(chatGuid, limit)

    /**
     * @return count of messages **received** from the server page (not necessarily newly
     *   inserted — duplicates are merged by the ingestor).
     */
    suspend fun loadOlder(chatGuid: String, beforeMs: Long, pageSize: Int = 50): Int {
        val dtos = apiCall {
            api.chatMessages(
                g = chatGuid,
                with = CHAT_MESSAGES_WITH,
                sort = "DESC",
                before = beforeMs,
                limit = pageSize,
            )
        }
        ingestor.ingest(
            dtos = dtos,
            source = IngestSource.BACKFILL,
            fallbackChatGuid = chatGuid,
        )
        return dtos.size
    }

    private companion object {
        const val CHAT_MESSAGES_WITH =
            "attachment,message.attributedBody,message.messageSummaryInfo"
    }
}
