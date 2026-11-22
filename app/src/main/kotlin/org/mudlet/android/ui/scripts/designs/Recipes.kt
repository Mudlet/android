package org.mudlet.android.ui.scripts.designs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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
import org.mudlet.android.ui.LocalInDetailPane
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.ScriptItemsClient
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.scripts.Adds
import org.mudlet.android.ui.scripts.AliasCommand
import org.mudlet.android.ui.scripts.AliasPattern
import org.mudlet.android.ui.scripts.ButtonCss
import org.mudlet.android.ui.scripts.ButtonPushDown
import org.mudlet.android.ui.scripts.CommandField
import org.mudlet.android.ui.scripts.ConsoleStrip
import org.mudlet.android.ui.scripts.DraftBadge
import org.mudlet.android.ui.scripts.EventHandlers
import org.mudlet.android.ui.scripts.GameButton
import org.mudlet.android.ui.scripts.Hint
import org.mudlet.android.ui.scripts.ItemActions
import org.mudlet.android.ui.scripts.ItemDialogs
import org.mudlet.android.ui.scripts.ItemNotOpen
import org.mudlet.android.ui.scripts.KeyPick
import org.mudlet.android.ui.scripts.LuaField
import org.mudlet.android.ui.scripts.LuaFieldState
import org.mudlet.android.ui.scripts.LuaFunction
import org.mudlet.android.ui.scripts.LuaKeys
import org.mudlet.android.ui.scripts.LuaSnippet
import org.mudlet.android.ui.scripts.NameField
import org.mudlet.android.ui.scripts.NewItemButton
import org.mudlet.android.ui.scripts.NotRunningBanner
import org.mudlet.android.ui.scripts.SCRIPT_ITEM_TAG
import org.mudlet.android.ui.scripts.SCRIPT_LIBRARY_TAG
import org.mudlet.android.ui.scripts.ScriptHelpButton
import org.mudlet.android.ui.scripts.ScriptItemKey
import org.mudlet.android.ui.scripts.ScriptItemScreen
import org.mudlet.android.ui.scripts.ScriptNotices
import org.mudlet.android.ui.scripts.SearchAsTyped
import org.mudlet.android.ui.scripts.SearchOptions
import org.mudlet.android.ui.scripts.StatusText
import org.mudlet.android.ui.scripts.SwitchField
import org.mudlet.android.ui.scripts.TimerTime
import org.mudlet.android.ui.scripts.TriggerCommand
import org.mudlet.android.ui.scripts.TriggerOptions
import org.mudlet.android.ui.scripts.TriggerPatterns
import org.mudlet.android.ui.scripts.adds
import org.mudlet.android.ui.scripts.childrenOf
import org.mudlet.android.ui.scripts.fieldWidth
import org.mudlet.android.ui.scripts.holdsItems
import org.mudlet.android.ui.scripts.itemWidth
import org.mudlet.android.ui.scripts.keyCombination
import org.mudlet.android.ui.scripts.lineCount
import org.mudlet.android.ui.scripts.newName
import org.mudlet.android.ui.scripts.pathTo
import org.mudlet.android.ui.scripts.rememberItemPage
import org.mudlet.android.ui.scripts.rememberLuaFieldState
import org.mudlet.android.ui.scripts.rowStatus
import org.mudlet.android.ui.scripts.title
import org.mudlet.android.ui.scripts.visibleLists
import org.mudlet.android.ui.scripts.whereText

// The most patterns, and lines of Lua, a card shows before it says how many more there are
private const val CARD_PATTERNS = 2
private const val CARD_CODE_LINES = 4

// How far each level of folders is set in, in the drawer, and the deepest level that still is
private const val DRAWER_INDENT_DP = 16
private const val DRAWER_MAX_INDENT = 4

/** The parts of an item, each changed on a step of its own, in the order a new item is led through them. */
private enum class Step(val title: Int) {
    OVERVIEW(R.string.recipes_overview),
    WHEN(R.string.recipes_when),
    THEN(R.string.recipes_then),
    MORE(R.string.recipes_more),
}

/** The item just made, whose page opens on its first part and leads through the rest. */
private object RecipesGuide {
    private var key: ScriptItemKey? = null

    fun made(key: ScriptItemKey) {
        this.key = key
    }

    /** Whether [key]'s page is the one to lead through its parts; once only. */
    fun take(key: ScriptItemKey): Boolean = (this.key == key).also { if (it) this.key = null }
}

/** The pieces of Lua the keys over the keyboard type whole: what a player's first triggers and aliases are made of. */
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
    TriggerPatternType.SUBSTRING -> stringResource(R.string.recipes_when_substring, pattern.text)
    TriggerPatternType.START_OF_LINE -> stringResource(R.string.recipes_when_start, pattern.text)
    TriggerPatternType.EXACT -> stringResource(R.string.recipes_when_exact, pattern.text)
    TriggerPatternType.REGEX -> stringResource(R.string.recipes_when_regex, pattern.text)
    TriggerPatternType.LUA -> stringResource(R.string.recipes_when_lua, pattern.text)
    TriggerPatternType.SPACER -> stringResource(R.string.recipes_when_spacer, pattern.text)
    TriggerPatternType.COLOR -> stringResource(R.string.recipes_when_color)
    TriggerPatternType.PROMPT -> stringResource(R.string.recipes_when_prompt)
}

/**
 * In words, when [item] runs, from its fields and [node], as listed: a trigger's first patterns, what an alias matches,
 * a script's events, a timer's time, a key's keys, how a button is pressed.
 */
@Composable
private fun whenLines(item: ScriptItem, node: ScriptItemNode): List<String> = when (val details = item.details) {
    is ScriptItemDetails.Trigger -> {
        val patterns = details.patterns
        val more = patterns.size - CARD_PATTERNS
        when {
            patterns.isEmpty() -> listOf(stringResource(R.string.recipes_when_none))
            more > 0 -> patterns.take(CARD_PATTERNS).map { whenOf(it) } + pluralStringResource(R.plurals.recipes_when_more, more, more)
            else -> patterns.map { whenOf(it) }
        }
    }

    is ScriptItemDetails.Alias -> listOf(if (details.pattern.isEmpty()) stringResource(R.string.recipes_when_none) else stringResource(R.string.recipes_when_alias, details.pattern))

    is ScriptItemDetails.Script -> listOf(
        if (details.eventHandlers.isEmpty()) stringResource(R.string.recipes_when_script) else stringResource(R.string.recipes_when_events, details.eventHandlers.joinToString(", ")),
    )

    // The engine's summary of a timer is its time as the desktop editor writes it
    is ScriptItemDetails.Timer -> listOf(stringResource(if (details.offset) R.string.recipes_when_offset else R.string.recipes_when_timer, node.summary))

    is ScriptItemDetails.Key -> listOf(stringResource(R.string.recipes_when_key, keyCombination(details)))

    is ScriptItemDetails.Button -> listOf(stringResource(if (details.pushDown) R.string.recipes_when_push else R.string.recipes_when_click))
}

/** In words, what [item] does once it runs: what it sends, and that it runs its Lua. */
@Composable
private fun thenLines(item: ScriptItem): List<String> {
    val details = item.details
    val command = when (details) {
        is ScriptItemDetails.Trigger -> details.command
        is ScriptItemDetails.Alias -> details.command
        is ScriptItemDetails.Script -> ""
        is ScriptItemDetails.Timer -> details.command
        is ScriptItemDetails.Key -> details.command
        is ScriptItemDetails.Button -> details.commandDown
    }
    val lines = if (item.script.isBlank()) 0 else lineCount(item.script.trim())
    return buildList {
        if (command.isNotEmpty()) add(stringResource(R.string.recipes_then_send, command))
        if (details is ScriptItemDetails.Button && details.pushDown && details.commandUp.isNotEmpty()) add(stringResource(R.string.recipes_then_send_up, details.commandUp))
        if (lines > 0) add(stringResource(R.string.recipes_then_lua, pluralStringResource(R.plurals.recipes_lua_lines, lines, lines)))
        if (isEmpty()) add(stringResource(R.string.recipes_then_none))
    }
}

/**
 * The Recipes design's library of [game]: a drawer with the kinds and their folders, and what is in the folder picked
 * as cards, each saying in words when its item runs and what it then does, with its switch. A new item opens on its
 * first part, through [onOpen]. The parameters are [org.mudlet.android.ui.scripts.ScriptLibraryScreen]'s.
 */
@Composable
internal fun RecipesLibrary(
    scripts: ScriptItemsClient,
    game: String,
    onBack: () -> Unit,
    onOpen: (ScriptItemKey) -> Unit,
    selected: ScriptItemKey?,
    console: () -> List<UiLine>,
    colors: ConsoleColors,
    onVariables: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    var kindWire by rememberSaveable { mutableStateOf(ScriptItemKind.TRIGGER.wire) }
    val kind = ScriptItemKind.fromWire(kindWire) ?: ScriptItemKind.TRIGGER
    var openFolder by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    val options = SearchOptions(caseSensitive, wholeWord)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    SearchAsTyped(scripts, query, options)
    val lists = visibleLists(scripts)
    val items = lists[kind]
    // A folder deleted, or gone with a new engine, leaves the library at the top level
    val folder = if (items != null && items.none { it.id == openFolder }) 0 else openFolder
    val shownIn = items?.firstOrNull { it.id == folder }
    val path = items.orEmpty().pathTo(folder)
    val adds = adds(kind, shownIn)
    val newItem = stringResource(if (adds == Adds.TOOLBAR) R.string.scripts_new_toolbar else kind.newName)

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
            else -> openFolder = shownIn?.parent ?: 0
        }
    }

    // Closed, the sheet is still laid out, off the screen's edge, so what is in it is left out until it opens, which only
    // the menu button does; its width is set, as the drawer slides it by what it measures
    val sheetShown = drawer.isOpen || drawer.isAnimationRunning || drawer.targetValue == DrawerValue.Open
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(DrawerDefaults.MaximumDrawerWidth)) {
                if (sheetShown) {
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
                            for (row in treeRows(all, all.map { it.id }.toSet())) {
                                if (!row.node.folder) continue
                                NavigationDrawerItem(
                                    label = { Text(nameOf(row.node)) },
                                    selected = k == kind && folder == row.node.id,
                                    onClick = { go(k, row.node.id) },
                                    modifier = Modifier.padding(start = (DRAWER_INDENT_DP * minOf(row.depth + 1, DRAWER_MAX_INDENT)).dp),
                                    icon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        if (onVariables != null) {
                            NavigationDrawerItem(
                                label = { Text(stringResource(R.string.variables_chip)) },
                                selected = false,
                                onClick = onVariables,
                                icon = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                            )
                        }
                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.scripts_back_to_game)) },
                            selected = false,
                            onClick = onBack,
                            icon = { Icon(painterResource(R.drawable.ic_videogame_asset), contentDescription = null) },
                        )
                    }
                }
            }
        },
    ) {
        MudletScaffold(
            stringResource(R.string.scripts_title, game),
            onBack = onBack,
            actions = {
                actions()
                IconButton({ scope.launch { drawer.open() } }) { Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.recipes_menu)) }
                SearchButton(searching) {
                    searching = !searching
                    query = ""
                }
                ScriptHelpButton(kind)
            },
            snackbarHost = {
                ScriptNotices(scripts) { added ->
                    if (added.folder) {
                        go(added.key.kind, added.key.id)
                    } else {
                        RecipesGuide.made(added.key)
                        onOpen(added.key)
                    }
                }
            },
            floatingActionButton = { if (items != null && !searching) NewItemButton(scripts, kind, folder, adds, newItem) },
            bottomBar = { ConsoleStrip(console, colors, onGame = onBack) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag(SCRIPT_LIBRARY_TAG),
                // Room at the foot for the floating button over the last card
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (searching) {
                    item(key = "search") {
                        SearchHeader(query, { query = it }, options, itemWidth) {
                            caseSensitive = it.caseSensitive
                            wholeWord = it.wholeWord
                        }
                    }
                    if (query.isNotBlank()) {
                        searchResultItems(query, lists, options, scripts) { result, key, children ->
                            Card(scripts, key, result.item, children, whereText(result.kind, result.folders), key == selected) {
                                if (result.item.holdsItems(children)) go(result.kind, result.item.id) else onOpen(key)
                            }
                        }
                    }
                } else if (items == null) {
                    item(key = "loading") { Hint(stringResource(R.string.scripts_loading)) }
                } else {
                    item(key = "where") {
                        Where(
                            whereText(kind, path),
                            edit = shownIn?.let { if (it.folder) stringResource(R.string.scripts_edit_folder) else stringResource(R.string.scripts_edit_item, nameOf(it)) },
                            onEdit = { onOpen(ScriptItemKey(kind, folder)) },
                            onAddFolder = if (adds == Adds.ITEM_AND_FOLDER) ({ scripts.add(kind, folder, folder = true, it) }) else null,
                        )
                    }
                    val shown = items.childrenOf(folder)
                    if (shown.isEmpty()) item(key = "empty") { Hint(stringResource(if (folder == 0) R.string.scripts_none else R.string.scripts_folder_empty)) }
                    items(shown, key = { "${kind.wire}/${it.id}" }) { node ->
                        val key = ScriptItemKey(kind, node.id)
                        val children = items.count { it.parent == node.id }
                        Card(scripts, key, node, children, where = null, key == selected) { if (node.holdsItems(children)) openFolder = node.id else onOpen(key) }
                    }
                }
            }
        }
    }
}

/**
 * Where the cards shown are, [where], as a heading, with a button that opens the folder's page where [edit] names one,
 * and one that adds a folder there, given its name, through [onAddFolder] where a folder can go there.
 */
@Composable
private fun Where(where: String, edit: String?, onEdit: () -> Unit, onAddFolder: ((String) -> Unit)?) {
    val newFolder = stringResource(R.string.scripts_new_folder)
    Row(itemWidth.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(where, Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        if (edit != null) IconButton(onEdit) { Icon(painterResource(R.drawable.ic_edit), edit) }
        if (onAddFolder != null) IconButton({ onAddFolder(newFolder) }) { Icon(painterResource(R.drawable.ic_create_new_folder), stringResource(R.string.scripts_add_folder)) }
    }
}

/**
 * An item as a card: its name, marked while it has a draft, what is to say of it ([rowStatus]), then when it runs and
 * what it does then, in words, once the engine has given its fields. Its switch is beside them.
 */
@Composable
private fun Card(scripts: ScriptItemsClient, key: ScriptItemKey, node: ScriptItemNode, children: Int, where: String?, selected: Boolean, onClick: () -> Unit) {
    // What a card says comes with the item's fields; asked once, and again once the engine is back
    LaunchedEffect(key, scripts.attaches) { if (!node.folder && scripts.saved[key] == null) scripts.open(key) }
    val item = scripts.drafts[key] ?: scripts.saved[key]
    val name = nameOf(node)
    val colors = MaterialTheme.colorScheme
    val holds = node.holdsItems(children)
    Surface(itemWidth.padding(vertical = 4.dp), shape = MaterialTheme.shapes.large, color = if (selected) colors.secondaryContainer else colors.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(onClickLabel = stringResource(if (holds) R.string.scripts_open_folder else R.string.scripts_open_item), onClick = onClick)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(if (node.folder) R.drawable.ic_folder else key.kind.icon), contentDescription = null, Modifier.padding(end = 16.dp), tint = colors.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                if (where != null) Text(where, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
                    if (key in scripts.drafts) DraftBadge(Modifier.padding(start = 8.dp))
                }
                node.rowStatus(children)?.let { StatusText(it) }
                if (item != null && !node.folder) {
                    Part(stringResource(R.string.recipes_when), whenLines(item, node))
                    Part(stringResource(R.string.recipes_then), thenLines(item))
                }
            }
            Switch(node.enabled, { scripts.enable(key, it) }, Modifier.semantics { contentDescription = name })
        }
    }
}

/** A part of a card: its [label], "When" or "Then", before what it says, read with the rest of the card. */
@Composable
private fun Part(label: String, lines: List<String>) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        // BITE P3: what a card says is cut short
        for (line in lines) Text(line, Modifier.widthIn(max = 80.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The Recipes design's page of the item [key]. It opens on an overview to read: its switch, when it runs, what it does
 * then and its name and options, each a card. A tap on one changes that part on a step of its own, from which Next goes
 * to the part after it; an item just made opens on the first. Apply, on the overview, saves all that was changed, as
 * the current editor's does. What is wrong with the Lua so applied shows on every step, with a button that goes to its
 * line. A folder's page is the current editor's, as its parts are not a recipe's.
 */
@Composable
internal fun RecipesItem(scripts: ScriptItemsClient, key: ScriptItemKey, functions: List<LuaFunction>, onBack: () -> Unit, onGame: () -> Unit) {
    // Told by the list where it has the item: the current editor's page asks for a folder itself, and asked here too it would be twice
    if (scripts.lists[key.kind]?.firstOrNull { it.id == key.id }?.folder == true) {
        ScriptItemScreen(scripts, key, functions, onBack, onGame)
        return
    }
    val page = rememberItemPage(scripts, key)
    if (page.item?.node?.folder == true) {
        ScriptItemScreen(scripts, key, functions, onBack, onGame)
        return
    }
    val item = page.item
    val dirty = page.dirty
    var leaving by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var step by rememberSaveable {
        mutableStateOf(if (RecipesGuide.take(key)) Step.WHEN else Step.OVERVIEW)
    }
    BackHandler(enabled = step != Step.OVERVIEW || dirty) { if (step != Step.OVERVIEW) step = Step.OVERVIEW else leaving = true }
    val name = item?.node?.let { nameOf(it) }
    val lua = rememberLuaFieldState(scripts, key, functions)
    val luaFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()

    MudletScaffold(
        if (step == Step.OVERVIEW) name ?: stringResource(R.string.scripts_loading) else stringResource(step.title),
        onBack = {
            when {
                step != Step.OVERVIEW -> step = Step.OVERVIEW
                dirty -> leaving = true
                else -> onBack()
            }
        },
        snackbarHost = { if (!LocalInDetailPane.current) ScriptNotices(scripts) {} },
        actions = {
            if (item != null && step == Step.OVERVIEW) ItemActions(scripts, page, name.orEmpty(), onMove = { moving = true }, onBack)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp).testTag(SCRIPT_ITEM_TAG),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    item == null -> ItemNotOpen(page, fieldWidth.padding(16.dp))
                    step == Step.OVERVIEW -> Overview(scripts, item, page.listed.firstOrNull { it.id == key.id }, page.error) { step = it }
                    else -> StepFields(step, scripts, item, lua, scroll.viewportSize, luaFocus)
                }
            }
            // BITE B3: Recipes' page does not show what is wrong with the Lua
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                if (item != null && lua.focused) {
                    LuaKeys(lua, snippets = SNIPPETS)
                } else {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Row(fieldWidth, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (step == Step.OVERVIEW) {
                                GameButton(onGame)
                                Button({ scripts.apply(key) }, Modifier.weight(1f), enabled = dirty) { Text(stringResource(R.string.scripts_apply)) }
                            } else {
                                OutlinedButton({ step = Step.OVERVIEW }) { Text(stringResource(R.string.recipes_overview)) }
                                val next = Step.entries.getOrNull(step.ordinal + 1)
                                Button({ step = next ?: Step.OVERVIEW }, Modifier.weight(1f)) {
                                    Text(if (next == null) stringResource(R.string.recipes_finish) else stringResource(R.string.recipes_next, stringResource(next.title)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    ItemDialogs(scripts, page, name.orEmpty(), moving, leaving, onClose = {
        moving = false
        leaving = false
    }, onBack)
}

/** The item's overview: why it does not run, its switch, from [listed] where the list has it, then a card for each part. */
@Composable
private fun ColumnScope.Overview(scripts: ScriptItemsClient, item: ScriptItem, listed: ScriptItemNode?, error: String, onStep: (Step) -> Unit) {
    val node = listed ?: item.node
    NotRunningBanner(error, fieldWidth.padding(top = 8.dp))
    SwitchField(R.string.designs_on, R.string.designs_on_hint, node.enabled) { scripts.enable(ScriptItemKey(item.kind, item.node.id), it) }
    PartCard(stringResource(R.string.recipes_when), whenLines(item, node), code = "") { onStep(Step.WHEN) }
    PartCard(stringResource(R.string.recipes_then), thenLines(item), code = item.script.trim()) { onStep(Step.THEN) }
    val details = item.details
    val options = when (details) {
        is ScriptItemDetails.Trigger -> listOfNotNull(
            if (details.patterns.size > 1) stringResource(if (details.lineDelta >= 0) R.string.scripts_match_all else R.string.scripts_match_any) else null,
            if (details.matchAll) stringResource(R.string.scripts_every_occurrence) else null,
            if (details.filter) stringResource(R.string.scripts_filter) else null,
            if (details.highlight) stringResource(R.string.scripts_highlight) else null,
            if (details.sound) stringResource(R.string.scripts_sound) else null,
        )

        else -> emptyList()
    }
    PartCard(stringResource(R.string.recipes_more), listOf(nameOf(item.node)) + options, code = "") { onStep(Step.MORE) }
}

/** A part of the item on its overview: its [title], what it says in [lines], and the first of its [code] if it has any. A tap changes it. */
@Composable
private fun PartCard(title: String, lines: List<String>, code: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(fieldWidth.padding(top = 12.dp), shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = stringResource(R.string.recipes_change), role = Role.Button, onClick = onClick).padding(16.dp),
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

/** The first lines of [code], then how many more there are. */
@Composable
private fun CodePreview(code: String, modifier: Modifier = Modifier) {
    val lines = code.lines()
    val more = lines.size - CARD_CODE_LINES
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(8.dp)) {
            Text(lines.take(CARD_CODE_LINES).joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (more > 0) Text(pluralStringResource(R.plurals.recipes_more_lines, more, more), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One part of the item to change, under which part it is and what the part is for: what makes it run; what it sends
 * and its Lua, in [lua] wrapped to the screen with the line the cursor is on under it, [viewport] high what shows; or
 * its name and options.
 */
@Composable
private fun ColumnScope.StepFields(step: Step, scripts: ScriptItemsClient, item: ScriptItem, lua: LuaFieldState, viewport: Int, luaFocus: FocusRequester) {
    val details = item.details
    Text(stringResource(R.string.recipes_step, step.ordinal, Step.entries.size - 1), fieldWidth.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    val explains = when (step) {
        Step.WHEN -> when (details) {
            is ScriptItemDetails.Trigger -> R.string.recipes_when_trigger_hint
            is ScriptItemDetails.Alias -> R.string.recipes_when_alias_hint
            is ScriptItemDetails.Script -> R.string.recipes_when_script_hint
            is ScriptItemDetails.Timer -> R.string.recipes_when_timer_hint
            is ScriptItemDetails.Key -> R.string.recipes_when_key_hint
            is ScriptItemDetails.Button -> R.string.recipes_when_button_hint
        }

        Step.THEN -> R.string.recipes_then_hint

        else -> R.string.recipes_more_hint
    }
    Text(stringResource(explains), fieldWidth.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge)
    val change = { to: ScriptItemDetails -> scripts.edit(item.copy(details = to)) }
    when (step) {
        Step.WHEN -> when (details) {
            is ScriptItemDetails.Trigger -> TriggerPatterns(details, change)
            is ScriptItemDetails.Alias -> AliasPattern(details, change)
            is ScriptItemDetails.Script -> EventHandlers(details, change)
            is ScriptItemDetails.Timer -> TimerTime(details, change)
            is ScriptItemDetails.Key -> KeyPick(details, change)
            is ScriptItemDetails.Button -> ButtonPushDown(details, change)
        }

        Step.THEN -> {
            when (details) {
                is ScriptItemDetails.Trigger -> TriggerCommand(details, change)

                is ScriptItemDetails.Alias -> AliasCommand(details, change)

                is ScriptItemDetails.Script -> Unit

                is ScriptItemDetails.Timer -> CommandField(R.string.scripts_timer_command, details.command) { change(details.copy(command = it)) }

                is ScriptItemDetails.Key -> CommandField(R.string.scripts_key_command, details.command) { change(details.copy(command = it)) }

                is ScriptItemDetails.Button -> {
                    CommandField(R.string.scripts_button_command, details.commandDown) { change(details.copy(commandDown = it)) }
                    if (details.pushDown) CommandField(R.string.scripts_button_command_up, details.commandUp) { change(details.copy(commandUp = it)) }
                }
            }
            LuaField(lua, viewport, luaFocus, fieldWidth.padding(top = 16.dp), numbered = false)
            val value = lua.value
            val line = lineCount(value.text.take(value.selection.start))
            Text(
                stringResource(R.string.recipes_line, line, lineCount(value.text)),
                fieldWidth.padding(horizontal = 4.dp, vertical = 4.dp).padding(bottom = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        else -> {
            NameField(item, onChange = scripts::edit)
            when (details) {
                is ScriptItemDetails.Trigger -> TriggerOptions(details, change)
                is ScriptItemDetails.Button -> ButtonCss(details, change)
                else -> Unit
            }
        }
    }
}
