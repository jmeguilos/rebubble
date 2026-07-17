package app.rebubble.data.logging

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

const val LOG_FILE_NAME = "rebubble-log.txt"
const val LOG_DIR_NAME = "logs"

/** Writes [lines] to `cacheDir/logs/rebubble-log.txt`, creating parent dirs as needed. */
fun writeLogSnapshot(context: Context, lines: List<String>): File {
    val dir = File(context.cacheDir, LOG_DIR_NAME)
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val file = File(dir, LOG_FILE_NAME)
    file.writeText(lines.joinToString("\n"))
    return file
}

/** Builds an [Intent.ACTION_SEND] share intent for a log file via [FileProvider]. */
fun shareLogsIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
