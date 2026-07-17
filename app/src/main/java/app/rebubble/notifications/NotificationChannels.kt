package app.rebubble.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * App notification channels. Created once on process start ([app.rebubble.RebubbleApplication]).
 *
 * - [MESSAGES] — incoming message MessagingStyle notifications (IMPORTANCE_HIGH).
 * - [ERRORS] — send-failure alerts (IMPORTANCE_DEFAULT); posted by later tasks.
 */
object NotificationChannels {
    const val MESSAGES = "messages"
    const val ERRORS = "errors"

    /** NotificationCompat group key for per-chat message notifications + summary. */
    const val GROUP_KEY = "rebubble_messages"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ERRORS,
                "Send failures",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}
