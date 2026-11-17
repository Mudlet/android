package org.mudlet.android.ui.scripts.designs

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.protocol.LuaProblem
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.ScriptItemsClient
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.ansiText
import org.mudlet.android.ui.scripts.CHECK_DELAY_MS
import org.mudlet.android.ui.scripts.LUA_INDENT
import org.mudlet.android.ui.scripts.LuaEdit
import org.mudlet.android.ui.scripts.LuaFunction
import org.mudlet.android.ui.scripts.SYMBOLS
import org.mudlet.android.ui.scripts.ScriptItemKey
import org.mudlet.android.ui.scripts.autoIndent
import org.mudlet.android.ui.scripts.complete
import org.mudlet.android.ui.scripts.completionPrefix
import org.mudlet.android.ui.scripts.completions
import org.mudlet.android.ui.scripts.fieldWidth
import org.mudlet.android.ui.scripts.insertAt
import org.mudlet.android.ui.scripts.lineStart
import org.mudlet.android.ui.scripts.newName
import org.mudlet.android.ui.scripts.withoutHidden

/**
 * Which script editor the app was built with (`-PeditorDesign`): the one in [org.mudlet.android.ui.scripts], or one of
 * the three alternatives to it here, each an app of its own to try beside it.
 */
enum class EditorDesign {
    CURRENT,
    WORKBENCH,
    ONE_PAGE,
    RECIPES,
    ;

    companion object {
        /** The design `-PeditorDesign=`[letter] builds; the current one for none. */
        fun of(letter: String) = when (letter) {
            "a" -> WORKBENCH
            "b" -> ONE_PAGE
            "c" -> RECIPES
            else -> CURRENT
        }
    }
}

@get:DrawableRes
internal val ScriptItemKind.icon: Int
    get() = when (this) {
        ScriptItemKind.TRIGGER -> R.drawable.ic_bolt
        ScriptItemKind.ALIAS -> R.drawable.ic_keyboard
        ScriptItemKind.SCRIPT -> R.drawable.ic_code
    }

/** Each kind's items as the engine listed them, less those deleted with their Undo still offered; null until listed. */
@Composable
internal fun visibleLists(scripts: ScriptItemsClient): Map<ScriptItemKind, List<ScriptItemNode>?> {
    // Asked again once the engine is back, as what was asked of the last link is not answered
    LaunchedEffect(scripts.attaches) { ScriptItemKind.entries.forEach(scripts::ask) }
    val hidden = scripts.deleting
    return ScriptItemKind.entries.associateWith { k -> scripts.lists[k]?.withoutHidden(hidden.filter { it.kind == k }.map { it.id }.toSet()) }
}

/** What a row says under an item's name, as the current library's rows do; null for an item that runs. */
@Composable
internal fun statusOf(item: ScriptItemNode, children: Int): String? = when {
    item.error.isNotEmpty() -> stringResource(R.string.scripts_not_running, item.error)
    item.folder -> pluralStringResource(R.plurals.scripts_folder_items, children, children)
    !item.enabled -> stringResource(R.string.scripts_off)
    !item.active -> stringResource(R.string.scripts_idle)
    item.packageName.isNotEmpty() -> stringResource(R.string.scripts_from_package, item.packageName)
    else -> null
}

@Composable
internal fun nameOf(item: ScriptItemNode): String = item.name.ifEmpty { stringResource(R.string.scripts_unnamed) }

/** Marks an item whose changes are not saved yet. */
@Composable
internal fun DraftBadge(modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            stringResource(R.string.scripts_draft),
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** The last of the game's lines, which go back to the game; the caller keeps it clear of the navigation bar. */
@Composable
internal fun GameStrip(console: () -> List<UiLine>, colors: ConsoleColors, modifier: Modifier = Modifier, onGame: () -> Unit) {
    val lines = console().takeLast(2)
    if (lines.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().background(colors.background)
            .clickable(onClickLabel = stringResource(R.string.scripts_to_game), role = Role.Button, onClick = onGame)
            .then(modifier).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        for (line in lines) {
            Text(ansiText(line, colors), color = colors.foreground, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Asks whether to drop what was changed in the item named [name]. */
@Composable
internal fun DiscardDialog(name: String, onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        confirmButton = { TextButton(onDiscard) { Text(stringResource(R.string.scripts_discard)) } },
        dismissButton = { TextButton(onKeep) { Text(stringResource(R.string.scripts_keep_editing)) } },
        title = { Text(stringResource(R.string.scripts_discard_title, name)) },
        text = { Text(stringResource(R.string.scripts_discard_text)) },
    )
}

/** Why the item does not run, as the engine says it. */
@Composable
internal fun NotRunning(error: String, modifier: Modifier = Modifier) {
    if (error.isEmpty()) return
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
        Text(stringResource(R.string.scripts_not_running, error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
internal fun NameField(item: ScriptItem, modifier: Modifier = Modifier, onChange: (ScriptItem) -> Unit) {
    OutlinedTextField(
        item.node.name,
        { onChange(item.copy(node = item.node.copy(name = it))) },
        fieldWidth.then(modifier),
        label = { Text(stringResource(R.string.scripts_name)) },
        singleLine = true,
    )
}

/** The menu's entries that make a new item of [kind], or a folder, last in [parent]. */
@Composable
internal fun AddMenuItems(scripts: ScriptItemsClient, kind: ScriptItemKind, parent: Int, onPicked: () -> Unit) {
    val item = stringResource(kind.newName)
    val folder = stringResource(R.string.scripts_new_folder)
    DropdownMenuItem(
        { Text(item) },
        {
            onPicked()
            scripts.add(kind, parent, folder = false, item)
        },
        leadingIcon = { Icon(painterResource(kind.icon), contentDescription = null) },
    )
    DropdownMenuItem(
        { Text(folder) },
        {
            onPicked()
            scripts.add(kind, parent, folder = true, folder)
        },
        leadingIcon = { Icon(painterResource(R.drawable.ic_create_new_folder), contentDescription = null) },
    )
}

/**
 * The Lua of one item as it is typed: what the editor shows, what the engine last found wrong with it, and the
 * functions to offer at the cursor. Held apart from the field, so that the keys that type into it can be drawn
 * somewhere else on the screen.
 */
@Stable
internal class LuaSession(private val scripts: ScriptItemsClient) {
    var item by mutableStateOf<ScriptItem?>(null)
    var functions: List<LuaFunction> = emptyList()
    var problem by mutableStateOf<LuaProblem?>(null)
    var focused by mutableStateOf(false)
    val focus = FocusRequester()
    private var typed by mutableStateOf(TextFieldValue())

    // What changes the item elsewhere, as an Undo does, shows here, with the cursor at its start
    val value: TextFieldValue
        get() {
            val script = item?.script.orEmpty()
            return if (typed.text == script) typed else TextFieldValue(script)
        }
    val code: String get() = value.text

    val offered: List<LuaFunction>
        get() {
            val shown = value
            val prefix = if (shown.selection.collapsed) completionPrefix(shown.text, shown.selection.start) else null
            return prefix?.let { completions(it, functions) }.orEmpty()
        }

    private fun change(to: TextFieldValue) {
        val of = item ?: return
        val before = code
        typed = to
        if (to.text != before) scripts.edit(of.copy(script = to.text))
    }

    private fun change(to: LuaEdit) = change(TextFieldValue(to.text, TextRange(to.cursor)))

    /** What the field's keyboard made of the code: a new line is indented as the one above it. */
    fun type(new: TextFieldValue) {
        val indented = autoIndent(code, LuaEdit(new.text, new.selection.start))
        if (indented.text == new.text) change(new) else change(indented)
    }

    /** Types [text] over what is selected; a `|` in it is where the cursor goes, and its lines are indented as the cursor's is. */
    fun insert(text: String) {
        val shown = value
        val indent = shown.text.substring(lineStart(shown.text, 1 + shown.text.take(shown.selection.min).count { it == '\n' })).takeWhile { it == ' ' || it == '\t' }
        val lines = text.replace("\n", "\n" + indent)
        val mark = lines.indexOf('|')
        val edit = insertAt(shown.text, shown.selection.start, shown.selection.end, lines.replace("|", ""))
        change(if (mark < 0) edit else edit.copy(cursor = edit.cursor - (lines.length - 1 - mark)))
    }

    fun complete(function: LuaFunction) = change(complete(code, value.selection.start, function))

    fun goTo(line: Int) {
        change(value.copy(selection = TextRange(lineStart(code, line))))
        focus.requestFocus()
    }
}

/** The [LuaSession] of [item], which asks the engine whether its Lua compiles once the player has stopped typing. */
@Composable
internal fun rememberLuaSession(scripts: ScriptItemsClient, item: ScriptItem, functions: List<LuaFunction>): LuaSession {
    val key = ScriptItemKey(item.kind, item.node.id)
    val session = remember(scripts, key) { LuaSession(scripts) }
    session.item = item
    session.functions = functions
    val code = item.script
    LaunchedEffect(code, scripts.attaches) {
        delay(CHECK_DELAY_MS)
        scripts.checkLua(code)
    }
    val checked = scripts.luaCheck
    LaunchedEffect(checked) { if (checked != null && checked.code == code) session.problem = checked.problem }
    return session
}

/** The functions that complete the name at the cursor, each a chip that types it. */
@Composable
internal fun LuaCompletions(session: LuaSession) {
    val offered = session.offered
    if (offered.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (function in offered) {
            val signature = function.signature.ifEmpty { function.name }
            val label = stringResource(R.string.lua_complete, signature)
            SuggestionChip({ session.complete(function) }, label = { Text(signature, fontFamily = FontFamily.Monospace) }, modifier = Modifier.semantics { contentDescription = label })
        }
    }
}

/** A piece of Lua a key types whole: the key says [label], and a `|` in [text] is where the cursor is left. */
internal data class LuaSnippet(val label: String, val text: String)

/** A row of keys that type Lua's symbols, after those that type [snippets]. */
@Composable
internal fun LuaKeys(session: LuaSession, snippets: List<LuaSnippet> = emptyList()) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        for (snippet in snippets) {
            LuaKey(snippet.label, stringResource(R.string.lua_type, snippet.label)) { session.insert(snippet.text) }
        }
        for (symbol in SYMBOLS) {
            val indents = symbol == LUA_INDENT
            LuaKey(
                if (indents) stringResource(R.string.lua_indent_key) else symbol,
                if (indents) stringResource(R.string.lua_indent) else stringResource(R.string.lua_type, symbol),
            ) { session.insert(symbol) }
        }
    }
}

@Composable
private fun LuaKey(text: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
    }
}
