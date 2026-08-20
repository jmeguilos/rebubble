package app.rebubble.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.rebubble.ui.theme.avatarHueFor
import coil3.ImageLoader
import coil3.compose.AsyncImage
import java.io.File

/** Default list-row avatar diameter (Messages-style). */
val ChatAvatarSizeLarge = 56.dp

/** Compact avatar for the chat app bar (card uses 40dp). */
val ChatAvatarSizeCompact = 40.dp

/**
 * Contact / group avatar: photo when [avatarPath] exists, otherwise a monogram
 * (or person glyph for phone-number-only titles). Groups use a single clipped
 * circle with a group glyph.
 *
 * Named-contact monograms *and* group glyphs pick a stable hue from [hueKey] (chat guid preferred)
 * — the design card shows the group row tinted like any named contact, with the glyph taking the
 * hue's foreground. Only the unknown-number person glyph stays neutral (surfaceContainerHighest +
 * onSurfaceVariant), which is what keeps it reading as "no identity known".
 */
@Composable
fun ChatAvatar(
    title: String,
    avatarPath: String?,
    isGroup: Boolean,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    size: Dp = ChatAvatarSizeLarge,
    /** Stable key for hue selection — chat guid, or sender address for per-sender. */
    hueKey: String = title,
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
            GroupMonogram(size = size, hueKey = hueKey)
        } else {
            when (label) {
                is AvatarLabel.Initials -> MonogramCircle(
                    initials = label.value,
                    size = size,
                    hueKey = hueKey,
                )
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
    hueKey: String = initials,
) {
    val dark = isSystemInDarkTheme()
    val hue = remember(hueKey, dark) { avatarHueFor(hueKey, dark) }
    val textStyle = if (size >= ChatAvatarSizeLarge) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.labelLarge
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(hue.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = textStyle.copy(
                fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
                fontWeight = FontWeight.SemiBold,
            ),
            color = hue.foreground,
        )
    }
}

@Composable
private fun PersonMonogram(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    GlyphAvatar(
        size = size,
        modifier = modifier,
        icon = Icons.Outlined.Person,
    )
}

/**
 * Group avatar: [Icons.Outlined.Group] on the hue picked from [hueKey] (chat guid), tinted with
 * that hue's foreground — same treatment as a named contact's monogram, per the chat-list card.
 */
@Composable
fun GroupMonogram(
    modifier: Modifier = Modifier,
    size: Dp = ChatAvatarSizeLarge,
    hueKey: String = "",
) {
    val dark = isSystemInDarkTheme()
    val hue = remember(hueKey, dark) { avatarHueFor(hueKey, dark) }
    GlyphAvatar(
        size = size,
        modifier = modifier,
        icon = Icons.Outlined.Group,
        background = hue.background,
        foreground = hue.foreground,
    )
}

@Composable
private fun GlyphAvatar(
    size: Dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    foreground: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(size * 0.55f),
            tint = foreground,
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
