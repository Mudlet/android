package org.mudlet.android.ui.game

import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Takes the place of the screen's haptic feedback when not null: the tests' way to feel a switch's tick. */
@VisibleForTesting
internal var switchHaptics: HapticFeedback? = null

/**
 * The slide of a switch between games (docs/multi-profile.md, "The switcher"): a drag on the game pill moves the front
 * game sideways with the finger, and a release past [COMMIT] of the pill's width, or a fling, slides it out and the game
 * beside it in; otherwise it springs back. Toward a side with no game it gives only a little, as a rubber band does.
 * [offset] is how far the game has slid, as a share of its width, negative to the left.
 */
@Stable
class GameSwipe(private val scope: CoroutineScope) {
    var offset by mutableFloatStateOf(0f)
        private set

    // The drag so far, as a share of the pill's width, before the rubber band
    private var dragged = 0f
    private var sliding: Job? = null

    /** A drag begins, taking over from a slide still running. */
    fun startDrag() {
        sliding?.cancel()
        dragged = offset
    }

    /** The finger moved [share] of the pill's width; [canLeft] and [canRight] say whether a game is that way. */
    fun drag(share: Float, canLeft: Boolean, canRight: Boolean) {
        dragged += share
        offset = if ((dragged < 0 && !canLeft) || (dragged > 0 && !canRight)) band(dragged) else dragged.coerceIn(-1f, 1f)
    }

    /**
     * The finger lifted, flinging left (-1), right (1) or not (0). Slides in [leftward], the game a slide to the left
     * brings, or [rightward], with [switch] bringing it to the front, and is true; false when it springs back.
     */
    fun release(fling: Int, leftward: String?, rightward: String?, switch: (String) -> Boolean): Boolean {
        val direction = when {
            fling != 0 -> if (fling.toFloat() == sign(offset)) fling else 0
            offset <= -COMMIT -> -1
            offset >= COMMIT -> 1
            else -> 0
        }
        val target = when {
            direction < 0 -> leftward
            direction > 0 -> rightward
            else -> null
        }
        if (target == null) {
            settle()
            return false
        }
        val out = direction.toFloat()
        launchSlide {
            animate(offset, out, animationSpec = tween(OUT_MS)) { value, _ -> offset = value }
            if (switch(target)) arriving(out < 0) else settling()
        }
        return true
    }

    /** Springs back to rest, as when the system took the drag for its back gesture. */
    fun settle() = launchSlide { settling() }

    /** The game brought to the front by other means slides in, from the right when [left] it went. */
    fun arrive(left: Boolean) = launchSlide { arriving(left) }

    private suspend fun arriving(left: Boolean) {
        offset = if (left) 1f else -1f
        animate(offset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) { value, _ -> offset = value }
    }

    private suspend fun settling() = animate(offset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> offset = value }

    private fun launchSlide(block: suspend () -> Unit) {
        sliding?.cancel()
        sliding = scope.launch { block() }
    }

    companion object {
        /** How far, as a share of the pill's width, a drag goes before its release switches games. */
        const val COMMIT = 0.4f

        // How far the rubber band gives at most, as a share of the width
        private const val BAND = 0.15f
        private const val OUT_MS = 150

        private fun band(dragged: Float) = dragged * BAND / (BAND + abs(dragged))
    }
}

// A release this fast is a fling, however short the drag
private val FLING_VELOCITY = 500.dp

/**
 * Takes a horizontal drag for [swipe], with the games a slide to the left and to the right brings ([leftward] and
 * [rightward]), which [switch] brings to the front; [onSwitched] follows each switch it starts. Only a drag past the
 * touch slop is taken, so a tap still taps, and a drag the system takes back, as for its back gesture, springs back.
 * Read on each drag, so the lambdas may give what changes.
 */
fun Modifier.gameSwipe(
    swipe: GameSwipe,
    leftward: () -> String?,
    rightward: () -> String?,
    switch: (String) -> Boolean,
    onSwitched: () -> Unit,
): Modifier = pointerInput(swipe) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val velocity = VelocityTracker()
        velocity.addPointerInputChange(down)
        val width = size.width.toFloat().coerceAtLeast(1f)
        fun moved(share: Float) = swipe.drag(share, canLeft = leftward() != null, canRight = rightward() != null)
        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
            change.consume()
            velocity.addPointerInputChange(change)
            swipe.startDrag()
            moved(overSlop / width)
        } ?: return@awaitEachGesture
        val lifted = horizontalDrag(start.id) { change ->
            velocity.addPointerInputChange(change)
            moved(change.positionChange().x / width)
            change.consume()
        }
        if (!lifted) {
            swipe.settle()
            return@awaitEachGesture
        }
        val speed = velocity.calculateVelocity().x
        val fling = if (abs(speed) >= FLING_VELOCITY.toPx()) sign(speed).toInt() else 0
        if (swipe.release(fling, leftward(), rightward(), switch)) onSwitched()
    }
}

/** The tick a switch by swipe gives. */
@Composable
fun rememberSwitchTick(): () -> Unit {
    val haptics = switchHaptics ?: LocalHapticFeedback.current
    return remember(haptics) { { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) } }
}
