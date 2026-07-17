package app.rebubble.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.rebubble.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SendFailureNotifierTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var notifier: SendFailureNotifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationChannels.ensureCreated(context)
        notifier = SendFailureNotifier(context)
    }

    @Test
    fun `notify posts on errors channel with chatGuid deep link`() {
        val chatGuid = "iMessage;-;+15551234567"
        notifier.notifySendFailed(chatGuid)

        val notification = shadowOf(notificationManager).getNotification(chatGuid.hashCode())
        assertNotNull(notification)
        assertEquals(NotificationChannels.ERRORS, notification!!.channelId)
        assertEquals(
            SendFailureNotifier.CONTENT_TEXT,
            notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
        )

        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals(chatGuid, intent.getStringExtra(MessageNotifier.EXTRA_CHAT_GUID))
    }

    @Test
    fun `contentIntent uses FLAG_UPDATE_CURRENT`() {
        val chatGuid = "iMessage;-;+15551234567"
        notifier.notifySendFailed(chatGuid)

        val notification = shadowOf(notificationManager).getNotification(chatGuid.hashCode())
        assertNotNull(notification)
        val flags = shadowOf(notification!!.contentIntent).flags
        assertTrue(
            "SendFailureNotifier contentIntent must replace extras on re-post",
            flags and PendingIntent.FLAG_UPDATE_CURRENT != 0,
        )
    }
}
