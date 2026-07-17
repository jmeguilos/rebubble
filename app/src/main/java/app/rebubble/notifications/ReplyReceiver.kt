package app.rebubble.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import app.rebubble.data.outbox.OutboxRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * Inline-reply action target for message notifications.
 *
 * Extracts RemoteInput [MessageNotifier.KEY_REPLY] + [MessageNotifier.EXTRA_CHAT_GUID],
 * optimistic-sends via [OutboxRepository.sendText], then rebuilds the chat notification so the
 * pending reply remains visible in MessagingStyle.
 *
 * Core work lives in [handleReply] so unit tests can exercise RemoteInput → send → repost without
 * a `@HiltAndroidApp` (Hilt's generated `onReceive` injects before the override body).
 */
@AndroidEntryPoint
class ReplyReceiver : BroadcastReceiver() {

    @Inject
    lateinit var outboxRepository: OutboxRepository

    @Inject
    lateinit var messageNotifier: MessageNotifier

    @Inject
    @Named("fcm")
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                handleReply(intent)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "inline reply failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * RemoteInput extraction + [OutboxRepository.sendText] + notification rebuild.
     * Package-visible for Robolectric tests that set fields manually.
     */
    suspend fun handleReply(intent: Intent) {
        val chatGuid = intent.getStringExtra(MessageNotifier.EXTRA_CHAT_GUID)
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(MessageNotifier.KEY_REPLY)
            ?.toString()
            ?.trim()

        if (chatGuid.isNullOrBlank() || reply.isNullOrBlank()) {
            Log.w(LOG_TAG, "missing chatGuid or reply text; ignoring")
            return
        }

        outboxRepository.sendText(chatGuid, reply)
        messageNotifier.repostIncludingReply(chatGuid)
    }

    private companion object {
        const val LOG_TAG = "ReplyReceiver"
    }
}
