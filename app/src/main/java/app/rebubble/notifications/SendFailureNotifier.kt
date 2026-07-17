package app.rebubble.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.rebubble.MainActivity
import app.rebubble.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts send-failure alerts on [NotificationChannels.ERRORS]. Deep-links [MainActivity] with
 * [MessageNotifier.EXTRA_CHAT_GUID] so the user can open the chat and retry.
 */
@Singleton
class SendFailureNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun notifySendFailed(chatGuid: String) {
        val contentIntent = PendingIntent.getActivity(
            context,
            chatGuid.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(MessageNotifier.EXTRA_CHAT_GUID, chatGuid)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.ERRORS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(CONTENT_TITLE)
            .setContentText(CONTENT_TEXT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(chatGuid.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "notify failed for chat=$chatGuid", e)
        }
    }

    companion object {
        const val CONTENT_TITLE = "Rebubble"
        const val CONTENT_TEXT = "Message not sent — open the chat to retry"
        private const val LOG_TAG = "SendFailureNotifier"
    }
}
