package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import org.mudlet.android.R

/**
 * The commands sent from this screen, newest last, and where the up and down buttons have got to in them: [position]
 * is an index into [sent], or `sent.size` for the line being typed, which is kept in [draft] while an older one shows.
 */
data class CommandHistory(val sent: List<String> = emptyList(), val position: Int = sent.size, val draft: String = "") {
    val canGoBack get() = position > 0
    val canGoForward get() = position < sent.size

    /** The line the field shows at [position]. */
    val shown get() = if (position < sent.size) sent[position] else draft

    /** After [line] was sent: kept unless it is blank or repeats the last one, and back at a new, empty line. */
    fun add(line: String): CommandHistory {
        val kept = if (line.isBlank() || line == sent.lastOrNull()) sent else (sent + line).takeLast(MAX_SENT)
        return CommandHistory(kept)
    }

    /** One command further back; [typed] is the field's text, kept as the draft when leaving the new line. */
    fun back(typed: String): CommandHistory = if (!canGoBack) this else copy(position = position - 1, draft = if (canGoForward) draft else typed)

    fun forward(): CommandHistory = if (!canGoForward) this else copy(position = position + 1)

    companion object {
        const val MAX_SENT = 100

        val Saver = listSaver<CommandHistory, Any>(
            save = { listOf(ArrayList(it.sent), it.position, it.draft) },
            restore = { CommandHistory((it[0] as List<*>).map { line -> line as String }, it[1] as Int, it[2] as String) },
        )
    }
}

/**
 * The line the player types commands in. The keyboard's Send key or the Send button hands the line to [onSend] and
 * leaves the keyboard open; [onSend] returns false when the command could not go out, and the line then stays. The
 * up and down buttons, and a hardware keyboard's arrow keys, step through the lines sent before.
 */
@Composable
fun InputLine(onSend: (String) -> Boolean, modifier: Modifier = Modifier) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var history by rememberSaveable(stateSaver = CommandHistory.Saver) { mutableStateOf(CommandHistory()) }
    var notSent by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun show(moved: CommandHistory) {
        history = moved
        text = TextFieldValue(moved.shown, TextRange(moved.shown.length))
    }

    fun submit() {
        val line = text.text
        notSent = !onSend(line)
        if (!notSent) {
            history = history.add(line)
            text = TextFieldValue()
        }
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                notSent = false
            },
            modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.DirectionUp && history.canGoBack -> show(history.back(text.text))
                    event.key == Key.DirectionDown && history.canGoForward -> show(history.forward())
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
            label = { Text(stringResource(R.string.input_label)) },
            supportingText = if (notSent) ({ Text(stringResource(R.string.input_not_sent)) }) else null,
            isError = notSent,
            singleLine = true,
            // Game commands are not prose: corrections would change them
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        IconButton({ show(history.back(text.text)) }, enabled = history.canGoBack) {
            Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.input_history_previous))
        }
        IconButton({ show(history.forward()) }, enabled = history.canGoForward) {
            Icon(painterResource(R.drawable.ic_arrow_downward), stringResource(R.string.input_history_next))
        }
        IconButton({
            submit()
            focus.requestFocus()
        }) {
            Icon(painterResource(R.drawable.ic_send), stringResource(R.string.input_send))
        }
    }
}
