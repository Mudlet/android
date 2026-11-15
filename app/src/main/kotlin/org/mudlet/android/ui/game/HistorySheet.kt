package org.mudlet.android.ui.game

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.suggest.FuzzyMatch
import org.mudlet.android.suggest.PinnedCommands

/** The command history's test tag. */
const val HISTORY_SHEET_TAG = "historySheet"

/**
 * The command history, a sheet over the game screen: a search field, then the pinned commands and the history's
 * commands that match what is typed there, as [SuggestionState.search] ranks them. A row's command goes on the input
 * line with [onPick]; its buttons send it at once with [onSend], pin or unpin it, and forget it. Picking or sending
 * closes the sheet, as do its close button, Back and a tap outside it, each calling [onDismiss] once it has gone.
 *
 * The search field takes the focus as the sheet opens, so typing, and TalkBack, start there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(state: SuggestionState, onPick: (String) -> Unit, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var full by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(query) { state.search(query) }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }

    fun close() {
        scope.launch { sheet.hide() }.invokeOnCompletion { if (!sheet.isVisible) onDismiss() }
    }

    // What the row's buttons do; each acts at once, so nothing waits on the sheet's animation
    fun pick(command: String) {
        onPick(command)
        close()
    }

    fun send(command: String) {
        onSend(command)
        close()
    }

    fun pin(command: String) {
        full = !state.canPin
        if (!full) state.pin(command)
    }

    fun unpin(command: String) {
        full = false
        state.unpin(command)
    }

    fun forget(command: String) {
        state.forget(command)
        state.search(query)
    }

    val typed = query.trim()
    val pinned = state.pinned.filter { FuzzyMatch.score(typed, it) != null }
    val answer = state.searchAnswer
    val sent = answer?.commands.orEmpty().filterNot { command -> state.pinned.any { PinnedCommands.same(it, command) } }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(HISTORY_SHEET_TAG), sheetState = sheet) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.history_title),
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(::close) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.history_close))
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                full = false
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(searchFocus),
            label = { Text(stringResource(R.string.history_search)) },
            supportingText = if (full) {
                {
                    Text(
                        pluralStringResource(R.plurals.history_pins_full, state.maxPins, state.maxPins),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            } else {
                null
            },
            singleLine = true,
            // Commands, as on the input line: no corrections or capitals
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            if (pinned.isNotEmpty()) {
                item(key = "pinned") { Heading(R.string.history_pinned) }
                items(pinned, key = { "pinned:$it" }) { command ->
                    CommandRow(command, onPick = { pick(command) }) {
                        Action(R.drawable.ic_send, stringResource(R.string.history_send, command)) { send(command) }
                        Action(R.drawable.ic_push_pin, stringResource(R.string.history_unpin, command)) { unpin(command) }
                    }
                }
            }
            item(key = "sent") { Heading(R.string.history_sent) }
            if (sent.isEmpty() && answer?.query == query) {
                item(key = "none") {
                    Text(
                        stringResource(if (typed.isEmpty()) R.string.history_empty else R.string.history_no_match),
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            items(sent, key = { "sent:$it" }) { command ->
                CommandRow(command, onPick = { pick(command) }) {
                    Action(R.drawable.ic_send, stringResource(R.string.history_send, command)) { send(command) }
                    Action(R.drawable.ic_push_pin_outline, stringResource(R.string.history_pin, command)) { pin(command) }
                    Action(R.drawable.ic_delete, stringResource(R.string.history_forget, command)) { forget(command) }
                }
            }
        }
    }
}

@Composable
private fun Heading(@StringRes text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

/** A row of the history: [command], which a tap puts on the input line with [onPick], then its [actions]' buttons. */
@Composable
private fun CommandRow(command: String, onPick: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable(onClickLabel = stringResource(R.string.history_put_on_line), role = Role.Button, onClick = onPick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(command, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
private fun Action(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick) { Icon(painterResource(icon), label) }
}
