package app.rebubble.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Permanent accessibility guard over the palette in [app.rebubble.ui.theme] `Color.kt`.
 *
 * Contrast is a property of a *pair* of colours, so no amount of care picking a single token
 * protects it — the failures this test exists to catch (white on the SMS green at 2.22:1, white on
 * the old #0A7CFF at 3.93:1, `outline` aliased to `outlineVariant` at 1.62:1) were all introduced
 * by editing one side of a pair in isolation. Enumerating the pairs here makes that impossible to
 * do silently.
 *
 * Deliberately a **pure JVM** test: it reads the constants directly and does the WCAG arithmetic
 * itself, so it needs no Robolectric, no Compose runtime and no rendering, and it cannot be
 * defeated by a theme-composition detail. The trade-off is that the fill/on-colour *pairings* below
 * are transcribed from their selection sites rather than observed; if `MessageBubble.kt` or
 * `Theme.kt` starts pairing these tokens differently, update the cases here to match.
 *
 * Scheme-role pairs cover only the fallback schemes in `Theme.kt`. On Android S+ with dynamic
 * colour enabled the whole scheme is replaced by the platform's, which is generated to be
 * contrast-correct and is not ours to assert on. The bubble fills are never themed either way.
 */
class ContrastRatioTest {

    /** WCAG 2.x AA for body text at 16sp (`bodyLarge`), i.e. below the 18.66sp large-text cutoff. */
    private val aaBodyText = 4.5

    /** WCAG 2.x 1.4.11 non-text contrast, for borders and icons that carry meaning. */
    private val nonTextUi = 3.0

    private data class ContrastCase(
        val label: String,
        val foreground: Color,
        val background: Color,
        val minimum: Double,
    )

    /**
     * Every fill an own/incoming/failed bubble can take, against the on-colour actually paired with
     * it in `MessageBubble.kt`'s `containerColor`/`contentColor` branches.
     */
    private val bubblePairs = listOf(
        ContrastCase("own iMessage bubble", OnIMessageBubble, OwnIMessageBubble, aaBodyText),
        ContrastCase("own SMS bubble", OnSmsBubble, OwnSmsBubble, aaBodyText),
        // The legacy `--on-bubble` alias must stay safe on the fill it aliases.
        ContrastCase("iMessage bubble (OnBubble alias)", OnBubble, OwnIMessageBubble, aaBodyText),
        ContrastCase(
            "incoming bubble, light (surfaceContainerHigh/onSurface)",
            OnSurfaceLight,
            SurfaceContainerHighLight,
            aaBodyText,
        ),
        ContrastCase(
            "incoming bubble, dark (surfaceContainerHigh/onSurface)",
            OnSurfaceDark,
            SurfaceContainerHighDark,
            aaBodyText,
        ),
        ContrastCase(
            "failed bubble, light (errorContainer/onErrorContainer)",
            OnErrorContainerLight,
            ErrorContainerLight,
            aaBodyText,
        ),
        ContrastCase(
            "failed bubble, dark (errorContainer/onErrorContainer)",
            OnErrorContainerDark,
            ErrorContainerDark,
            aaBodyText,
        ),
    )

    /**
     * `outline` is for interactive borders and must clear 3:1 against every surface it is drawn on
     * — both bare `surface` and the `surfaceContainer` of a card/chip sitting on it.
     * `outlineVariant` is exempt: it is decorative dividers only.
     */
    private val outlinePairs = listOf(
        ContrastCase("outline vs surface, light", OutlineLight, SurfaceLight, nonTextUi),
        ContrastCase(
            "outline vs surfaceContainer, light",
            OutlineLight,
            SurfaceContainerLight,
            nonTextUi,
        ),
        ContrastCase("outline vs surface, dark", OutlineDark, SurfaceDark, nonTextUi),
        ContrastCase(
            "outline vs surfaceContainer, dark",
            OutlineDark,
            SurfaceContainerDark,
            nonTextUi,
        ),
    )

    /** The send button tints its icon with `onPrimary` on a `primary` container. */
    private val primaryPairs = listOf(
        ContrastCase("onPrimary on primary, light", OnUltramarine, Ultramarine, aaBodyText),
        ContrastCase("onPrimary on primary, dark", OnUltramarineDark, UltramarineDark, aaBodyText),
    )

    @Test
    fun `every bubble fill and its paired on-color clear AA for 16sp body text`() {
        bubblePairs.forEach(::assertMeetsMinimum)
    }

    @Test
    fun `outline clears the non-text minimum against surface and surfaceContainer`() {
        outlinePairs.forEach(::assertMeetsMinimum)
    }

    @Test
    fun `send button icon tint clears AA against the primary container`() {
        primaryPairs.forEach(::assertMeetsMinimum)
    }

    /**
     * `outline` and `outlineVariant` serve different minimums (3:1 interactive vs decorative), so
     * aliasing them to one value guarantees one of the two is wrong. Both schemes must keep them
     * distinct even if the individual values above are later retuned.
     */
    @Test
    fun `outline is a distinct token from outlineVariant in both schemes`() {
        assertTrue(
            "light outline ${hex(OutlineLight)} must differ from outlineVariant " +
                "${hex(OutlineVariantLight)} — aliasing them is what produced 1.62:1 borders",
            OutlineLight != OutlineVariantLight,
        )
        assertTrue(
            "dark outline ${hex(OutlineDark)} must differ from outlineVariant " +
                "${hex(OutlineVariantDark)} — aliasing them is what produced 1.99:1 borders",
            OutlineDark != OutlineVariantDark,
        )
    }

    /**
     * Pins the arithmetic itself against hand-checked reference values, including each of the
     * historical failures at its pre-fix value. Without this, a bug in [contrastRatio] would make
     * every assertion above vacuous.
     */
    @Test
    fun `contrast helpers reproduce known WCAG reference ratios`() {
        val cases = listOf(
            Triple("white on black", Color(0xFFFFFFFF) to Color(0xFF000000), 21.00),
            Triple("identical colors", Color(0xFF34C759) to Color(0xFF34C759), 1.00),
            Triple("white on the old #0A7CFF", Color(0xFFFFFFFF) to Color(0xFF0A7CFF), 3.93),
            Triple("white on the SMS green", Color(0xFFFFFFFF) to Color(0xFF34C759), 2.22),
            Triple("white on the dark primary", Color(0xFFFFFFFF) to Color(0xFFB9C3FF), 1.71),
            Triple("#CAC4D0 on the light surface", Color(0xFFCAC4D0) to Color(0xFFFEF7FF), 1.62),
        )
        cases.forEach { (label, pair, expected) ->
            assertEquals(label, expected, contrastRatio(pair.first, pair.second), 0.005)
        }
    }

    private fun assertMeetsMinimum(case: ContrastCase) {
        val ratio = contrastRatio(case.foreground, case.background)
        assertTrue(
            "${case.label}: ${hex(case.foreground)} on ${hex(case.background)} is " +
                "${format(ratio)}:1, below the required ${format(case.minimum)}:1 " +
                "(shortfall ${format(case.minimum - ratio)}). Fix the pair, do not lower the bar.",
            ratio >= case.minimum,
        )
    }

    /**
     * WCAG 2.x relative luminance. The spec's linear/gamma cutoff is written as 0.03928 in WCAG 2.0
     * and 0.04045 in 2.1; the two disagree only for 8-bit channel values strictly between 10 and
     * 11, so they are equivalent for every colour in this palette.
     */
    private fun relativeLuminance(color: Color): Double {
        fun linearize(component: Float): Double {
            val c = component.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun hex(color: Color): String {
        fun channel(component: Float): Int = Math.round(component * 255f)
        return "#%02X%02X%02X".format(channel(color.red), channel(color.green), channel(color.blue))
    }

    private fun format(value: Double): String = "%.2f".format(value)
}
