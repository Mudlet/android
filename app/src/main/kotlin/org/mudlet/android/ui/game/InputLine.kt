package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import org.mudlet.android.R
import org.mudlet.android.suggest.Suggestion
import org.mudlet.android.suggest.SuggestionSession

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

// TalkBack's order within the input line: the field, the suggestion strip above it, then the buttons beside it
private const val FIELD_ORDER = 0f
private const val STRIP_ORDER = 1f
private const val BUTTON_ORDER = 2f

/**
 * The line the player types commands in. The keyboard's Send key or the Send button hands the line to [onSend] and
 * leaves the keyboard open; an `on...` that returns false could not send its request, and the line then stays.
 * The up and down buttons, and a hardware keyboard's arrow keys, step through [history] (newest first). The completion
 * button, or a hardware Tab, hands the line to [onComplete], whose answer replaces it. A [commandLine] from a script
 * replaces the line, which then calls [onCommandLineShown].
 *
 * While [hiddenInput] holds, a masked password field takes the line's place and sends with [onSendHidden]; its close
 * button calls [onDismissHidden]. What is typed there is not saved across a rotation, to keep it out of saved state.
 *
 * Each change of the line or its cursor goes to [onInputChanged], whose [suggestions] show in a strip above the line;
 * a button there puts its suggestion on the line, without sending it. When the answer is for the line as it is and
 * its best suggestion only extends it, the rest shows greyed after the cursor (ghost text), and the accept button
 * at the start of the strip, or a hardware right arrow at the end of the line, puts it on the line. While input is
 * hidden there is neither strip nor ghost text.
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
    suggestions: SuggestionSession.Answer? = null,
    onInputChanged: (text: String, cursor: Int) -> Unit = { _, _ -> },
) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var secret by remember { mutableStateOf(TextFieldValue()) }
    var at by rememberSaveable(stateSaver = HistoryPosition.Saver) { mutableStateOf(HistoryPosition()) }
    var notice by rememberSaveable { mutableStateOf<Notice?>(null) }
    var focused by remember { mutableStateOf(false) }
    // Counts the sends, so that sending an empty line, which leaves the line as it was, still asks for suggestions
    var sends by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val hiddenFocus = remember { FocusRequester() }

    fun show(line: String, cursor: Int = line.length) {
        text = TextFieldValue(line, TextRange(cursor))
        notice = null
    }

    fun move(moved: HistoryPosition) {
        at = moved
        show(moved.shown(history))
    }

    fun submit() {
        val sent = onSend(text.text)
        notice = if (sent) null else Notice.NOT_SENT
        if (sent) {
            at = HistoryPosition()
            text = TextFieldValue()
            sends++
        }
    }

    fun submitHidden() {
        val sent = onSendHidden(secret.text)
        notice = if (sent) null else Notice.NOT_SENT
        if (sent) secret = TextFieldValue()
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

    val items = if (hiddenInput) emptyList() else suggestions?.suggestions?.items.orEmpty()
    // Only for the line as it is now: an answer for an older one would put the wrong text after the cursor
    val ghost = suggestions?.takeIf { !hiddenInput && it.input == text.text && text.selection.collapsed && it.cursor == text.selection.end }?.suggestions?.ghost

    fun accept() {
        if (ghost == null) return
        show(text.text + ghost)
        focus.requestFocus()
    }

    fun pick(suggestion: Suggestion) {
        show(suggestion.text, suggestion.cursor)
        focus.requestFocus()
    }

    val inputChanged by rememberUpdatedState(onInputChanged)
    LaunchedEffect(text.text, text.selection, hiddenInput, sends) {
        if (!hiddenInput) inputChanged(text.text, text.selection.end)
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
    val buttonOrder = Modifier.semantics { traversalIndex = BUTTON_ORDER }

    Column(modifier.semantics { isTraversalGroup = true }) {
        if (items.isNotEmpty()) {
            SuggestionStrip(
                items,
                accepted = ghost?.let { text.text + it },
                onAccept = ::accept,
                onPick = ::pick,
                Modifier.fillMaxWidth().semantics {
                    isTraversalGroup = true
                    traversalIndex = STRIP_ORDER
                },
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (hiddenInput) {
                OutlinedTextField(
                    value = secret,
                    onValueChange = {
                        secret = it
                        notice = null
                    },
                    modifier = Modifier.weight(1f).focusRequester(hiddenFocus).then(trackFocus).semantics { traversalIndex = FIELD_ORDER },
                    label = { Text(stringResource(R.string.input_password_label)) },
                    supportingText = supportingText,
                    isError = notice == Notice.NOT_SENT,
                    singleLine = true,
                    // Password semantics: TalkBack says "password", not what is typed
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitHidden() }),
                )
                IconButton({ if (!onDismissHidden()) notice = Notice.NOT_SENT }, buttonOrder) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.input_password_close))
                }
                IconButton({
                    submitHidden()
                    hiddenFocus.requestFocus()
                }, buttonOrder) {
                    Icon(painterResource(R.drawable.ic_send), stringResource(R.string.input_send))
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        notice = null
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus).then(trackFocus).semantics {
                        traversalIndex = FIELD_ORDER
                        // The typed text, not the ghost the transformation adds: what TalkBack reads and tests see
                        if (ghost != null) editableText = AnnotatedString(text.text)
                    }.onPreviewKeyEvent { event ->
                        val tab = event.key == Key.Tab && !event.isShiftPressed
                        // Both halves of Tab's press, so neither the field nor the focus acts on it
                        if (tab && event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent true
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // Right takes the ghost, which is only there with the cursor at the end, where it does nothing else
                        when {
                            tab -> complete()
                            event.key == Key.DirectionUp && at.canGoBack(history) -> move(at.back(history, text.text))
                            event.key == Key.DirectionDown && at.canGoForward -> move(at.forward())
                            event.key == Key.DirectionRight && ghost != null -> accept()
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
                    visualTransformation = ghost?.let { GhostTransformation(it, MaterialTheme.colorScheme.onSurfaceVariant) } ?: VisualTransformation.None,
                    singleLine = true,
                    // Game commands are not prose: corrections, suggestions and capitals would change them ("n" to "In")
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                IconButton({ move(at.back(history, text.text)) }, buttonOrder, enabled = at.canGoBack(history)) {
                    Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.input_history_previous))
                }
                IconButton({ move(at.forward()) }, buttonOrder, enabled = at.canGoForward) {
                    Icon(painterResource(R.drawable.ic_arrow_downward), stringResource(R.string.input_history_next))
                }
                IconButton({
                    submit()
                    focus.requestFocus()
                }, buttonOrder) {
                    Icon(painterResource(R.drawable.ic_send), stringResource(R.string.input_send))
                }
            }
        }
    }
}
