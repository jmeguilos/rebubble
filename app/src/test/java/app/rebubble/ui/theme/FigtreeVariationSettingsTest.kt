package app.rebubble.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.width
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression guard for the Figtree variable-font weight bug.
 *
 * `figtree.ttf` has a single `wght` axis whose **default instance is 300** (`OS/2.usWeightClass`
 * is 300 too). Compose's 3- and 4-argument `Font(resId, weight, ...)` overloads build a
 * `ResourceFont` with empty `FontVariation.Settings`, so the typeface loads at that 300 default and
 * `weight` only participates in font *matching* — every declared face rasterizes Light. Because the
 * match is exact, Compose never synthesizes bold either, so nothing hides the bug.
 *
 * `ResourceFont` and its `variationSettings` are `internal` to `ui-text`, so there is nothing to
 * assert against directly. Measured advance width is the observable consequence and therefore the
 * only honest assertion: with the axis correctly pinned, heavier weights are strictly wider.
 *
 * If this test fails, [FigtreeFamily] has regressed to an overload that drops `variationSettings`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class FigtreeVariationSettingsTest {

    @get:Rule
    val rule = createComposeRule()

    /** Wide enough to accumulate a measurable difference, and all-caps to maximise stem count. */
    private val sample = "HAMBURGEFONTSIV"

    @Test
    fun `heavier declared weights rasterize wider than lighter ones`() {
        rule.setContent {
            Column {
                Text(sample, fontFamily = FigtreeFamily, fontWeight = FontWeight.Normal, maxLines = 1, modifier = Modifier.testTag("w400"))
                Text(sample, fontFamily = FigtreeFamily, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.testTag("w500"))
                Text(sample, fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.testTag("w600"))
                Text(sample, fontFamily = FigtreeFamily, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.testTag("w700"))
            }
        }

        val tags: List<String> = listOf("w400", "w500", "w600", "w700")
        val widths: List<Float> = tags.map { tag ->
            rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().width.value
        }
        val measured: String = tags.zip(widths).joinToString { (t, w) -> "$t=$w" }

        // Every step up the axis must be strictly wider. A flat sequence is the signature of the
        // bug: all four declared faces resolving to the same 300 instance. Walking all four steps
        // also proves each face is present and distinct, so no separate structural test is needed.
        for (i in 0 until widths.lastIndex) {
            val light: Float = widths[i]
            val heavy: Float = widths[i + 1]
            assertTrue(
                "expected ${tags[i + 1]} ($heavy dp) to be wider than ${tags[i]} ($light dp) — " +
                    "equal widths mean the wght axis is not pinned and every face rendered at the " +
                    "font's 300 default. Measured: $measured",
                heavy > light,
            )
        }
    }
}
