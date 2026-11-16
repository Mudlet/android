package org.mudlet.android.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

/**
 * Material 3 Expressive's motion, as springs: spatial ones for what moves or changes size, effects ones, which never
 * overshoot, for colour and opacity. The values are MotionScheme.expressive()'s defaults, set here as springs so that the
 * app needs none of Material's experimental Expressive API.
 */
object MudletMotion {
    private const val SPATIAL_DAMPING = 0.8f
    private const val SPATIAL_STIFFNESS = 380f
    private const val EFFECTS_STIFFNESS = 1600f

    fun <T> spatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS)

    /** [spatial] for a slide, whose offsets are whole pixels. */
    val slide: FiniteAnimationSpec<IntOffset> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, IntOffset(1, 1))

    fun <T> effects(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, EFFECTS_STIFFNESS)
}
