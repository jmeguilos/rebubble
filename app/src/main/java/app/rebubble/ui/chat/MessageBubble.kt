package app.rebubble.ui.chat

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.ui.theme.RebubbleTheme
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** iMessage own-bubble blue (chat screen only — not Material primary). */
val OwnIMessageBubble = Color(0xFF0B84FE)

/** SMS own-bubble green (chat.guid starts with `SMS;`). */
val OwnSmsBubble = Color(0xFF34C759)

private val BubbleOuterRadius = 20.dp
private val BubbleInnerRadius = 4.dp
private val TailWidth = 8.dp
private val TailHeight = 6.dp
private val TimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

@Composable
fun MessageBubble(
    item: ChatUiItem.Bubble,
    isSms: Boolean,
    selected: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit = {},
    onRetry: () -> Unit,
    onDownloadAttachment: (String) -> Unit,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    animateSendPop: Boolean = item.message.guid.startsWith("temp-") && item.message.isFromMe,
) {
    val fromMe = item.message.isFromMe
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.76f).dp
    val density = LocalDensity.current
    val shape = remember(fromMe, item.showTail, item.isFirstInRun, item.isLastInRun, density) {
        BubbleShape(
            fromMe = fromMe,
            showTail = item.showTail,
            isFirstInRun = item.isFirstInRun,
            isLastInRun = item.isLastInRun,
            density = density,
        )
    }
    val containerColor = when {
        item.isFailed -> MaterialTheme.colorScheme.errorContainer
        fromMe && isSms -> OwnSmsBubble
        fromMe -> OwnIMessageBubble
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        item.isFailed -> MaterialTheme.colorScheme.onErrorContainer
        fromMe -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }

    val reduceMotion = !ValueAnimator.areAnimatorsEnabled()
    val scale = remember {
        Animatable(if (animateSendPop && !reduceMotion) 0.8f else 1f)
    }
    val alpha = remember {
        Animatable(if (animateSendPop && !reduceMotion) 0f else 1f)
    }
    LaunchedEffect(item.message.guid, reduceMotion) {
        if (animateSendPop && !reduceMotion) {
            coroutineScope {
                launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                launch { alpha.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
            }
        } else {
            scale.snapTo(1f)
            alpha.snapTo(1f)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (fromMe) 48.dp else 12.dp,
                end = if (fromMe) 12.dp else 48.dp,
                top = if (item.isFirstInRun) 6.dp else 2.dp,
                bottom = if (item.isLastInRun) 6.dp else 2.dp,
            )
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            },
        horizontalAlignment = if (fromMe) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(shape)
                .background(containerColor)
                .pointerInput(item.key) {
                    detectTapGestures(
                        onLongPress = { onLongPress() },
                        onTap = { onTap() },
                    )
                }
                .padding(
                    horizontal = if (item.attachments.isEmpty()) 12.dp else 4.dp,
                    vertical = if (item.attachments.isEmpty()) 8.dp else 4.dp,
                )
                .then(
                    if (item.showTail) Modifier.padding(bottom = TailHeight) else Modifier,
                ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.attachments.forEach { attachment ->
                    AttachmentContent(
                        attachment = attachment,
                        isUploading = item.isSending && attachment.localPath != null,
                        onDownloadClick = { onDownloadAttachment(attachment.guid) },
                        imageLoader = imageLoader,
                        contentColor = contentColor,
                    )
                }
                val text = item.message.text
                if (!text.isNullOrBlank()) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        modifier = Modifier.padding(
                            horizontal = if (item.attachments.isEmpty()) 0.dp else 8.dp,
                        ),
                    )
                }
            }
        }
        if (item.isFailed) {
            TextButton(onClick = onRetry) {
                Text(
                    text = "Not sent — tap to retry",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (selected) {
            Text(
                text = formatBubbleTime(item.message.dateCreated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

/**
 * Signature bubble geometry: 20dp outer / 4dp inner run radii, plus a small directional tail on
 * the last bubble of a consecutive same-sender run (own = bottom-right, other = bottom-left).
 */
internal class BubbleShape(
    private val fromMe: Boolean,
    private val showTail: Boolean,
    private val isFirstInRun: Boolean,
    private val isLastInRun: Boolean,
    private val density: Density,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val d = this.density
        val outer = with(d) { BubbleOuterRadius.toPx() }
        val inner = with(d) { BubbleInnerRadius.toPx() }
        val tailW = with(d) { TailWidth.toPx() }
        val tailH = with(d) { TailHeight.toPx() }

        val topStart = if (isFirstInRun) outer else inner
        val topEnd = if (isFirstInRun) outer else inner
        val bottomStart = when {
            !fromMe && showTail -> outer
            isLastInRun -> outer
            else -> inner
        }
        val bottomEnd = when {
            fromMe && showTail -> outer
            isLastInRun -> outer
            else -> inner
        }

        val path = Path().apply {
            val bodyBottom = if (showTail) size.height - tailH else size.height
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = bodyBottom,
                    topLeftCornerRadius = CornerRadius(topStart, topStart),
                    topRightCornerRadius = CornerRadius(topEnd, topEnd),
                    bottomLeftCornerRadius = CornerRadius(bottomStart, bottomStart),
                    bottomRightCornerRadius = CornerRadius(bottomEnd, bottomEnd),
                ),
            )
            if (showTail) {
                if (fromMe) {
                    moveTo(size.width - outer * 0.4f, bodyBottom)
                    quadraticTo(
                        size.width + tailW * 0.15f,
                        bodyBottom + tailH * 0.35f,
                        size.width - tailW * 0.2f,
                        size.height,
                    )
                    quadraticTo(
                        size.width - outer * 0.55f,
                        bodyBottom + tailH * 0.55f,
                        size.width - outer * 1.1f,
                        bodyBottom,
                    )
                    close()
                } else {
                    moveTo(outer * 0.4f, bodyBottom)
                    quadraticTo(
                        -tailW * 0.15f,
                        bodyBottom + tailH * 0.35f,
                        tailW * 0.2f,
                        size.height,
                    )
                    quadraticTo(
                        outer * 0.55f,
                        bodyBottom + tailH * 0.55f,
                        outer * 1.1f,
                        bodyBottom,
                    )
                    close()
                }
            }
        }
        return Outline.Generic(path)
    }
}

internal fun formatBubbleTime(dateCreatedMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TimeFormatter.format(Instant.ofEpochMilli(dateCreatedMs).atZone(zone))

@Preview(showBackground = true, name = "Own run with tails")
@Composable
private fun OwnRunPreview() {
    val ctx = LocalPlatformContext.current
    RebubbleTheme(dynamicColor = false) {
        Column {
            MessageBubble(
                item = previewBubble("1", "Hey", first = true, last = false, tail = false),
                isSms = false,
                selected = false,
                onLongPress = {},
                onRetry = {},
                onDownloadAttachment = {},
                imageLoader = ImageLoader.Builder(ctx).build(),
                animateSendPop = false,
            )
            MessageBubble(
                item = previewBubble("2", "Want to go?", first = false, last = true, tail = true),
                isSms = false,
                selected = true,
                onLongPress = {},
                onRetry = {},
                onDownloadAttachment = {},
                imageLoader = ImageLoader.Builder(ctx).build(),
                animateSendPop = false,
            )
        }
    }
}

@Preview(showBackground = true, name = "Other + SMS + FAILED")
@Composable
private fun VariantsPreview() {
    val ctx = LocalPlatformContext.current
    RebubbleTheme(dynamicColor = false) {
        Column {
            MessageBubble(
                item = previewBubble("o", "Incoming", fromMe = false, first = true, last = true, tail = true),
                isSms = false,
                selected = false,
                onLongPress = {},
                onRetry = {},
                onDownloadAttachment = {},
                imageLoader = ImageLoader.Builder(ctx).build(),
                animateSendPop = false,
            )
            MessageBubble(
                item = previewBubble("s", "SMS out", first = true, last = true, tail = true),
                isSms = true,
                selected = false,
                onLongPress = {},
                onRetry = {},
                onDownloadAttachment = {},
                imageLoader = ImageLoader.Builder(ctx).build(),
                animateSendPop = false,
            )
            MessageBubble(
                item = previewBubble(
                    "f",
                    "Failed",
                    first = true,
                    last = true,
                    tail = true,
                    status = SendStatus.FAILED,
                ),
                isSms = false,
                selected = false,
                onLongPress = {},
                onRetry = {},
                onDownloadAttachment = {},
                imageLoader = ImageLoader.Builder(ctx).build(),
                animateSendPop = false,
            )
        }
    }
}

private fun previewBubble(
    guid: String,
    text: String,
    fromMe: Boolean = true,
    first: Boolean,
    last: Boolean,
    tail: Boolean,
    status: SendStatus = SendStatus.SENT,
    attachments: List<AttachmentEntity> = emptyList(),
) = ChatUiItem.Bubble(
    message = MessageEntity(
        guid = guid,
        chatGuid = "c",
        originalRowId = null,
        text = text,
        subject = null,
        isFromMe = fromMe,
        senderAddress = null,
        dateCreated = System.currentTimeMillis(),
        dateRead = null,
        dateDelivered = null,
        groupTitle = null,
        associatedMessageGuid = null,
        associatedMessageType = null,
        threadOriginatorGuid = null,
        expressiveSendStyleId = null,
        dateEdited = null,
        dateRetracted = null,
        sendStatus = status,
    ),
    attachments = attachments,
    showTail = tail,
    isFirstInRun = first,
    isLastInRun = last,
)
