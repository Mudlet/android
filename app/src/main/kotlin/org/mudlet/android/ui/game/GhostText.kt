package org.mudlet.android.ui.game

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.text.SpanStyle

/**
 * Shows [ghost] in [color] after the text, fish style, without changing the text: the field's state, and so the
 * keyboard, holds only what was typed, and the cursor stays within it. InputLine sets the field's semantics to the
 * typed text, so TalkBack does not read the ghost as part of it.
 */
data class GhostTransformation(val ghost: String, val color: Color) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val typed = length
        append(ghost)
        addStyle(SpanStyle(color = color), typed, length)
    }
}

/**
 * Calls [onTap] after each tap that starts where [armed] holds, once the text field this modifies has handled it:
 * the field handles a pointer event before the modifiers ahead of it in its chain do, so by then it has put the cursor
 * where the tap was. A tap on the ghost cannot put the cursor inside it, so it leaves the cursor at the end of the
 * typed text, where the ghost starts. A long press or a drag is not a tap. Nothing is consumed.
 */
suspend fun PointerInputScope.detectTapsAfterTheField(armed: (Offset) -> Boolean, onTap: () -> Unit) {
    awaitEachGesture {
        // Initial: read before the field acts on the press, which can move the focus
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!armed(down.position)) return@awaitEachGesture
        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var change = down
            while (change.pressed) {
                change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
            }
            true
        }
        if (tapped == true) onTap()
    }
}
