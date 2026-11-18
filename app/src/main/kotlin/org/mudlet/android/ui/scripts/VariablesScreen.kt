package org.mudlet.android.ui.scripts

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.LuaVariable
import org.mudlet.android.protocol.VARIABLE_VALUE_CHARS
import org.mudlet.android.protocol.VariableKey
import org.mudlet.android.protocol.VariablePreview
import org.mudlet.android.protocol.VariableRow
import org.mudlet.android.protocol.VariableType
import org.mudlet.android.protocol.opens
import org.mudlet.android.protocol.preview
import org.mudlet.android.protocol.rowCuts
import org.mudlet.android.protocol.shownName
import org.mudlet.android.protocol.variableRows
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.VariablesClient
import org.mudlet.android.ui.settings.MAX_WIDTH

/** The Variables screen's list, for tests to scroll. */
const val VARIABLES_TAG = "variables"

private val rowWidth = Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth()

// How far each table's members are set in from the table
private val INDENT = 24.dp

/**
 * The Lua variables of [game], as the desktop editor's Variables view lists them: the globals, each with its type and
 * value, a table opening to its members, which are read as it opens. Read only. A string's row opens to its whole value,
 * as much of it as the engine sends. A search finds names among what has been read.
 */
@Composable
internal fun VariablesScreen(variables: VariablesClient, game: String, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    // The strings shown whole, by path
    val whole = remember { mutableStateMapOf<List<VariableKey>, Unit>() }
    // Read each time the screen comes, as the values change while it is away, and after a reattach, which answers nothing asked before it
    LaunchedEffect(variables, variables.attaches) { variables.refresh() }
    val root = variables.levels[emptyList()]
    val rows = variableRows(variables.levels, variables.open, query)

    MudletScaffold(
        stringResource(R.string.variables_title, game),
        onBack = onBack,
        actions = { TextButton(variables::refresh) { Text(stringResource(R.string.variables_refresh)) } },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag(VARIABLES_TAG),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "search") { SearchField(query) { query = it } }
            item(key = "hidden") { HiddenSwitch(variables.showHidden, root?.hiddenLeftOut ?: 0, variables::showHidden) }
            variables.refusal?.let { item(key = "refusal") { Hint(stringResource(R.string.variables_refused, it), error = true) } }
            when {
                root == null -> if (variables.refusal == null) item(key = "loading") { Hint(stringResource(R.string.variables_loading)) }
                rows.isEmpty() && query.isNotBlank() -> item(key = "none") { Hint(stringResource(R.string.variables_search_none, query.trim())) }
                rows.isEmpty() -> item(key = "none") { Hint(stringResource(R.string.variables_none)) }
            }
            items(rows, key = ::rowKey) { row ->
                when (row) {
                    is VariableRow.Entry -> EntryRow(
                        row,
                        shownWhole = row.path in whole,
                        onToggle = {
                            if (row.variable.opens) {
                                variables.toggle(row.path)
                            } else if (whole.remove(row.path) == null) {
                                whole[row.path] = Unit
                            }
                        },
                    )

                    is VariableRow.Loading -> Hint(stringResource(R.string.variables_loading), Modifier.padding(start = INDENT * row.depth))

                    is VariableRow.More -> MoreRow(row) { variables.more(row.path, row.shown) }
                }
            }
        }
    }
}

private fun rowKey(row: VariableRow): String {
    val kind = when (row) {
        is VariableRow.Entry -> "e"
        is VariableRow.Loading -> "l"
        is VariableRow.More -> "m"
    }
    // Each name's length goes before it, as a Lua name can hold any character and two rows sharing a key crash the list
    return kind + row.path.joinToString("") { "${it.type.wire}:${it.name.length}:${it.name}" }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        query,
        onQuery,
        rowWidth.padding(vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.variables_search)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton({ onQuery("") }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.settings_search_clear)) }
            }
        },
        singleLine = true,
        shape = CircleShape,
    )
}

/** Desktop's "Show hidden variables", with how many the list leaves out while it is off. */
@Composable
private fun HiddenSwitch(shown: Boolean, leftOut: Int, onShown: (Boolean) -> Unit) {
    Row(
        rowWidth.heightIn(min = 48.dp).toggleable(shown, role = Role.Switch, onValueChange = onShown).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(stringResource(R.string.variables_show_hidden), style = MaterialTheme.typography.bodyLarge)
            if (!shown && leftOut > 0) {
                Text(
                    pluralStringResource(R.plurals.variables_hidden_count, leftOut, leftOut),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(shown, onCheckedChange = null)
    }
}

@get:StringRes
private val VariableType.title: Int
    get() = when (this) {
        VariableType.STRING -> R.string.variables_type_string
        VariableType.NUMBER -> R.string.variables_type_number
        VariableType.BOOLEAN -> R.string.variables_type_boolean
        VariableType.TABLE -> R.string.variables_type_table
        VariableType.FUNCTION -> R.string.variables_type_function
        VariableType.OTHER -> R.string.variables_type_other
    }

/** What the row writes after the type, as desktop's Variables view does, or null for a function or what else has no value. */
@Composable
private fun previewText(variable: LuaVariable): String? = when (val preview = variable.preview()) {
    is VariablePreview.Text -> stringResource(R.string.variables_quoted, preview.shown)
    is VariablePreview.Value -> preview.shown
    VariablePreview.EmptyTable -> "{ }"
    is VariablePreview.Items -> pluralStringResource(R.plurals.variables_items, preview.count, preview.count)
    is VariablePreview.Keys -> pluralStringResource(R.plurals.variables_keys, preview.count, preview.count)
    VariablePreview.Function, VariablePreview.Other -> null
}

/**
 * A variable's row: its name, marked when hidden, then its type and value. A table that opens shows a chevron and opens
 * or closes with a tap; a string whose row cannot show all of it opens to its whole value, [shownWhole], with a note
 * when the engine cut it. TalkBack reads it as one: name, type, value, or for a table how many entries it has.
 */
@Composable
private fun EntryRow(row: VariableRow.Entry, shownWhole: Boolean, onToggle: () -> Unit) {
    val variable = row.variable
    val colors = MaterialTheme.colorScheme
    val type = stringResource(variable.type.title)
    val preview = previewText(variable)
    val toggles = variable.opens || variable.rowCuts
    val expanded = if (variable.opens) row.open else shownWhole
    val hidden = stringResource(R.string.variables_hidden)
    val named = if (variable.hidden) stringResource(R.string.variables_name_hidden, variable.shownName, hidden) else variable.shownName
    val reading = when {
        variable.type == VariableType.TABLE ->
            pluralStringResource(R.plurals.variables_reading_table, variable.members, named, variable.members)

        preview == null -> stringResource(R.string.variables_reading_bare, named, type)

        variable.type == VariableType.STRING && expanded -> stringResource(R.string.variables_reading, named, type, variable.value)

        else -> stringResource(R.string.variables_reading, named, type, preview)
    }
    val state = stringResource(if (expanded) R.string.variables_expanded else R.string.variables_collapsed)
    val action = stringResource(
        when {
            variable.opens && expanded -> R.string.variables_close
            variable.opens -> R.string.variables_open
            expanded -> R.string.variables_show_less
            else -> R.string.variables_show_whole
        },
    )
    Column(
        rowWidth.heightIn(min = 48.dp)
            .then(
                if (toggles) {
                    Modifier.clickable(onClickLabel = action, role = Role.Button, onClick = onToggle).semantics { stateDescription = state }
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .padding(start = 8.dp + INDENT * row.depth, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        // Read as the one reading below, not as the texts drawn
        Row(Modifier.clearAndSetSemantics { contentDescription = reading }, verticalAlignment = Alignment.CenterVertically) {
            if (variable.opens) {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, Modifier.size(24.dp).rotate(if (expanded) 90f else 0f))
            } else {
                Spacer(Modifier.size(24.dp))
            }
            Column(Modifier.padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(variable.shownName, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                    if (variable.hidden) {
                        Text(hidden, Modifier.padding(start = 8.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    if (preview == null) type else stringResource(R.string.variables_type_value, type, preview),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (variable.rowCuts && expanded) {
                    Text(variable.value, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    if (variable.cut) {
                        Text(
                            pluralStringResource(R.plurals.variables_cut, VARIABLE_VALUE_CHARS, VARIABLE_VALUE_CHARS),
                            Modifier.padding(top = 4.dp),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

/** The members of a long table not read yet: a tap reads the next page. */
@Composable
private fun MoreRow(row: VariableRow.More, onMore: () -> Unit) {
    val left = row.total - row.shown
    TextButton(onMore, rowWidth.padding(start = INDENT * row.depth)) {
        Text(pluralStringResource(R.plurals.variables_more, left, left), Modifier.fillMaxWidth())
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        rowWidth.then(modifier).padding(16.dp),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}
