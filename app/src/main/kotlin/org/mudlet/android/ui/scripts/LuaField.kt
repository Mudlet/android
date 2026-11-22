package org.mudlet.android.ui.scripts

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.LuaProblem
import org.mudlet.android.ui.ScriptItemsClient

/** The Lua field of an item's page, for tests to find. */
const val LUA_EDITOR_TAG = "luaEditor"

/** What the row over the keyboard types: what Lua uses most and a phone's keyboard hides deepest; the first indents. */
private val SYMBOLS = listOf(LUA_INDENT, "(", ")", "\"", "[", "]", "{", "}", "=", "..", "end")

// How far from the edges of what shows of the page the cursor's line is kept
private val CURSOR_MARGIN = 12.dp

/**
 * An item's Lua as its page's field shows it, with the cursor in it, and what the player does to it there: a new line
 * is indented as the one above it, and [functions] are [offered] as a name is typed. The code itself is the item's,
 * read with [script] and changed with [onScript]; [typed] is what was last typed here, to start from.
 */
internal class LuaFieldState(
    private val functions: List<LuaFunction>,
    private val script: () -> String,
    private val onScript: (String) -> Unit,
    typed: TextFieldValue = TextFieldValue(script()),
) {
    private var typed by mutableStateOf(typed)

    /** Whether the field has the keyboard: what goes over the keyboard for it shows only then. */
    var focused by mutableStateOf(false)

    /** How many times [goTo] was asked, for the field to show the cursor's line again when the cursor was on it already. */
    var wentTo by mutableIntStateOf(0)
        private set

    /** The code with its cursor. What changes the item elsewhere, as an Undo does, shows with the cursor at its start. */
    val value: TextFieldValue get() = typed.takeIf { it.text == script() } ?: TextFieldValue(script())

    /** Mudlet's functions to offer for the name being typed at the cursor. */
    val offered: List<LuaFunction>
        get() {
            val shown = value
            val prefix = if (shown.selection.collapsed) completionPrefix(shown.text, shown.selection.start) else null
            return prefix?.let { completions(it, functions) }.orEmpty()
        }

    /** The field's text or cursor as the player has made it, [new]. */
    fun type(new: TextFieldValue) {
        val indented = autoIndent(value.text, LuaEdit(new.text, new.selection.start))
        if (indented.text == new.text) show(new) else show(indented)
    }

    /** Types [symbol] over what is selected. */
    fun insert(symbol: String) = value.let { show(insertAt(it.text, it.selection.start, it.selection.end, symbol)) }

    /** Types [snippet] over what is selected, as [insertSnippet] does. */
    fun insert(snippet: LuaSnippet) = value.let { show(insertSnippet(it.text, it.selection.start, it.selection.end, snippet.text)) }

    /** Completes the name being typed to [function], one of [offered]. */
    fun complete(function: LuaFunction) = value.let { show(complete(it.text, it.selection.start, function)) }

    /** Puts the cursor at the start of line [line], from 1. */
    fun goTo(line: Int) {
        value.let { show(it.copy(selection = TextRange(lineStart(it.text, line)))) }
        wentTo++
    }

    private fun show(edit: LuaEdit) = show(TextFieldValue(edit.text, TextRange(edit.cursor)))

    private fun show(to: TextFieldValue) {
        val changed = to.text != value.text
        typed = to
        if (changed) onScript(to.text)
    }

    companion object {
        /** Keeps what was typed, with its cursor, over a rotation; the rest is given again. */
        fun saver(functions: List<LuaFunction>, script: () -> String, onScript: (String) -> Unit): Saver<LuaFieldState, Any> = Saver(
            save = { state -> with(TextFieldValue.Saver) { save(state.typed) } },
            restore = { saved -> TextFieldValue.Saver.restore(saved)?.let { LuaFieldState(functions, script, onScript, it) } },
        )
    }
}

/** The state of the Lua field of the item [key]'s page: what is typed in it is the item's draft, as its other fields are. */
@Composable
internal fun rememberLuaFieldState(scripts: ScriptItemsClient, key: ScriptItemKey, functions: List<LuaFunction>): LuaFieldState {
    fun item() = scripts.drafts[key] ?: scripts.saved[key]
    val script = { item()?.script.orEmpty() }
    val onScript: (String) -> Unit = { code -> item()?.let { scripts.edit(it.copy(script = code)) } }
    return rememberSaveable(scripts, key, functions, saver = LuaFieldState.saver(functions, script, onScript)) { LuaFieldState(functions, script, onScript) }
}

/**
 * The item's Lua in its page: each line numbered beside it and none wrapped, so the numbers stay with their lines, or
 * where not [numbered], wrapped to the field with no numbers. It is as tall as its lines and scrolls with the page,
 * [viewport] the height of what shows of it: the cursor's line is kept in what shows of the page as the player types,
 * as the keyboard comes, and at each [LuaFieldState.goTo] once [focus] is asked for it. A long line scrolls sideways.
 * The field's name is its first line, where a screen reader finds it; the numbers are not read.
 */
@Composable
internal fun LuaField(state: LuaFieldState, viewport: Int, focus: FocusRequester, modifier: Modifier = Modifier, numbered: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = colors.onSurface)
    val label = stringResource(R.string.scripts_lua)
    val value = state.value
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // The layout is of the text a frame ago while a key is being typed
    val cursor = layout?.takeIf { value.selection.end <= it.layoutInput.text.length }?.getCursorRect(value.selection.end)
    val cursorInView = remember { BringIntoViewRequester() }
    val margin = with(LocalDensity.current) { CURSOR_MARGIN.toPx() }
    // Asked again as what shows of the page changes, as when the keyboard comes over it, but not as the player scrolls
    LaunchedEffect(cursor, viewport, state.focused, state.wentTo) {
        if (state.focused && cursor != null) cursorInView.bringIntoView(cursor.inflate(margin))
    }

    val outline = if (state.focused) colors.primary else colors.outline
    Row(modifier.border(if (state.focused) 2.dp else 1.dp, outline, MaterialTheme.shapes.extraSmall).padding(vertical = 8.dp)) {
        if (numbered) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp).clearAndSetSemantics {}, horizontalAlignment = Alignment.End) {
                // As tall as the name beside it, so that each number is beside its line
                Text("", style = MaterialTheme.typography.labelMedium)
                Text((1..lineCount(value.text)).joinToString("\n"), style = style, color = colors.onSurfaceVariant, textAlign = TextAlign.End)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).padding(start = if (numbered) 0.dp else 16.dp)) {
            // At least as wide as its place, so that a tap beside a short line reaches the field
            val width = maxWidth
            BasicTextField(
                value,
                state::type,
                Modifier.then(if (numbered) Modifier.horizontalScroll(rememberScrollState()) else Modifier).focusRequester(focus).onFocusChanged { state.focused = it.isFocused }.testTag(LUA_EDITOR_TAG),
                textStyle = style,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                onTextLayout = { layout = it },
                cursorBrush = SolidColor(colors.primary),
                decorationBox = { field ->
                    Column(Modifier.widthIn(min = width).heightIn(min = 160.dp).padding(end = 16.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                        Box(Modifier.bringIntoViewRequester(cursorInView)) { field() }
                    }
                },
            )
        }
    }
}

/** A piece of Lua a key over the keyboard types whole: the key says [label], and [text] is typed as [insertSnippet] has it. */
internal data class LuaSnippet(val label: String, val text: String)

/**
 * What goes over the keyboard while the Lua field has it: Mudlet's functions for the name being typed, and a row of
 * keys that type [snippets], then Lua's symbols.
 */
@Composable
internal fun LuaKeys(state: LuaFieldState, modifier: Modifier = Modifier, snippets: List<LuaSnippet> = emptyList()) {
    Column(modifier) {
        val offered = state.offered
        if (offered.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (function in offered) {
                    val signature = function.signature.ifEmpty { function.name }
                    val label = stringResource(R.string.lua_complete, signature)
                    SuggestionChip(
                        { state.complete(function) },
                        label = { Text(signature, fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (snippet in snippets) {
                LuaKey(snippet.label, stringResource(R.string.lua_type, snippet.label)) { state.insert(snippet) }
            }
            for (symbol in SYMBOLS) {
                val indents = symbol == LUA_INDENT
                val label = if (indents) stringResource(R.string.lua_indent) else stringResource(R.string.lua_type, symbol)
                LuaKey(if (indents) stringResource(R.string.lua_indent_key) else symbol, label) { state.insert(symbol) }
            }
        }
    }
}

@Composable
private fun LuaKey(text: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
    }
}

/** What is wrong with the Lua that was applied, read out as it comes, and for a line Lua named, the button that goes there. */
@Composable
internal fun LuaProblemLine(problem: LuaProblem, onGoTo: () -> Unit) {
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
