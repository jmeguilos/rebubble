package app.rebubble.ui.chat

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.rebubble.ui.theme.RebubbleMotion
import app.rebubble.ui.theme.OnIMessageBubble
import app.rebubble.ui.theme.OnSmsBubble
import app.rebubble.ui.theme.OwnIMessageBubble
import app.rebubble.ui.theme.OwnSmsBubble
import app.rebubble.ui.theme.RebubbleTheme
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Own bubbles still in-flight ([SendStatus.SENDING]) render at reduced emphasis vs SENT. */
internal const val SendingBubbleAlpha = 0.65f

private val BubbleOuterRadius = 20.dp
/** Card `--radius-tight`. */
private val BubbleInnerRadius = 5.dp
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
    /** When true and this is the latest own message, show Delivered/Read under the bubble. */
    showDeliveryReceipt: Boolean = false,
    animateSendPop: Boolean = item.message.guid.startsWith("temp-") && item.message.isFromMe,
) {
    val fromMe = item.message.isFromMe
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.76f).dp
    val density = LocalDensity.current
    val motion = RebubbleMotion
    val shape = remember(fromMe, item.isFirstInRun, item.isLastInRun) {
        bubbleShapeFor(
            fromMe = fromMe,
            isFirstInRun = item.isFirstInRun,
            isLastInRun = item.isLastInRun,
        )
    }
    val containerColor = when {
        item.isFailed -> MaterialTheme.colorScheme.errorContainer
        fromMe && isSms -> OwnSmsBubble
        fromMe -> OwnIMessageBubble
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    // Mirrors the containerColor branches above: each own-bubble fill has its own on-color, because
    // white clears AA on the iMessage blue but only manages 2.22:1 on the SMS green.
    val contentColor = when {
        item.isFailed -> MaterialTheme.colorScheme.onErrorContainer
        fromMe && isSms -> OnSmsBubble
        fromMe -> OnIMessageBubble
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
                launch { scale.animateTo(1f, motion.fastSpatialSpec()) }
                launch { alpha.animateTo(1f, motion.fastEffectsSpec()) }
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
                // SENDING own bubbles stay visually quieter than SENT (no text label).
                val sendingDim =
                    if (fromMe && item.isSending) SendingBubbleAlpha else 1f
                this.alpha = alpha.value * sendingDim
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
        val receiptLabel = deliveryReceiptLabel(
            show = showDeliveryReceipt && fromMe && !item.isFailed && !item.isSending,
            dateDelivered = item.message.dateDelivered,
            dateRead = item.message.dateRead,
        )
        if (receiptLabel != null) {
            Text(
                text = receiptLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = selected,
            enter = expandVertically(animationSpec = motion.fastSpatialSpec()) +
                fadeIn(animationSpec = motion.fastEffectsSpec()),
            exit = shrinkVertically(animationSpec = motion.fastSpatialSpec()) +
                fadeOut(animationSpec = motion.fastEffectsSpec()),
        ) {
            Text(
                text = formatBubbleTime(item.message.dateCreated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

/** "Read" wins over "Delivered"; null when neither timestamp is set or [show] is false. */
internal fun deliveryReceiptLabel(
    show: Boolean,
    dateDelivered: Long?,
    dateRead: Long?,
): String? {
    if (!show) return null
    return when {
        dateRead != null -> "Read"
        dateDelivered != null -> "Delivered"
        else -> null
    }
}

/**
 * Bubble geometry, straight from `design/components/bubbles.html`.
 *
 * The **far** side of a bubble (right for incoming, left for outgoing) is always the full
 * [BubbleOuterRadius]. The **near** side carries the run seam: [BubbleOuterRadius] at the run's
 * first and last bubbles, [BubbleInnerRadius] on interior ones. That asymmetry alone communicates
 * both who is speaking and whether the run continues — it is Google Messages' own vocabulary.
 *
 * This replaced a hand-rolled [androidx.compose.ui.graphics.Shape] that also drew a decorative
 * tail. The tail was deleted rather than repaired: the approved card specified a 12x12 concave
 * fillet offset 6px outside a 20px corner, which is geometrically impossible (the body edge at the
 * fillet's near x-position is ~14px away), so it rendered as a detached nub in the card and as a
 * hairline sickle in the port. Neither M3 nor Google Messages ships a bubble tail.
 *
 * Using start/end corners rather than left/right also makes the geometry correct under RTL, which
 * the previous implementation was not: it received a `layoutDirection` and ignored it, and its tail
 * path hard-coded `size.width`. It additionally captured a [androidx.compose.ui.unit.Density] at
 * construction and ignored the one handed to `createOutline`.
 */
internal fun bubbleShapeFor(
    fromMe: Boolean,
    isFirstInRun: Boolean,
    isLastInRun: Boolean,
): RoundedCornerShape {
    val far = BubbleOuterRadius
    val nearTop = if (isFirstInRun) BubbleOuterRadius else BubbleInnerRadius
    val nearBottom = if (isLastInRun) BubbleOuterRadius else BubbleInnerRadius
    return if (fromMe) {
        // Outgoing: near side is the end edge.
        RoundedCornerShape(topStart = far, topEnd = nearTop, bottomEnd = nearBottom, bottomStart = far)
    } else {
        // Incoming: near side is the start edge.
        RoundedCornerShape(topStart = nearTop, topEnd = far, bottomEnd = far, bottomStart = nearBottom)
    }
}

internal fun formatBubbleTime(dateCreatedMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TimeFormatter.format(Instant.ofEpochMilli(dateCreatedMs).atZone(zone))

@Preview(showBackground = true, name = "Own run")
@Composable
private fun OwnRunPreview() {
    val ctx = LocalPlatformContext.current
    RebubbleTheme(dynamicColor = false) {
        Column {
            MessageBubble(
                item = previewBubble("1", "Hey", first = true, last = false),
                isSms = false,
                selected = false,
                onLongPress = {},
                onRetry = {},
                onDownloadAttachment = {},
                imageLoader = ImageLoader.Builder(ctx).build(),
                animateSendPop = false,
            )
            MessageBubble(
                item = previewBubble("2", "Want to go?", first = false, last = true),
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
                item = previewBubble("o", "Incoming", fromMe = false, first = true, last = true),
                isSms = false,
                selected = false,
                onLongPress = {},
                onRetry = {},
                onDownloadAttachment = {},
                imageLoader = ImageLoader.Builder(ctx).build(),
                animateSendPop = false,
            )
            MessageBubble(
                item = previewBubble("s", "SMS out", first = true, last = true),
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

@Preview(showBackground = true, name = "SENDING own bubble")
@Composable
private fun SendingOwnPreview() {
    val ctx = LocalPlatformContext.current
    RebubbleTheme(dynamicColor = false) {
        MessageBubble(
            item = previewBubble(
                "temp-deadbeef",
                "Still sending…",
                first = true,
                last = true,
                status = SendStatus.SENDING,
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

@Preview(showBackground = true, name = "Delivered + Read receipts")
@Composable
private fun DeliveryReceiptPreview() {
    val ctx = LocalPlatformContext.current
    val now = System.currentTimeMillis()
    RebubbleTheme(dynamicColor = false) {
        Column {
            MessageBubble(
                item = previewBubble(
                    "d",
                    "Delivered only",
                    first = true,
                    last = true,
                    dateDelivered = now,
                ),
                isSms = false,
                selected = false,
                showDeliveryReceipt = true,
                onLongPress = {},
                onRetry = {},
                onDownloadAttachment = {},
                imageLoader = ImageLoader.Builder(ctx).build(),
                animateSendPop = false,
            )
            MessageBubble(
                item = previewBubble(
                    "r",
                    "Read wins",
                    first = true,
                    last = true,
                    dateDelivered = now - 1_000,
                    dateRead = now,
                ),
                isSms = true,
                selected = false,
                showDeliveryReceipt = true,
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
    status: SendStatus = SendStatus.SENT,
    attachments: List<AttachmentEntity> = emptyList(),
    dateDelivered: Long? = null,
    dateRead: Long? = null,
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
        dateRead = dateRead,
        dateDelivered = dateDelivered,
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
    isFirstInRun = first,
    isLastInRun = last,
)
