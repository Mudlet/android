package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.mudlet.android.R
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

// Where the field and its completion button are, so that a tap on the button is left to the button
private class Placed {
    var field: LayoutCoordinates? = null
    var button: LayoutCoordinates? = null

    fun onButton(position: Offset): Boolean {
        val field = field ?: return false
        val button = button ?: return false
        return field.isAttached && button.isAttached && field.localBoundingBoxOf(button).contains(position)
    }
}

/**
 * The line the player types commands in. The keyboard's Send key or the Send button hands the line to [onSend] and
 * leaves the keyboard open; an `on...` that returns false could not send its request, and the line then stays.
 * The up and down buttons, and a hardware keyboard's arrow keys, step through [history] (newest first). The completion
 * button, or a hardware Tab, hands the line to [onComplete], whose answer replaces it. A [commandLine] from a script
 * replaces the line, which then calls [onCommandLineShown]; a [picked] command (from the command history) replaces it
 * too and takes the focus, and then calls [onPickedShown]. [takeFocus] gives the line the focus, as after a `prompt:`
 * link in a label, and then calls [onFocusTaken].
 *
 * While [hiddenInput] holds, a masked password field takes the line's place and sends with [onSendHidden]; its close
 * button calls [onDismissHidden]. What is typed there is not saved across a rotation, to keep it out of saved state.
 *
 * Each change of the line or its cursor goes to [onInputChanged], which answers with [suggestions]. When the answer is
 * for the line as it is and its best suggestion only extends it, the rest shows greyed after the cursor (ghost text).
 * A tap on it while the line has the focus, a hardware right arrow at the end of the line, or TalkBack's action on
 * the line puts it on the line, without sending it; TalkBack reads it after the typed text. While input is hidden
 * there is no ghost text.
 *
 * A line that was sent is emptied, or with [clearAfterSend] off it stays, selected whole, as desktop Mudlet's does:
 * typing replaces it, Send sends it again and the up button goes to the command before it. While it stays so,
 * TalkBack is told its state in place of the selection ([keptCommand]).
 * With [highlightHistory], as desktop Mudlet's option of that name, a command the up and down buttons bring back is
 * selected whole too, so what is typed next replaces it.
 *
 * As the map comes to the front, [mapInFront], the keyboard goes down so the map has the room, but the line keeps the
 * focus: a hardware keyboard still types in it and a tap opens the keyboard again. As the map goes back, the line takes
 * the keyboard back if it had it. With [focusOnStart] (as another game comes to the front), the line takes the focus when
 * it first shows, unless the map is in front, which would open the keyboard over it.
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
    picked: String? = null,
    onPickedShown: () -> Unit = {},
    takeFocus: Boolean = false,
    onFocusTaken: () -> Unit = {},
    mapInFront: Boolean = false,
    focusOnStart: Boolean = false,
    clearAfterSend: Boolean = true,
    highlightHistory: Boolean = true,
) {
    // State-based fields: what the keyboard edits is this state itself, so a clear holds against an edit the
    // keyboard sends before the next frame, which a value and onValueChange field would apply to the old text
    val text = rememberTextFieldState()
    val secret = remember { TextFieldState() }
    var at by rememberSaveable(stateSaver = HistoryPosition.Saver) { mutableStateOf(HistoryPosition()) }
    var notice by rememberSaveable { mutableStateOf<Notice?>(null) }
    // The line as it was sent and kept
    var kept by rememberSaveable { mutableStateOf<String?>(null) }
    // The command brought back from the history and selected whole, with highlightHistory
    var recalled by rememberSaveable { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    // Counts the sends, so that sending an empty line, which leaves the line as it was, still asks for suggestions
    var sends by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val hiddenFocus = remember { FocusRequester() }
    // The player's own edits of the text or the cursor clear a notice, at once and so before any send they precede;
    // the line's other changes (a send, a recall, a completion) set the notice themselves
    val clearsNotice = remember { InputTransformation { notice = null } }

    fun show(line: String, cursor: Int = line.length) {
        text.edit {
            replace(0, length, line)
            selection = TextRange(cursor.coerceIn(0, line.length))
        }
        notice = null
        kept = null
        recalled = null
    }

    // Whether the line is [command] (the one kept or recalled), still selected whole: what is typed next replaces it
    fun selectedWhole(command: String?): Boolean {
        val typed = text.text
        return typed.toString() == command && text.selection == TextRange(0, typed.length)
    }

    fun move(moved: HistoryPosition) {
        at = moved
        show(moved.shown(history))
        if (moved.position < 0) return
        text.edit { selection = TextRange(0, length) }
        recalled = text.text.toString()
    }

    // A kept line is the history's newest already, so the way back from it starts at the command before
    fun back() {
        val typed = text.text.toString()
        val from = if (!at.canGoForward && typed == kept && typed == history.firstOrNull()) HistoryPosition(0) else at
        move(from.back(history, typed))
    }

    fun submit() {
        val line = text.text.toString()
        val sent = onSend(line)
        notice = if (sent) null else Notice.NOT_SENT
        if (!sent) return
        at = HistoryPosition()
        sends++
        if (clearAfterSend || line.isEmpty()) {
            text.clearText()
            kept = null
        } else {
            text.edit { selection = TextRange(0, length) }
            kept = line
        }
    }

    fun submitHidden() {
        val sent = onSendHidden(secret.text.toString())
        notice = if (sent) null else Notice.NOT_SENT
        if (sent) secret.clearText()
    }

    fun complete() {
        val asked = text.text.toString()
        val sent = onComplete(asked) { completed ->
            // The player typed on meanwhile: their line wins
            if (text.text.toString() != asked) return@onComplete
            if (completed.isEmpty()) notice = Notice.NO_COMPLETION else show(completed)
        }
        notice = if (sent) null else Notice.NOT_SENT
    }

    val line = text.text.toString()
    val cursor = text.selection
    // Only for the line as it is now: an answer for an older one would put the wrong text after the cursor
    val ghost = suggestions?.takeIf { !hiddenInput && it.input == line && cursor.collapsed && it.cursor == cursor.end }?.suggestions?.ghost

    fun accept() {
        if (ghost == null) return
        show(line + ghost)
        focus.requestFocus()
    }

    val placed = remember { Placed() }

    val inputChanged by rememberUpdatedState(onInputChanged)
    LaunchedEffect(hiddenInput) {
        if (hiddenInput) return@LaunchedEffect
        snapshotFlow { Triple(text.text.toString(), text.selection.end, sends) }.collect { (changed, end) -> inputChanged(changed, end) }
    }

    // Read as the field swaps, before the old one's focus is gone: the new one takes the focus it had
    val hadFocus = remember(hiddenInput) { focused }
    LaunchedEffect(hiddenInput) {
        if (!hiddenInput) secret.clearText()
        notice = null
        if (hadFocus) (if (hiddenInput) hiddenFocus else focus).requestFocus()
        // A field's selection goes with its focus, as the kept line's did when the masked field took its place
        if (!hiddenInput && text.text.toString() == kept) text.edit { selection = TextRange(0, length) }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    // Saved, as the map stays in front across a rotation
    var refocus by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(mapInFront) {
        if (mapInFront) {
            if (!focused) return@LaunchedEffect
            refocus = true
            keyboard?.hide()
        } else if (refocus) {
            refocus = false
            (if (hiddenInput) hiddenFocus else focus).requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(Unit) {
        if (focusOnStart && !mapInFront) (if (hiddenInput) hiddenFocus else focus).requestFocus()
    }
    LaunchedEffect(commandLine) {
        if (commandLine == null) return@LaunchedEffect
        at = HistoryPosition()
        show(commandLine)
        onCommandLineShown()
    }
    LaunchedEffect(picked) {
        if (picked == null) return@LaunchedEffect
        at = HistoryPosition()
        show(picked)
        // Only the command line has this requester: the masked field has a focus of its own
        if (!hiddenInput) focus.requestFocus()
        onPickedShown()
    }
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        if (!hiddenInput) focus.requestFocus()
        onFocusTaken()
    }

    val supportingText: (@Composable () -> Unit)? = notice?.let {
        {
            val message = if (it == Notice.NOT_SENT) R.string.input_not_sent else R.string.input_no_completion
            Text(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
    val trackFocus = Modifier.onFocusChanged { focused = it.isFocused }
    val accepted = ghost?.let { line + it }
    val acceptLabel = accepted?.let { stringResource(R.string.input_accept_suggestion, it) }
    val suggestionState = accepted?.let { stringResource(R.string.input_suggestion, it) }
    val keptState = stringResource(R.string.input_kept)
    val recalledState = stringResource(R.string.input_recalled)

    Row(modifier.semantics { isTraversalGroup = true }, verticalAlignment = Alignment.CenterVertically) {
        if (hiddenInput) {
            // Password semantics: TalkBack says "password", not what is typed
            OutlinedSecureTextField(
                state = secret,
                modifier = Modifier.weight(1f).focusRequester(hiddenFocus).then(trackFocus),
                label = { Text(stringResource(R.string.input_password_label)) },
                supportingText = supportingText,
                isError = notice == Notice.NOT_SENT,
                inputTransformation = clearsNotice,
                textObfuscationMode = TextObfuscationMode.Hidden,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Send),
                onKeyboardAction = { submitHidden() },
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
                state = text,
                modifier = Modifier.weight(1f).focusRequester(focus).then(trackFocus).keptCommand(selectedWhole(kept) || selectedWhole(recalled), line.length, if (selectedWhole(kept)) keptState else recalledState).semantics {
                    if (acceptLabel == null || suggestionState == null) return@semantics
                    // The typed text, not the ghost the transformation adds: what TalkBack reads and tests see
                    editableText = AnnotatedString(line)
                    stateDescription = suggestionState
                    customActions = listOf(
                        CustomAccessibilityAction(acceptLabel) {
                            accept()
                            true
                        },
                    )
                }.onPlaced { placed.field = it }.pointerInput(line, ghost) {
                    if (ghost == null) return@pointerInput
                    detectTapsAfterTheField(armed = { focused && !placed.onButton(it) }) {
                        if (text.text.toString() == line && text.selection == TextRange(line.length)) accept()
                    }
                }.onPreviewKeyEvent { event ->
                    val tab = event.key == Key.Tab && !event.isShiftPressed
                    // Both halves of Tab's press, so neither the field nor the focus acts on it
                    if (tab && event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent true
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Right takes the ghost, which is only there with the cursor at the end, where it does nothing else
                    when {
                        tab -> complete()
                        event.key == Key.DirectionUp && at.canGoBack(history) -> back()
                        event.key == Key.DirectionDown && at.canGoForward -> move(at.forward())
                        event.key == Key.DirectionRight && ghost != null -> accept()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
                label = { Text(stringResource(R.string.input_label)) },
                trailingIcon = {
                    IconButton({ complete() }, Modifier.onPlaced { placed.button = it }) {
                        Icon(painterResource(R.drawable.ic_keyboard_tab), stringResource(R.string.input_complete))
                    }
                },
                supportingText = supportingText,
                isError = notice == Notice.NOT_SENT,
                inputTransformation = clearsNotice,
                outputTransformation = ghost?.let { GhostTransformation(it, MaterialTheme.colorScheme.onSurfaceVariant) },
                // Game commands are not prose: corrections, suggestions and capitals would change them ("n" to "In")
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                onKeyboardAction = { submit() },
                lineLimits = TextFieldLineLimits.SingleLine,
            )
            IconButton(::back, enabled = at.canGoBack(history)) {
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

/**
 * What TalkBack is told of a command line whose sent or recalled command is [kept], selected whole: its [state], and a
 * cursor at the end of its [length]. TalkBack reads out a selection as it changes, so told of this one it would read the
 * command a second time, as if it had been typed anew.
 */
internal fun Modifier.keptCommand(kept: Boolean, length: Int, state: String): Modifier = if (!kept) {
    this
} else {
    semantics {
        textSelectionRange = TextRange(length)
        stateDescription = state
    }
}
