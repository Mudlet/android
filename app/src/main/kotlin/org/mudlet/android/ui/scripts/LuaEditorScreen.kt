package org.mudlet.android.ui.scripts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.protocol.LuaProblem
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.ScriptItemsClient

/** The Lua editor's text field, for tests to find. */
const val LUA_EDITOR_TAG = "luaEditor"

// How long the player has stopped typing for before the engine is asked whether the Lua compiles
internal const val CHECK_DELAY_MS = 400L

/** What the row over the keyboard types: what Lua uses most and a phone's keyboard hides deepest; the first indents. */
internal val SYMBOLS = listOf(LUA_INDENT, "(", ")", "\"", "[", "]", "{", "}", "=", "..", "end")

/**
 * The Lua of the item [key], full screen: its lines numbered, a new line indented as the one above it, Mudlet's
 * [functions] offered as a name is typed, a row of Lua's symbols over the keyboard, and what the engine finds wrong as
 * the player types pinned at the top, with a button that goes to its line. What is typed is the item's draft, as on
 * its page: Apply saves it, and [onBack] goes back to the page with the draft kept.
 */
@Composable
internal fun LuaEditorScreen(scripts: ScriptItemsClient, key: ScriptItemKey, functions: List<LuaFunction>, onBack: () -> Unit) {
    // Its page asked for it already, unless this screen was come back to after the app's process was started again
    LaunchedEffect(key, scripts.attaches) { if (scripts.saved[key] == null) scripts.open(key) }
    val item = scripts.drafts[key] ?: scripts.saved[key]
    val unnamed = stringResource(R.string.scripts_unnamed)
    MudletScaffold(
        item?.let { stringResource(R.string.lua_title, it.node.name.ifEmpty { unnamed }) } ?: stringResource(R.string.scripts_loading),
        onBack = onBack,
        snackbarHost = { ScriptNotices(scripts) {} },
        actions = { TextButton({ scripts.apply(key) }, enabled = key in scripts.drafts) { Text(stringResource(R.string.scripts_apply)) } },
    ) { padding ->
        val inside = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
        val refusal = scripts.unopened[key]
        when {
            item != null -> Editor(scripts, item, functions, inside)
            refusal != null -> Text(stringResource(R.string.scripts_refused_open, refusal), inside.padding(16.dp), color = MaterialTheme.colorScheme.error)
            else -> Text(stringResource(R.string.scripts_loading), inside.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun Editor(scripts: ScriptItemsClient, item: ScriptItem, functions: List<LuaFunction>, modifier: Modifier) {
    var typed by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(item.script)) }
    // What changes the item elsewhere, as an Undo does, shows here, with the cursor at its start
    val shown = if (typed.text == item.script) typed else TextFieldValue(item.script)
    val code = shown.text
    val focus = remember { FocusRequester() }

    fun change(to: TextFieldValue) {
        typed = to
        if (to.text != code) scripts.edit(item.copy(script = to.text))
    }

    fun change(to: LuaEdit) = change(TextFieldValue(to.text, TextRange(to.cursor)))

    // Asked again once the engine is back, as what was asked of the last link is not answered
    LaunchedEffect(code, scripts.attaches) {
        delay(CHECK_DELAY_MS)
        scripts.checkLua(code)
    }
    // What the engine last found in the code as it is here: kept while the next check is on its way, so that the code
    // under it does not jump at each key
    var problem by remember { mutableStateOf<LuaProblem?>(null) }
    val checked = scripts.luaCheck
    LaunchedEffect(checked) { if (checked != null && checked.code == code) problem = checked.problem }
    val prefix = if (shown.selection.collapsed) completionPrefix(code, shown.selection.start) else null
    val offered = remember(prefix, functions) { prefix?.let { completions(it, functions) }.orEmpty() }

    Column(modifier) {
        problem?.let { found ->
            Problem(found) {
                change(shown.copy(selection = TextRange(lineStart(code, found.line))))
                focus.requestFocus()
            }
        }
        Code(shown, Modifier.weight(1f).focusRequester(focus)) { new ->
            val indented = autoIndent(code, LuaEdit(new.text, new.selection.start))
            if (indented.text == new.text) change(new) else change(indented)
        }
        if (offered.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (function in offered) {
                    val signature = function.signature.ifEmpty { function.name }
                    val label = stringResource(R.string.lua_complete, signature)
                    SuggestionChip(
                        { change(complete(code, shown.selection.start, function)) },
                        label = { Text(signature, fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (symbol in SYMBOLS) {
                val indents = symbol == LUA_INDENT
                val label = if (indents) stringResource(R.string.lua_indent) else stringResource(R.string.lua_type, symbol)
                Box(
                    Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button) { change(insertAt(code, shown.selection.start, shown.selection.end, symbol)) }
                        .semantics { contentDescription = label }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (indents) stringResource(R.string.lua_indent_key) else symbol, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** What is wrong with the Lua, read out as it comes, and for a line Lua named, the button that goes there. */
@Composable
internal fun Problem(problem: LuaProblem, onGoTo: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (problem.line > 0) stringResource(R.string.lua_problem_at, problem.line, problem.message) else problem.message,
                Modifier.weight(1f).padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (problem.line > 0) TextButton(onGoTo) { Text(stringResource(R.string.lua_go_to_line)) }
        }
    }
}

/**
 * The code, each line numbered beside it and none wrapped, so the numbers stay with their lines: it scrolls both
 * ways. The field's label is its first line, where a screen reader finds it; the numbers are not read.
 */
@Composable
private fun Code(value: TextFieldValue, modifier: Modifier, onValue: (TextFieldValue) -> Unit) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(R.string.lua_label)
    Row(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp).clearAndSetSemantics {}, horizontalAlignment = Alignment.End) {
            // As tall as the label beside it, so that each number is beside its line
            Text("", style = MaterialTheme.typography.labelMedium)
            Text((1..lineCount(value.text)).joinToString("\n"), style = style, color = faint, textAlign = TextAlign.End)
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            // At least as wide as its place, so that a tap beside a short line reaches the field
            val width = maxWidth
            BasicTextField(
                value,
                onValue,
                Modifier.horizontalScroll(rememberScrollState()).testTag(LUA_EDITOR_TAG),
                textStyle = style,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { field ->
                    Column(Modifier.widthIn(min = width).heightIn(min = 240.dp).padding(end = 16.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = faint, maxLines = 1)
                        field()
                    }
                },
            )
        }
    }
}
