package app.rebubble.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the two properties of [Typography] that drifted and caused visible bugs.
 *
 * 1. **Sizes follow the M3 baseline scale.** The scale previously sat about one step low
 *    (`displaySmall` 28 vs 36, `headlineMedium` 24 vs 28, `titleLarge` 20 vs 22), which is what made
 *    `headlineMedium` look like a plausible top-app-bar title when M3 specifies `titleLarge`.
 * 2. **The display face is confined to its documented roles.** `design/foundations/type.html`:
 *    Figtree covers display/headline/`titleLarge` and avatar monograms; everything 16sp and below
 *    uses the platform body face. A regression here is invisible in review but changes the feel of
 *    every conversational surface.
 *
 * These are plain JVM assertions — [Typography] is constructed eagerly and needs no Compose runtime.
 */
class TypeScaleTest {

    private fun sizeOf(style: TextStyle): TextUnit = style.fontSize

    @Test
    fun `display and headline roles match the M3 baseline scale`() {
        assertEquals(57.sp, sizeOf(Typography.displayLarge))
        assertEquals(45.sp, sizeOf(Typography.displayMedium))
        assertEquals(36.sp, sizeOf(Typography.displaySmall))
        assertEquals(32.sp, sizeOf(Typography.headlineLarge))
        assertEquals(28.sp, sizeOf(Typography.headlineMedium))
        assertEquals(24.sp, sizeOf(Typography.headlineSmall))
    }

    @Test
    fun `title roles match the M3 baseline scale`() {
        // 22sp is the M3 small-top-app-bar title size. Screens must use this role, not headlineMedium.
        assertEquals(22.sp, sizeOf(Typography.titleLarge))
        assertEquals(16.sp, sizeOf(Typography.titleMedium))
        assertEquals(14.sp, sizeOf(Typography.titleSmall))
    }

    @Test
    fun `the scale is strictly monotonic from displayLarge down to titleSmall`() {
        // Guards the failure mode that raising one role in isolation creates: displaySmall moving to
        // 36sp while displayMedium sat at 32sp would have inverted the ladder.
        val ladder = listOf(
            "displayLarge" to Typography.displayLarge,
            "displayMedium" to Typography.displayMedium,
            "displaySmall" to Typography.displaySmall,
            "headlineLarge" to Typography.headlineLarge,
            "headlineMedium" to Typography.headlineMedium,
            "headlineSmall" to Typography.headlineSmall,
            "titleLarge" to Typography.titleLarge,
            "titleMedium" to Typography.titleMedium,
            "titleSmall" to Typography.titleSmall,
        )
        for (i in 0 until ladder.lastIndex) {
            val (biggerName, bigger) = ladder[i]
            val (smallerName, smaller) = ladder[i + 1]
            assertTrue(
                "$biggerName (${sizeOf(bigger)}) must be strictly larger than " +
                    "$smallerName (${sizeOf(smaller)})",
                sizeOf(bigger).value > sizeOf(smaller).value,
            )
        }
    }

    @Test
    fun `the display face is used only for display, headline and titleLarge`() {
        val displayRoles = mapOf(
            "displayLarge" to Typography.displayLarge,
            "displayMedium" to Typography.displayMedium,
            "displaySmall" to Typography.displaySmall,
            "headlineLarge" to Typography.headlineLarge,
            "headlineMedium" to Typography.headlineMedium,
            "headlineSmall" to Typography.headlineSmall,
            "titleLarge" to Typography.titleLarge,
        )
        displayRoles.forEach { (name, style) ->
            assertEquals("$name must use the Figtree display face", FigtreeFamily, style.fontFamily)
        }
    }

    @Test
    fun `everything 16sp and below uses the platform body face`() {
        val bodyRoles = mapOf(
            "titleMedium" to Typography.titleMedium,
            "titleSmall" to Typography.titleSmall,
            "bodyLarge" to Typography.bodyLarge,
            "bodyMedium" to Typography.bodyMedium,
            "bodySmall" to Typography.bodySmall,
            "labelLarge" to Typography.labelLarge,
            "labelMedium" to Typography.labelMedium,
            "labelSmall" to Typography.labelSmall,
        )
        bodyRoles.forEach { (name, style) ->
            assertEquals(
                "$name must use the platform body face, not the brand display face",
                FontFamily.Default,
                style.fontFamily,
            )
        }
    }
}
