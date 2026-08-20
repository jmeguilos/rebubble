package app.rebubble.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Expressive-style motion specs built on public APIs.
 *
 * material3 1.4.0 keeps MaterialTheme.motionScheme internal, so we mirror its
 * fast spatial/effects pairing with explicit springs: spatial movement gets a
 * slight bounce, effects (fades) settle without overshoot.
 */
object RebubbleMotion {
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 1400f)

    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)
}
