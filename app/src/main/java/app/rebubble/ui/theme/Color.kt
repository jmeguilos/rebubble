package app.rebubble.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens from `design/_tokens.css` / chat card `:root` + `.dark`.
 * Dynamic color still replaces the scheme on Android S+ when enabled.
 */

// Brand
val Ultramarine = Color(0xFF2D4FE0)
val OnUltramarine = Color(0xFFFFFFFF)
val UltramarineContainer = Color(0xFFDCE1FF)
val OnUltramarineContainer = Color(0xFF00105C)
val SecondaryContainerLight = Color(0xFFDFE1F9)
val OnSecondaryContainerLight = Color(0xFF171B2C)

val UltramarineDark = Color(0xFFB9C3FF)
val OnUltramarineDark = Color(0xFF00184A)
val UltramarineContainerDark = Color(0xFF1E3163)
val OnUltramarineContainerDark = Color(0xFFDCE1FF)
val SecondaryContainerDark = Color(0xFF434659)
val OnSecondaryContainerDark = Color(0xFFDFE1F9)

// M3 baseline neutrals (lavender-tinted) — light
val SurfaceLight = Color(0xFFFEF7FF)
val SurfaceContainerLowLight = Color(0xFFF7F2FA)
val SurfaceContainerLight = Color(0xFFF3EDF7)
val SurfaceContainerHighLight = Color(0xFFECE6F0)
val SurfaceContainerHighestLight = Color(0xFFE6E0E9)
val OnSurfaceLight = Color(0xFF1D1B20)
val OnSurfaceVariantLight = Color(0xFF49454F)
val OutlineVariantLight = Color(0xFFCAC4D0)

// M3 baseline neutrals — dark
val SurfaceDark = Color(0xFF141218)
val SurfaceContainerLowDark = Color(0xFF1D1B20)
val SurfaceContainerDark = Color(0xFF211F26)
val SurfaceContainerHighDark = Color(0xFF2B292F)
val SurfaceContainerHighestDark = Color(0xFF36343B)
val OnSurfaceDark = Color(0xFFE6E0E9)
val OnSurfaceVariantDark = Color(0xFFCAC4D0)
val OutlineVariantDark = Color(0xFF49454F)

// Error
val ErrorLight = Color(0xFFB3261E)
val ErrorContainerLight = Color(0xFFF9DEDC)
val OnErrorContainerLight = Color(0xFF410E0B)
val ErrorDark = Color(0xFFF2B8B5)
val ErrorContainerDark = Color(0xFF8C1D18)
val OnErrorContainerDark = Color(0xFFF9DEDC)

/** iMessage own-bubble blue (semantic — never themed). */
val OwnIMessageBubble = Color(0xFF0A7CFF)

/** SMS own-bubble green (semantic — never themed). */
val OwnSmsBubble = Color(0xFF34C759)

val OnBubble = Color(0xFFFFFFFF)

/**
 * Per-contact avatar hue pair (M3 tone-90 bg / tone-10 fg in light;
 * inverted roles in dark). Cycle by stable hash of chat guid / sender.
 */
data class AvatarHue(val background: Color, val foreground: Color)

val AvatarHuesLight = listOf(
    AvatarHue(Color(0xFFFFD9E2), Color(0xFF3E001D)),
    AvatarHue(Color(0xFFDCE1FF), Color(0xFF001159)),
    AvatarHue(Color(0xFFB2EBE4), Color(0xFF00201C)),
    AvatarHue(Color(0xFFFFDF9E), Color(0xFF261A00)),
    AvatarHue(Color(0xFFEADDFF), Color(0xFF21005D)),
    AvatarHue(Color(0xFFC4EED0), Color(0xFF002110)),
)

val AvatarHuesDark = listOf(
    AvatarHue(Color(0xFF5D1133), Color(0xFFFFD9E2)),
    AvatarHue(Color(0xFF16307E), Color(0xFFDCE1FF)),
    AvatarHue(Color(0xFF00504A), Color(0xFFB2EBE4)),
    AvatarHue(Color(0xFF5C4300), Color(0xFFFFDF9E)),
    AvatarHue(Color(0xFF4A2F87), Color(0xFFEADDFF)),
    AvatarHue(Color(0xFF0E5223), Color(0xFFC4EED0)),
)

fun avatarHueFor(key: String, darkTheme: Boolean): AvatarHue {
    val palette = if (darkTheme) AvatarHuesDark else AvatarHuesLight
    val index = (key.hashCode().toLong() and 0x7FFF_FFFFL) % palette.size
    return palette[index.toInt()]
}
