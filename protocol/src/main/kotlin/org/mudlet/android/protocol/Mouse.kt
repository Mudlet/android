package org.mudlet.android.protocol

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A mouse wheel's turns on a console, as desktop Mudlet's console takes them (`TTextEdit::wheelEvent()`): three rows
 * a notch, one with Shift, half the rows on the screen with Control. A turn of less than a row is kept, in whole
 * degrees as desktop keeps it, for the next, so a smooth wheel scrolls as far as a notched one.
 */
class ConsoleWheel {
    private var remainderDegrees = 0

    /**
     * The rows the console scrolls down, or up where negative, for a turn of [notches], positive toward the player as
     * Compose gives a scroll, with [shift] and [control] held, on a console [screenRows] rows high.
     */
    fun rows(notches: Float, shift: Boolean, control: Boolean, screenRows: Int): Int {
        val perNotch = when {
            shift -> 1.0
            control -> qRound(screenRows / 2.0).toDouble()
            else -> ROWS_A_NOTCH
        }
        val degrees = notches * NOTCH_DEGREES * perNotch + remainderDegrees
        val rows = qRound(degrees / NOTCH_DEGREES)
        // Desktop keeps it in a QPoint, which drops the fraction
        remainderDegrees = (degrees - NOTCH_DEGREES * rows).toInt()
        return rows
    }

    private companion object {
        const val NOTCH_DEGREES = 15.0
        const val ROWS_A_NOTCH = 3.0
    }
}

/**
 * A mouse wheel's turns on a map, as desktop Mudlet's map takes them (`TMapPainter::zoomAtPoint()`): a notch away from
 * the player zooms in by 7%, toward them out, and five notches' worth with Control.
 */
object MapWheel {
    private const val NOTCH_ZOOM = 1.07
    private const val CONTROL_NOTCHES = 5

    /**
     * What a turn of [notches], positive toward the player as Compose gives a scroll, scales the map by, with [control]
     * held.
     */
    fun zoom(notches: Float, control: Boolean): Double = NOTCH_ZOOM.pow(-notches.toDouble() * if (control) CONTROL_NOTCHES else 1)

    /**
     * The same turn as `mapPointer`'s `wheelY`: eighths of a degree, positive away from the player (docs/protocol.md),
     * five times over with [control] held, as the engine reads no keys from it.
     */
    fun wheelY(notches: Float, control: Boolean): Int = (-notches * ZoomNotches.WHEEL_NOTCH * if (control) CONTROL_NOTCHES else 1).roundToInt()
}

/** What a right click on a line of a console does where it is let go ([rightClick]). */
enum class RightClick { LINE_MENU, LINK_MENU, REVEAL, NOTHING }

/**
 * What a right click does on a line, [onLink] or not, of which [link] is what `getLink` said of the link there, null
 * until it answers, as desktop's console does on the release (`TTextEdit::mouseReleaseEvent()`): on a spoiler not yet
 * revealed it reveals it, even a disabled one; else on a link that is not disabled it opens its menu where it has one;
 * elsewhere on a link nothing, and off one the console's own menu, the line's here.
 */
fun rightClick(onLink: Boolean, link: EngineMessage.Link?): RightClick = when {
    !onLink -> RightClick.LINE_MENU
    link == null -> RightClick.NOTHING
    link.spoiler && !link.revealed -> RightClick.REVEAL
    link.disabled || !link.menu -> RightClick.NOTHING
    else -> RightClick.LINK_MENU
}

// Qt's qRound: halves away from zero
private fun qRound(value: Double): Int = (if (value >= 0) floor(value + 0.5) else ceil(value - 0.5)).toInt()
