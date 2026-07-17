package app.rebubble.data.logging

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class LogExportTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `writeLogSnapshot writes lines to cacheDir logs rebubble-log txt`() {
        val lines = listOf("a", "b", "c")
        val file = writeLogSnapshot(context, lines)

        assertEquals(File(context.cacheDir, "logs/rebubble-log.txt").canonicalPath, file.canonicalPath)
        assertTrue(file.exists())
        assertEquals("a\nb\nc", file.readText())
    }

    @Test
    fun `shareLogsIntent is ACTION_SEND text plain with stream uri`() {
        val file = writeLogSnapshot(context, listOf("line"))
        val intent = shareLogsIntent(context, file)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertEquals(
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            ),
            uri,
        )
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
