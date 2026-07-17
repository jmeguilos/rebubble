package app.rebubble.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = Ultramarine,
    onPrimary = OnUltramarine,
    primaryContainer = UltramarineContainer,
    onPrimaryContainer = OnUltramarineContainer,
    secondary = InkMuted,
    onSecondary = Color.White,
    secondaryContainer = Mist,
    onSecondaryContainer = Ink,
    tertiary = UltramarineLight,
    onTertiary = OnUltramarineContainer,
    background = SoftSurface,
    onBackground = Ink,
    surface = SoftSurface,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = InkMuted,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val SlateOnDark = Color(0xFFC3C6D6)

private val DarkColorScheme = darkColorScheme(
    primary = UltramarineLight,
    onPrimary = OnUltramarineContainer,
    primaryContainer = Ultramarine,
    onPrimaryContainer = UltramarineContainer,
    secondary = SlateOnDark,
    onSecondary = SoftSurfaceDark,
    secondaryContainer = Color(0xFF2A2D38),
    onSecondaryContainer = Mist,
    background = SoftSurfaceDark,
    onBackground = Color(0xFFE4E5ED),
    surface = SoftSurfaceDark,
    onSurface = Color(0xFFE4E5ED),
    surfaceVariant = Color(0xFF2A2D38),
    onSurfaceVariant = Color(0xFFC3C6D6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** Spring used for onboarding screen transitions (M3 Expressive MotionScheme is not public in 1.4.0). */
val RebubbleSpatialSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

@Composable
fun RebubbleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = RebubbleShapes,
        content = content,
    )
}
