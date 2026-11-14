package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import org.mudlet.android.R

/**
 * Where the up and down buttons have got to in the history, which is newest first: [position] is an index into it,
 * or -1 for the line being typed, which is kept in [draft] while an older one shows.
 */
data class HistoryPosition(val position: Int = -1, val draft: String = "") {
    fun canGoBack(history: List<String>) = position + 1 < history.size
    val canGoForward get() = position >= 0

    /** The line the field shows at [position]. */
    fun shown(history: List<String>) = history.getOrNull(position) ?: draft

    /** One line further back; [typed] is the field's text, kept as the draft when leaving the new line. */
    fun back(history: List<String>, typed: String): HistoryPosition = if (!canGoBack(history)) this else HistoryPosition(position + 1, if (canGoForward) draft else typed)

    fun forward(): HistoryPosition = if (!canGoForward) this else copy(position = position - 1)

    companion object {
        val Saver = listSaver<HistoryPosition, Any>(
            save = { listOf(it.position, it.draft) },
            restore = { HistoryPosition(it[0] as Int, it[1] as String) },
        )
    }
}

private enum class Notice { NOT_SENT, NO_COMPLETION }

/**
 * The line the player types commands in. The keyboard's Send key or the Send button hands the line to [onSend] and
 * leaves the keyboard open; each `on...` returns false when its request could not go out, and the line then stays.
 * The up and down buttons, and a hardware keyboard's arrow keys, step through [history] (newest first). The completion
 * button, or a hardware Tab, hands the line to [onComplete], whose answer replaces it. A [commandLine] from a script
 * replaces the line, which then calls [onCommandLineShown].
 *
 * While [hiddenInput] holds, a masked password field takes the line's place and sends with [onSendHidden]; its close
 * button calls [onDismissHidden]. What is typed there is not saved across a rotation, to keep it out of saved state.
 */
@Composable
fun InputLine(
    hiddenInput: Boolean,
    history: List<String>,
    commandLine: String?,
    onCommandLineShown: () -> Unit,
    onSend: (String) -> Boolean,
    onSendHidden: (String) -> Boolean,
    onDismissHidden: () -> Boolean,
    onComplete: (String, (String) -> Unit) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var secret by remember { mutableStateOf(TextFieldValue()) }
    var at by rememberSaveable(stateSaver = HistoryPosition.Saver) { mutableStateOf(HistoryPosition()) }
    var notice by rememberSaveable { mutableStateOf<Notice?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val hiddenFocus = remember { FocusRequester() }

    fun show(line: String) {
        text = TextFieldValue(line, TextRange(line.length))
        notice = null
    }

    fun move(moved: HistoryPosition) {
        at = moved
        show(moved.shown(history))
    }

    fun submit() {
        val line = text.text
        notice = if (onSend(line)) null else Notice.NOT_SENT
        if (notice == null) {
            at = HistoryPosition()
            text = TextFieldValue()
        }
    }

    fun submitHidden() {
        notice = if (onSendHidden(secret.text)) null else Notice.NOT_SENT
        if (notice == null) secret = TextFieldValue()
    }

    fun complete() {
        val asked = text.text
        val sent = onComplete(asked) { completed ->
            // The player typed on meanwhile: their line wins
            if (text.text != asked) return@onComplete
            if (completed.isEmpty()) notice = Notice.NO_COMPLETION else show(completed)
        }
        notice = if (sent) null else Notice.NOT_SENT
    }

    // Read as the field swaps, before the old one's focus is gone: the new one takes the focus it had
    val hadFocus = remember(hiddenInput) { focused }
    LaunchedEffect(hiddenInput) {
        if (!hiddenInput) secret = TextFieldValue()
        notice = null
        if (hadFocus) (if (hiddenInput) hiddenFocus else focus).requestFocus()
    }
    LaunchedEffect(commandLine) {
        if (commandLine == null) return@LaunchedEffect
        at = HistoryPosition()
        show(commandLine)
        onCommandLineShown()
    }

    val supportingText: (@Composable () -> Unit)? = notice?.let {
        {
            val message = if (it == Notice.NOT_SENT) R.string.input_not_sent else R.string.input_no_completion
            Text(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
    val trackFocus = Modifier.onFocusChanged { focused = it.isFocused }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (hiddenInput) {
            OutlinedTextField(
                value = secret,
                onValueChange = {
                    secret = it
                    notice = null
                },
                modifier = Modifier.weight(1f).focusRequester(hiddenFocus).then(trackFocus),
                label = { Text(stringResource(R.string.input_password_label)) },
                supportingText = supportingText,
                isError = notice == Notice.NOT_SENT,
                singleLine = true,
                // Password semantics: TalkBack says "password", not what is typed
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submitHidden() }),
            )
            IconButton({ if (!onDismissHidden()) notice = Notice.NOT_SENT }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.input_password_close))
            }
            IconButton({
                submitHidden()
                hiddenFocus.requestFocus()
            }) {
                Icon(painterResource(R.drawable.ic_send), stringResource(R.string.input_send))
            }
        } else {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    notice = null
                },
                modifier = Modifier.weight(1f).focusRequester(focus).then(trackFocus).onPreviewKeyEvent { event ->
                    val tab = event.key == Key.Tab && !event.isShiftPressed
                    // Tab's key up too, so it cannot move the focus
                    if (tab && event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent true
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        tab -> complete()
                        event.key == Key.DirectionUp && at.canGoBack(history) -> move(at.back(history, text.text))
                        event.key == Key.DirectionDown && at.canGoForward -> move(at.forward())
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
                label = { Text(stringResource(R.string.input_label)) },
                trailingIcon = {
                    IconButton({ complete() }) {
                        Icon(painterResource(R.drawable.ic_keyboard_tab), stringResource(R.string.input_complete))
                    }
                },
                supportingText = supportingText,
                isError = notice == Notice.NOT_SENT,
                singleLine = true,
                // Game commands are not prose: corrections would change them
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            IconButton({ move(at.back(history, text.text)) }, enabled = at.canGoBack(history)) {
                Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.input_history_previous))
            }
            IconButton({ move(at.forward()) }, enabled = at.canGoForward) {
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
}
