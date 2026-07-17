package app.rebubble.data.sync

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notified when a sync pass discovers incoming messages ([SyncOutcome.newMessageGuids]).
 *
 * **Idempotency (T7 flag):** the same guid may be delivered more than once across runs — e.g. if
 * the watermark write fails after a page was ingested, the next reconcile re-surfaces those guids.
 * Implementations **must** dedupe / be idempotent (T15's real notifier will own that).
 */
fun interface NewMessageAlert {
    suspend fun onNewMessages(guids: List<String>)
}

/**
 * Default DI binding until T15: log-only no-op. Does not post notifications.
 */
@Singleton
class LoggingNewMessageAlert @Inject constructor() : NewMessageAlert {
    override suspend fun onNewMessages(guids: List<String>) {
        Log.d(LOG_TAG, "new messages (T15 will notify); count=${guids.size}")
    }

    private companion object {
        const val LOG_TAG = "NewMessageAlert"
    }
}
