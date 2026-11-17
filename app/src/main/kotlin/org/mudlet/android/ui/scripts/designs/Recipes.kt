package org.mudlet.android.ui.scripts.designs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.protocol.TriggerPattern
import org.mudlet.android.protocol.TriggerPatternType
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.ScriptItemsClient
import org.mudlet.android.ui.SectionHeader
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.scripts.AliasCommand
import org.mudlet.android.ui.scripts.AliasPattern
import org.mudlet.android.ui.scripts.EventHandlers
import org.mudlet.android.ui.scripts.Hint
import org.mudlet.android.ui.scripts.LUA_EDITOR_TAG
import org.mudlet.android.ui.scripts.LuaFunction
import org.mudlet.android.ui.scripts.Problem
import org.mudlet.android.ui.scripts.SCRIPT_ITEM_TAG
import org.mudlet.android.ui.scripts.SCRIPT_LIBRARY_TAG
import org.mudlet.android.ui.scripts.ScriptItemKey
import org.mudlet.android.ui.scripts.ScriptNotices
import org.mudlet.android.ui.scripts.SearchField
import org.mudlet.android.ui.scripts.SwitchField
import org.mudlet.android.ui.scripts.TriggerCommand
import org.mudlet.android.ui.scripts.TriggerOptions
import org.mudlet.android.ui.scripts.TriggerPatterns
import org.mudlet.android.ui.scripts.childrenOf
import org.mudlet.android.ui.scripts.fieldWidth
import org.mudlet.android.ui.scripts.itemWidth
import org.mudlet.android.ui.scripts.lineCount
import org.mudlet.android.ui.scripts.newName
import org.mudlet.android.ui.scripts.pathTo
import org.mudlet.android.ui.scripts.searchScripts
import org.mudlet.android.ui.scripts.title

// The most patterns, and lines of Lua, a card shows before it says how many more there are
private const val CARD_PATTERNS = 2
private const val CARD_CODE_LINES = 4

// The parts of an item, each edited on a page of its own, in the order a new item is led through them
private const val OVERVIEW = 0
private const val WHEN = 1
private const val THEN = 2
private const val MORE = 3

/** The item just made, whose page opens on its first part and leads through the rest. */
internal object RecipesGuide {
    var start: ScriptItemKey? = null
}

/** The pieces of Lua the editor's keys type whole: what a player's first triggers and aliases are made of. */
private val SNIPPETS = listOf(
    LuaSnippet("send", "send(\"|\")"),
    LuaSnippet("echo", "echo(\"|\\n\")"),
    LuaSnippet("if", "if | then\n  \nend"),
    LuaSnippet("matches[2]", "matches[2]"),
    LuaSnippet("cecho", "cecho(\"<green>|\\n\")"),
    LuaSnippet("tempTimer", "tempTimer(1, function()\n  |\nend)"),
    LuaSnippet("for", "for i = 1, | do\n  \nend"),
    LuaSnippet("function", "function |()\n  \nend"),
    LuaSnippet("local", "local | = "),
)

@Composable
private fun whenOf(pattern: TriggerPattern): String = when (pattern.type) {
    TriggerPatternType.SUBSTRING -> stringResource(R.string.design_c_when_substring, pattern.text)
    TriggerPatternType.START_OF_LINE -> stringResource(R.string.design_c_when_start, pattern.text)
    TriggerPatternType.EXACT -> stringResource(R.string.design_c_when_exact, pattern.text)
    TriggerPatternType.REGEX -> stringResource(R.string.design_c_when_regex, pattern.text)
    TriggerPatternType.LUA -> stringResource(R.string.design_c_when_lua, pattern.text)
    TriggerPatternType.SPACER -> stringResource(R.string.design_c_when_spacer, pattern.text)
    TriggerPatternType.COLOR -> stringResource(R.string.design_c_when_color)
    TriggerPatternType.PROMPT -> stringResource(R.string.design_c_when_prompt)
}

/** In words, when [item] fires: a trigger's first patterns, what an alias matches, a script's events. */
@Composable
internal fun whenLines(item: ScriptItem): List<String> = when (val details = item.details) {
    is ScriptItemDetails.Trigger -> {
        val patterns = details.patterns
        val more = patterns.size - CARD_PATTERNS
        when {
            patterns.isEmpty() -> listOf(stringResource(R.string.design_c_when_none))
            more > 0 -> patterns.take(CARD_PATTERNS).map { whenOf(it) } + pluralStringResource(R.plurals.design_c_when_more, more, more)
            else -> patterns.map { whenOf(it) }
        }
    }

    is ScriptItemDetails.Alias -> listOf(if (details.pattern.isEmpty()) stringResource(R.string.design_c_when_none) else stringResource(R.string.design_c_when_alias, details.pattern))

    is ScriptItemDetails.Script -> listOf(
        if (details.eventHandlers.isEmpty()) stringResource(R.string.design_c_when_script) else stringResource(R.string.design_c_when_events, details.eventHandlers.joinToString(", ")),
    )
}

/** In words, what [item] does once it fires: what it sends, and that it runs its Lua. */
@Composable
internal fun thenLines(item: ScriptItem): List<String> {
    val command = when (val details = item.details) {
        is ScriptItemDetails.Trigger -> details.command
        is ScriptItemDetails.Alias -> details.command
        is ScriptItemDetails.Script -> ""
    }
    val lines = if (item.script.isBlank()) 0 else lineCount(item.script.trim())
    return buildList {
        if (command.isNotEmpty()) add(stringResource(R.string.design_c_then_send, command))
        if (lines > 0) add(stringResource(R.string.design_c_then_lua, pluralStringResource(R.plurals.scripts_lua_lines, lines, lines)))
        if (isEmpty()) add(stringResource(R.string.design_c_then_none))
    }
}

/**
 * Design C's library of [game]: a drawer with the kinds and their folders, and the folder picked as cards, each saying
 * when its item fires and what it then does, with its switch. A new item opens on its first part, through [onOpen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipesLibrary(
    scripts: ScriptItemsClient,
    game: String,
    onBack: () -> Unit,
    onOpen: (ScriptItemKey) -> Unit,
    console: () -> List<UiLine>,
    colors: ConsoleColors,
) {
    var kindWire by rememberSaveable { mutableStateOf(ScriptItemKind.TRIGGER.wire) }
    val kind = ScriptItemKind.fromWire(kindWire) ?: ScriptItemKind.TRIGGER
    var openFolder by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val lists = visibleLists(scripts)
    val items = lists[kind]
    // A folder deleted, or gone with a new engine, leaves the library at the top level
    val folder = if (items != null && items.none { it.id == openFolder }) 0 else openFolder
    val path = items.orEmpty().pathTo(folder)
    val place = path.lastOrNull()?.let { nameOf(it) } ?: stringResource(kind.title)
    val newItem = stringResource(kind.newName)
    val newFolder = stringResource(R.string.scripts_new_folder)

    fun go(of: ScriptItemKind, id: Int) {
        kindWire = of.wire
        openFolder = id
        searching = false
        query = ""
        scope.launch { drawer.close() }
    }
    BackHandler(enabled = drawer.isOpen || searching || folder != 0) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            searching -> go(kind, folder)
            else -> openFolder = path.lastOrNull()?.parent ?: 0
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.scripts_title, game), Modifier.padding(16.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    for (k in ScriptItemKind.entries) {
                        val all = lists[k].orEmpty()
                        NavigationDrawerItem(
                            label = { Text(stringResource(k.title)) },
                            selected = k == kind && folder == 0,
                            onClick = { go(k, 0) },
                            icon = { Icon(painterResource(k.icon), contentDescription = null) },
                            badge = { if (lists[k] != null) Text(all.count { !it.folder }.toString()) },
                        )
                        for (row in treeRows(all, all.filter { it.folder }.map { it.id }.toSet())) {
                            if (!row.node.folder) continue
                            NavigationDrawerItem(
                                label = { Text(nameOf(row.node), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                selected = k == kind && folder == row.node.id,
                                onClick = { go(k, row.node.id) },
                                modifier = Modifier.padding(start = (16 * minOf(row.depth + 1, 4)).dp),
                                icon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                                badge = { Text(row.children.toString()) },
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.scripts_back_to_game)) },
                        selected = false,
                        onClick = onBack,
                        icon = { Icon(painterResource(R.drawable.ic_videogame_asset), contentDescription = null) },
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(place, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton({ scope.launch { drawer.open() } }) { Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.design_c_menu)) } },
                    actions = {
                        IconButton({
                            searching = !searching
                            query = ""
                        }) { Icon(painterResource(if (searching) R.drawable.ic_close else R.drawable.ic_search), stringResource(if (searching) R.string.design_search_close else R.string.design_search)) }
                        if (folder != 0) {
                            IconButton({ onOpen(ScriptItemKey(kind, folder)) }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.scripts_edit_folder)) }
                        }
                        // A new item goes after those listed, so not before they are
                        if (items != null) {
                            IconButton({ scripts.add(kind, folder, folder = true, newFolder) }) { Icon(painterResource(R.drawable.ic_create_new_folder), stringResource(R.string.scripts_add_folder)) }
                        }
                    },
                )
            },
            snackbarHost = {
                ScriptNotices(scripts) { added ->
                    if (added.folder) {
                        go(added.key.kind, added.key.id)
                    } else {
                        RecipesGuide.start = added.key
                        onOpen(added.key)
                    }
                }
            },
            floatingActionButton = {
                if (items != null && !searching) {
                    ExtendedFloatingActionButton(
                        text = { Text(newItem) },
                        // The button's name for a screen reader: an extended button's text is not read
                        icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = newItem) },
                        onClick = { scripts.add(kind, folder, folder = false, newItem) },
                    )
                }
            },
            bottomBar = { GameStrip(console, colors, Modifier.navigationBarsPadding(), onGame = onBack) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag(SCRIPT_LIBRARY_TAG),
                // Room at the foot for the floating button over the last card
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (searching) {
                    item(key = "search") { SearchField(query) { query = it } }
                    val results = if (query.isBlank()) emptyList() else searchScripts(query, lists.mapValues { it.value.orEmpty() })
                    if (query.isNotBlank() && results.isEmpty()) item(key = "none") { Hint(stringResource(R.string.scripts_search_none, query.trim())) }
                    items(results, key = { "${it.kind.wire}/${it.item.id}" }) { result ->
                        val where = (listOf(stringResource(result.kind.title)) + result.folders.map { it.name }).joinToString(stringResource(R.string.scripts_path_separator))
                        Card(scripts, result.kind, result.item, lists[result.kind].orEmpty().count { it.parent == result.item.id }, where) {
                            if (result.item.folder) go(result.kind, result.item.id) else onOpen(ScriptItemKey(result.kind, result.item.id))
                        }
                    }
                } else if (items == null) {
                    item(key = "loading") { Hint(stringResource(R.string.scripts_loading)) }
                } else {
                    if (path.isNotEmpty()) {
                        item(key = "where") {
                            val where = (listOf(stringResource(kind.title)) + path.map { it.name }).joinToString(stringResource(R.string.scripts_path_separator))
                            Text(where, itemWidth.padding(horizontal = 4.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    val shown = items.childrenOf(folder)
                    if (shown.isEmpty()) item(key = "empty") { Hint(stringResource(if (folder == 0) R.string.scripts_none else R.string.scripts_folder_empty)) }
                    items(shown, key = { "${kind.wire}/${it.id}" }) { node ->
                        Card(scripts, kind, node, items.count { it.parent == node.id }, where = null) { if (node.folder) openFolder = node.id else onOpen(ScriptItemKey(kind, node.id)) }
                    }
                }
            }
        }
    }
}

/**
 * An item as a card: its name, why it does not run if it does not, then when it fires and what it does then, in words,
 * once the engine has said; a folder says how many items it has. Its switch is beside them.
 */
@Composable
private fun Card(scripts: ScriptItemsClient, kind: ScriptItemKind, node: ScriptItemNode, children: Int, where: String?, onClick: () -> Unit) {
    val key = ScriptItemKey(kind, node.id)
    // What a card says comes with the item's fields; asked once, and again once the engine is back
    LaunchedEffect(key, scripts.attaches) { if (!node.folder && scripts.saved[key] == null) scripts.open(key) }
    val item = scripts.drafts[key] ?: scripts.saved[key]
    val name = nameOf(node)
    val colors = MaterialTheme.colorScheme
    Surface(itemWidth.padding(vertical = 4.dp), shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(onClickLabel = stringResource(if (node.folder) R.string.scripts_open_folder else R.string.scripts_open_item), onClick = onClick)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node.folder) Icon(painterResource(R.drawable.ic_folder), contentDescription = null, Modifier.padding(end = 16.dp), tint = colors.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                if (where != null) Text(where, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
                    if (key in scripts.drafts) DraftBadge(Modifier.padding(start = 8.dp))
                }
                val status = statusOf(node, children)
                if (status != null) Text(status, style = MaterialTheme.typography.bodyMedium, color = if (node.error.isNotEmpty()) colors.error else colors.onSurfaceVariant, maxLines = 2)
                if (item != null && !node.folder) {
                    Part(stringResource(R.string.design_c_when), whenLines(item))
                    Part(stringResource(R.string.design_c_then), thenLines(item))
                }
            }
            Switch(node.enabled, { scripts.enable(key, it) }, Modifier.semantics { contentDescription = name })
        }
    }
}

/** A part of a card: its [label], "When" or "Then", before what it says. */
@Composable
private fun Part(label: String, lines: List<String>) {
    Row(Modifier.padding(top = 4.dp)) {
        Text(label, Modifier.width(52.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Column {
            for (line in lines) Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Design C's page of the item [key]. It opens on an overview to read: its switch, when it fires, what it does then,
 * and its name and options, each a card. A tap on one edits that part on a page of its own, from which Next goes to the
 * part after it; an item just made opens on the first. Save, on the overview, saves all that was changed; [onEditCode]
 * opens the Lua in the editor.
 */
@Composable
internal fun RecipesItem(scripts: ScriptItemsClient, key: ScriptItemKey, onBack: () -> Unit, onGame: () -> Unit, onEditCode: () -> Unit) {
    // Asked again each time, as a script may have changed it since, and once the engine is back; a draft stays over what comes
    LaunchedEffect(key, scripts.attaches) { scripts.open(key) }
    val saved = scripts.saved[key]
    val draft = scripts.drafts[key]
    val item = draft ?: saved
    val dirty = draft != null
    var leaving by remember { mutableStateOf(false) }
    var step by rememberSaveable {
        val guided = RecipesGuide.start == key
        if (guided) RecipesGuide.start = null
        mutableIntStateOf(if (guided) WHEN else OVERVIEW)
    }
    BackHandler(enabled = step != OVERVIEW || dirty) { if (step != OVERVIEW) step = OVERVIEW else leaving = true }
    val name = item?.node?.let { nameOf(it) }
    val title = when (step) {
        WHEN -> stringResource(R.string.design_c_when)
        THEN -> stringResource(R.string.design_c_then)
        MORE -> stringResource(R.string.design_c_more)
        else -> name ?: stringResource(R.string.scripts_loading)
    }

    MudletScaffold(
        title,
        onBack = {
            when {
                step != OVERVIEW -> step = OVERVIEW
                dirty -> leaving = true
                else -> onBack()
            }
        },
        snackbarHost = { ScriptNotices(scripts) {} },
        actions = {
            if (item != null && step == OVERVIEW) {
                IconButton({
                    scripts.deleteLater(key, name.orEmpty())
                    onBack()
                }) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.scripts_delete, name.orEmpty())) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(SCRIPT_ITEM_TAG),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val refusal = scripts.unopened[key]
                when {
                    item == null && refusal != null -> Text(stringResource(R.string.scripts_refused_open, refusal), fieldWidth.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    item == null -> Text(stringResource(R.string.scripts_loading), fieldWidth.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    step == OVERVIEW -> Overview(scripts, item, saved?.node?.error.orEmpty(), saved?.node?.enabled ?: item.node.enabled) { step = it }
                    else -> Step(step, scripts, item, onEditCode)
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Row(fieldWidth, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (step == OVERVIEW) {
                            val toGame = stringResource(R.string.scripts_back_to_game)
                            OutlinedButton(onGame, Modifier.semantics { contentDescription = toGame }) {
                                Icon(painterResource(R.drawable.ic_videogame_asset), contentDescription = null)
                                Text(stringResource(R.string.game_title), Modifier.padding(start = 8.dp))
                            }
                            Button({ scripts.apply(key) }, Modifier.weight(1f), enabled = dirty) { Text(stringResource(R.string.design_save)) }
                        } else {
                            OutlinedButton({ step = OVERVIEW }) { Text(stringResource(R.string.design_c_overview)) }
                            Button({ step = if (step == MORE) OVERVIEW else step + 1 }, Modifier.weight(1f)) {
                                Text(
                                    when (step) {
                                        WHEN -> stringResource(R.string.design_c_next, stringResource(R.string.design_c_then))
                                        THEN -> stringResource(R.string.design_c_next, stringResource(R.string.design_c_more))
                                        else -> stringResource(R.string.design_c_finish)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (leaving) {
        DiscardDialog(
            name.orEmpty(),
            onDiscard = {
                leaving = false
                scripts.discard(key)
                onBack()
            },
            onKeep = { leaving = false },
        )
    }
}

@Composable
private fun ColumnScope.Overview(scripts: ScriptItemsClient, item: ScriptItem, error: String, enabled: Boolean, onStep: (Int) -> Unit) {
    NotRunning(error, fieldWidth.padding(top = 8.dp))
    SwitchField(R.string.design_on, R.string.design_on_hint, enabled) { scripts.enable(ScriptItemKey(item.kind, item.node.id), it) }
    PartCard(stringResource(R.string.design_c_when), whenLines(item), code = "") { onStep(WHEN) }
    PartCard(stringResource(R.string.design_c_then), thenLines(item), code = item.script.trim()) { onStep(THEN) }
    val details = item.details
    val options = if (details is ScriptItemDetails.Trigger) {
        listOfNotNull(
            stringResource(if (details.lineDelta >= 0) R.string.scripts_match_all else R.string.scripts_match_any),
            if (details.matchAll) stringResource(R.string.scripts_every_occurrence) else null,
            if (details.filter) stringResource(R.string.scripts_filter) else null,
            if (details.highlight) stringResource(R.string.scripts_highlight) else null,
            if (details.sound) stringResource(R.string.scripts_sound) else null,
        )
    } else {
        emptyList()
    }
    PartCard(stringResource(R.string.design_c_more), listOf(nameOf(item.node)) + options, code = "") { onStep(MORE) }
}

/** A part of the item on its overview: its [title], what it says in [lines], and the first of its [code] if it has any. A tap edits it. */
@Composable
private fun PartCard(title: String, lines: List<String>, code: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(fieldWidth.padding(top = 12.dp), shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = stringResource(R.string.design_c_change), role = Role.Button, onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, Modifier.semantics { heading() }, color = colors.primary, style = MaterialTheme.typography.titleSmall)
                for (line in lines) Text(line, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge)
                if (code.isNotEmpty()) CodePreview(code, Modifier.padding(top = 8.dp))
            }
            Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

/** The first lines of [code], as the editor shows them. */
@Composable
private fun CodePreview(code: String, modifier: Modifier = Modifier) {
    val lines = code.lines()
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Text(
            lines.take(CARD_CODE_LINES).joinToString("\n") + if (lines.size > CARD_CODE_LINES) "\n…" else "",
            Modifier.padding(8.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            maxLines = CARD_CODE_LINES + 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One part of the item to edit, with a line that says what the part is for. */
@Composable
private fun ColumnScope.Step(step: Int, scripts: ScriptItemsClient, item: ScriptItem, onEditCode: () -> Unit) {
    val details = item.details
    Text(stringResource(R.string.design_c_step, step, MORE), fieldWidth.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    val explains = when (step) {
        WHEN -> when (details) {
            is ScriptItemDetails.Trigger -> R.string.design_c_when_trigger_hint
            is ScriptItemDetails.Alias -> R.string.design_c_when_alias_hint
            is ScriptItemDetails.Script -> R.string.design_c_when_script_hint
        }

        THEN -> R.string.design_c_then_hint

        else -> R.string.design_c_more_hint
    }
    Text(stringResource(explains), fieldWidth.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge)
    when (step) {
        WHEN -> when (details) {
            is ScriptItemDetails.Trigger -> TriggerPatterns(details) { scripts.edit(item.copy(details = it)) }
            is ScriptItemDetails.Alias -> AliasPattern(details) { scripts.edit(item.copy(details = it)) }
            is ScriptItemDetails.Script -> EventHandlers(details) { scripts.edit(item.copy(details = it)) }
        }

        THEN -> {
            when (details) {
                is ScriptItemDetails.Trigger -> TriggerCommand(details) { scripts.edit(item.copy(details = it)) }
                is ScriptItemDetails.Alias -> AliasCommand(details) { scripts.edit(item.copy(details = it)) }
                is ScriptItemDetails.Script -> Unit
            }
            SectionHeader(stringResource(R.string.scripts_code), fieldWidth)
            if (item.script.isBlank()) {
                Text(stringResource(R.string.scripts_lua_empty), fieldWidth.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CodePreview(item.script.trim(), fieldWidth)
            }
            OutlinedButton(onEditCode, fieldWidth.padding(top = 8.dp, bottom = 16.dp)) {
                Icon(painterResource(R.drawable.ic_code), contentDescription = null)
                Text(stringResource(R.string.design_c_edit_code), Modifier.padding(start = 8.dp))
            }
        }

        else -> {
            NameField(item, Modifier.padding(top = 8.dp), onChange = scripts::edit)
            if (details is ScriptItemDetails.Trigger) TriggerOptions(details) { scripts.edit(item.copy(details = it)) }
        }
    }
}

/**
 * Design C's Lua editor of the item [key]: its lines wrapped to the screen, so nothing is off to the side, with where
 * the cursor is under them; over the keyboard, keys that type whole pieces of Lua before those that type its symbols.
 * What is typed is the item's draft, which its overview's Save saves: Done and [onBack] go back to its page.
 */
@Composable
internal fun RecipesCode(scripts: ScriptItemsClient, key: ScriptItemKey, functions: List<LuaFunction>, onBack: () -> Unit) {
    // Its page asked for it already, unless this screen was come back to after the app's process was started again
    LaunchedEffect(key, scripts.attaches) { if (scripts.saved[key] == null) scripts.open(key) }
    val item = scripts.drafts[key] ?: scripts.saved[key]
    MudletScaffold(
        item?.let { stringResource(R.string.lua_title, nameOf(it.node)) } ?: stringResource(R.string.scripts_loading),
        onBack = onBack,
        snackbarHost = { ScriptNotices(scripts) {} },
        actions = { TextButton(onBack) { Text(stringResource(R.string.design_c_finish)) } },
    ) { padding ->
        val inside = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
        val refusal = scripts.unopened[key]
        when {
            item != null -> WrappedEditor(scripts, item, functions, inside)
            refusal != null -> Text(stringResource(R.string.scripts_refused_open, refusal), inside.padding(16.dp), color = MaterialTheme.colorScheme.error)
            else -> Text(stringResource(R.string.scripts_loading), inside.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WrappedEditor(scripts: ScriptItemsClient, item: ScriptItem, functions: List<LuaFunction>, modifier: Modifier) {
    val session = rememberLuaSession(scripts, item, functions)
    val colors = MaterialTheme.colorScheme
    val value = session.value
    val label = stringResource(R.string.lua_label)
    Column(modifier) {
        session.problem?.let { found -> Problem(found) { session.goTo(found.line) } }
        BasicTextField(
            value,
            session::type,
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(session.focus).testTag(LUA_EDITOR_TAG),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, color = colors.onSurface),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            cursorBrush = SolidColor(colors.primary),
            decorationBox = { field ->
                Column(Modifier.fillMaxWidth().heightIn(min = 240.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                    field()
                }
            },
        )
        val line = 1 + value.text.take(value.selection.start).count { it == '\n' }
        Text(
            stringResource(R.string.design_c_line, line, lineCount(value.text)),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        LuaCompletions(session)
        HorizontalDivider()
        LuaKeys(session, SNIPPETS)
    }
}
