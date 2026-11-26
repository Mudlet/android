package org.mudlet.android.ui.game

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldLabelScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Material's outlined text field with [ghost] drawn greyed after the typed text, fish style. The ghost is drawn over the
 * field, not put in its text: the keyboard reads the text a field shows, an output transformation's included, and with
 * the ghost there Samsung Keyboard takes its cursor to be inside a word and replaces the letter being typed with the
 * next one. A tap on the ghost lands past the text's end, so it leaves the cursor at the end of the typed text.
 */
@Composable
fun GhostTextField(
    state: TextFieldState,
    ghost: String?,
    errorMessage: String,
    modifier: Modifier = Modifier,
    label: @Composable (TextFieldLabelScope.() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    inputTransformation: InputTransformation? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
) {
    // What OutlinedTextField(state) does around BasicTextField, which takes no decorator of the caller's
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val focused by interactionSource.collectIsFocusedAsState()
    val style = LocalTextStyle.current
    val textColor = style.color.takeOrElse {
        when {
            isError -> colors.errorTextColor
            focused -> colors.focusedTextColor
            else -> colors.unfocusedTextColor
        }
    }
    val textStyle = style.merge(TextStyle(color = textColor))
    val ghostStyle = style.merge(TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant))
    val labelHalfHeight = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2 }
    val scroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    var layout by remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    val outlined = OutlinedTextFieldDefaults.decorator(
        state = state,
        enabled = true,
        lineLimits = lineLimits,
        outputTransformation = null,
        interactionSource = interactionSource,
        label = label,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        colors = colors,
    )
    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            state = state,
            modifier = modifier
                .then(if (label != null) Modifier.semantics(mergeDescendants = true) {}.padding(top = labelHalfHeight) else Modifier)
                .then(if (isError) Modifier.semantics { error(errorMessage) } else Modifier)
                .defaultMinSize(OutlinedTextFieldDefaults.MinWidth, OutlinedTextFieldDefaults.MinHeight),
            textStyle = textStyle,
            cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            lineLimits = lineLimits,
            onTextLayout = { layout = it },
            interactionSource = interactionSource,
            inputTransformation = inputTransformation,
            scrollState = scroll,
            decorator = { innerTextField ->
                outlined.Decoration {
                    Box(
                        Modifier.fillMaxWidth().drawWithContent {
                            drawContent()
                            if (ghost != null) drawGhost(measurer.measure(ghost, ghostStyle), layout?.invoke() ?: return@drawWithContent, scroll.value)
                        },
                        propagateMinConstraints = true,
                    ) { innerTextField() }
                }
            },
        )
    }
}

// The inner field places its text [scrolled] back, mirrored in a right-to-left layout, within this box's width
private fun DrawScope.drawGhost(ghost: TextLayoutResult, typed: TextLayoutResult, scrolled: Int) {
    val textStart = if (layoutDirection == LayoutDirection.Ltr) -scrolled.toFloat() else size.width - typed.size.width + scrolled
    val end = textStart + typed.getHorizontalPosition(typed.layoutInput.text.length, usePrimaryDirection = true)
    val rtl = typed.getParagraphDirection(0) == ResolvedTextDirection.Rtl
    val x = if (rtl) end - ghost.size.width else end
    val y = typed.getLineBaseline(0) - ghost.firstBaseline
    clipRect { drawText(ghost, topLeft = Offset(x, y)) }
}

/**
 * Calls [onTap] after each tap that starts where [armed] holds, once the text field it is on has handled the tap:
 * the field handles a pointer event before the modifiers ahead of it in its chain do, so by then it has put the cursor
 * where the tap was. A tap on the ghost cannot put the cursor inside it, so it leaves the cursor at the end of the
 * typed text, where the ghost starts. A long press or a drag is not a tap. Nothing is consumed.
 */
suspend fun PointerInputScope.detectTapsAfterTheField(armed: (Offset) -> Boolean, onTap: () -> Unit) {
    awaitEachGesture {
        // The field has consumed the press by now, but acts on it only at its release
        val down = awaitFirstDown(requireUnconsumed = false)
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
