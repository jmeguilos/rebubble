package app.rebubble.data.media

import android.content.Context
import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.remote.api.BlueBubblesApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams attachment bytes from the BlueBubbles download endpoint into
 * `cacheDir/attachments/<guid>/<transferName>` via a temp file + atomic rename.
 *
 * Never buffers the full response in memory — copies [okhttp3.ResponseBody.byteStream] directly
 * to disk.
 */
@Singleton
class AttachmentDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: BlueBubblesApi,
) {

    suspend fun download(entity: AttachmentEntity): Result<File> = withContext(Dispatchers.IO) {
        val transferName = entity.transferName?.takeIf { it.isNotBlank() } ?: "attachment"
        val dir = File(context.cacheDir, "attachments/${entity.guid}")
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext Result.failure(IOException("Unable to create cache dir: $dir"))
        }

        val finalFile = File(dir, transferName)
        val tempFile = File(dir, "$transferName.tmp")
        tempFile.delete()
        finalFile.delete()

        try {
            val response = api.downloadAttachment(entity.guid, original = true)
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                response.body()?.close()
                tempFile.delete()
                return@withContext Result.failure(
                    IOException("Attachment download failed: HTTP ${response.code()}"),
                )
            }
            val body = response.body()
                ?: return@withContext Result.failure(IOException("Attachment download returned empty body"))

            body.use { responseBody ->
                responseBody.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            if (!finalFile.isFile) {
                return@withContext Result.failure(IOException("Failed to place downloaded file at $finalFile"))
            }
            Result.success(finalFile)
        } catch (e: Exception) {
            tempFile.delete()
            finalFile.delete()
            Result.failure(e)
        }
    }
}
