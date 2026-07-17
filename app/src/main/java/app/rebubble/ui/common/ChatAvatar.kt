package app.rebubble.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import java.io.File

/** Default list-row avatar diameter (Messages-style). */
val ChatAvatarSizeLarge = 56.dp

/** Compact avatar for the chat app bar. */
val ChatAvatarSizeCompact = 36.dp

/**
 * Contact / group avatar: photo when [avatarPath] exists, otherwise a monogram
 * (or person glyph for phone-number-only titles). Groups use overlapping discs,
 * clipped to [size].
 */
@Composable
fun ChatAvatar(
    title: String,
    avatarPath: String?,
    isGroup: Boolean,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    size: Dp = ChatAvatarSizeLarge,
) {
    val label = remember(title) { avatarLabelForTitle(title) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarPath.isNullOrBlank() && File(avatarPath).isFile) {
            AsyncImage(
                model = File(avatarPath),
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        } else if (isGroup) {
            StackedMonogram(
                initials = when (label) {
                    is AvatarLabel.Initials -> label.value
                    AvatarLabel.Person -> "?"
                },
                size = size,
            )
        } else {
            when (label) {
                is AvatarLabel.Initials -> MonogramCircle(initials = label.value, size = size)
                AvatarLabel.Person -> PersonMonogram(size = size)
            }
        }
    }
}

@Composable
fun MonogramCircle(
    initials: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val textStyle = if (size >= ChatAvatarSizeLarge) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.labelLarge
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = textStyle,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun PersonMonogram(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = null,
            modifier = Modifier.size(size * 0.55f),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Quiet group treatment: two overlapping tonal monogram discs, clipped to [size]. */
@Composable
fun StackedMonogram(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = ChatAvatarSizeLarge,
) {
    val disc = size * (36f / 56f)
    val primary = initials.take(1).ifEmpty { "?" }
    val secondary = initials.drop(1).take(1).ifEmpty { primary }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    ) {
        MonogramCircle(
            initials = secondary,
            size = disc,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        MonogramCircle(
            initials = primary,
            size = disc,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

sealed interface AvatarLabel {
    data class Initials(val value: String) : AvatarLabel
    data object Person : AvatarLabel
}

/**
 * Initials for display titles. Phone-number-only titles (e.g. `+15551234567`) map to
 * [AvatarLabel.Person] so the avatar shows a person glyph instead of `+1`.
 */
internal fun avatarLabelForTitle(title: String): AvatarLabel {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return AvatarLabel.Initials("?")
    if (isPhoneNumberTitle(trimmed)) return AvatarLabel.Person
    return AvatarLabel.Initials(titleInitials(trimmed))
}

/** True when [title] is essentially a phone number (digits + phone punctuation only). */
internal fun isPhoneNumberTitle(title: String): Boolean {
    val digits = title.count { it.isDigit() }
    if (digits < 7) return false
    return title.all { ch ->
        ch.isDigit() || ch.isWhitespace() || ch in "+()-./"
    }
}

internal fun titleInitials(title: String): String {
    val parts = title.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> {
            val part = parts[0]
            if (isPhoneNumberTitle(part)) "?" else part.take(2).uppercase()
        }
        else -> buildString {
            val first = parts[0]
            val second = parts[1]
            append(
                if (isPhoneNumberTitle(first) || !first.first().isLetter()) {
                    '?'
                } else {
                    first.first().uppercaseChar()
                },
            )
            append(
                if (isPhoneNumberTitle(second) || !second.first().isLetter()) {
                    '?'
                } else {
                    second.first().uppercaseChar()
                },
            )
        }
    }
}
