package app.rebubble.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.DownloadState
import app.rebubble.ui.theme.RebubbleTheme
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext

private val ImageCorner = RoundedCornerShape(16.dp)
private val MaxImageHeight = 320.dp

@Composable
fun AttachmentContent(
    attachment: AttachmentEntity,
    isUploading: Boolean,
    onDownloadClick: () -> Unit,
    imageLoader: ImageLoader,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val ratio = attachmentAspectRatio(attachment.width, attachment.height)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxH = MaxImageHeight
        val heightFromWidth = maxWidth / ratio
        val height = min(heightFromWidth, maxH)
        val width = height * ratio
        Box(
            modifier = Modifier
                .size(width, height)
                .clip(ImageCorner)
                .align(Alignment.CenterStart),
        ) {
            val showImage = attachment.localPath != null &&
                (attachment.downloadState == DownloadState.DOWNLOADED ||
                    attachment.downloadState == DownloadState.DOWNLOADING ||
                    isUploading)
            when {
                showImage -> {
                    AsyncImage(
                        model = attachment,
                        contentDescription = attachment.transferName,
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (isUploading || attachment.downloadState == DownloadState.DOWNLOADING) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                attachment.downloadState == DownloadState.FAILED -> {
                    PlaceholderBox(onClick = onDownloadClick) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Retry download",
                            tint = contentColor,
                        )
                    }
                }
                else -> {
                    PlaceholderBox(onClick = onDownloadClick) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "Download",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderBox(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Stable aspect ratio from server width/height so Coil decode never causes a layout jump.
 * Falls back to 4:3 when dimensions are unknown.
 */
fun attachmentAspectRatio(width: Int?, height: Int?): Float {
    if (width != null && height != null && width > 0 && height > 0) {
        return width.toFloat() / height.toFloat()
    }
    return 4f / 3f
}

@Preview(showBackground = true, name = "Image placeholder")
@Composable
private fun AttachmentPlaceholderPreview() {
    val ctx = LocalPlatformContext.current
    RebubbleTheme(dynamicColor = false) {
        AttachmentContent(
            attachment = AttachmentEntity(
                guid = "a1",
                messageGuid = "m1",
                uti = null,
                mimeType = "image/jpeg",
                transferName = "photo.jpg",
                totalBytes = 1000,
                width = 1200,
                height = 800,
                hasLivePhoto = false,
                localPath = null,
                downloadState = DownloadState.NOT_DOWNLOADED,
            ),
            isUploading = false,
            onDownloadClick = {},
            imageLoader = ImageLoader.Builder(ctx).build(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "Downloaded")
@Composable
private fun AttachmentDownloadedPreview() {
    val ctx = LocalPlatformContext.current
    RebubbleTheme(dynamicColor = false) {
        AttachmentContent(
            attachment = AttachmentEntity(
                guid = "a2",
                messageGuid = "m2",
                uti = null,
                mimeType = "image/jpeg",
                transferName = "photo.jpg",
                totalBytes = 1000,
                width = 800,
                height = 1200,
                hasLivePhoto = false,
                localPath = "/tmp/missing-ok-for-preview.jpg",
                downloadState = DownloadState.DOWNLOADED,
            ),
            isUploading = false,
            onDownloadClick = {},
            imageLoader = ImageLoader.Builder(ctx).build(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp),
        )
    }
}
