package org.mudlet.android.ui.scripts.designs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.LocalInDetailPane
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.ScriptItemsClient
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.scripts.Adds
import org.mudlet.android.ui.scripts.ConsoleStrip
import org.mudlet.android.ui.scripts.DraftBadge
import org.mudlet.android.ui.scripts.GameButton
import org.mudlet.android.ui.scripts.Hint
import org.mudlet.android.ui.scripts.ItemDialogs
import org.mudlet.android.ui.scripts.ItemNotOpen
import org.mudlet.android.ui.scripts.KindFields
import org.mudlet.android.ui.scripts.LuaField
import org.mudlet.android.ui.scripts.LuaFunction
import org.mudlet.android.ui.scripts.LuaKeys
import org.mudlet.android.ui.scripts.LuaProblemLine
import org.mudlet.android.ui.scripts.NameField
import org.mudlet.android.ui.scripts.NotRunningBanner
import org.mudlet.android.ui.scripts.RowStatus
import org.mudlet.android.ui.scripts.SCRIPT_ITEM_TAG
import org.mudlet.android.ui.scripts.SCRIPT_LIBRARY_TAG
import org.mudlet.android.ui.scripts.ScriptHelpButton
import org.mudlet.android.ui.scripts.ScriptItemKey
import org.mudlet.android.ui.scripts.ScriptNotices
import org.mudlet.android.ui.scripts.SearchAsTyped
import org.mudlet.android.ui.scripts.SearchOptions
import org.mudlet.android.ui.scripts.StatusText
import org.mudlet.android.ui.scripts.SwitchField
import org.mudlet.android.ui.scripts.VariablesChip
import org.mudlet.android.ui.scripts.adds
import org.mudlet.android.ui.scripts.fieldWidth
import org.mudlet.android.ui.scripts.holdsItems
import org.mudlet.android.ui.scripts.pathTo
import org.mudlet.android.ui.scripts.rememberItemPage
import org.mudlet.android.ui.scripts.rememberLuaFieldState
import org.mudlet.android.ui.scripts.rowStatus
import org.mudlet.android.ui.scripts.title
import org.mudlet.android.ui.scripts.visibleLists
import org.mudlet.android.ui.scripts.whereText

// How far each level of the tree is set in, and the deepest level that still is: a phone has no room for more
private const val INDENT_DP = 16
private const val MAX_INDENT = 5

/** A row of the tree: an item, how deep it is, and how many items are directly in it. */
internal data class TreeRow(val node: ScriptItemNode, val depth: Int, val children: Int)

/**
 * [items], one kind's as listed, as a tree with those in [open] opened: what is in each, a folder or an item that holds
 * others ([holdsItems]), under it and a level deeper.
 */
internal fun treeRows(items: List<ScriptItemNode>, open: Set<Int>): List<TreeRow> {
    val byParent = items.groupBy { it.parent }
    val rows = ArrayList<TreeRow>()
    // A loop of parents would never end, so each item is listed once
    val seen = HashSet<Int>()

    fun list(parent: Int, depth: Int) {
        for (node in byParent[parent].orEmpty()) {
            if (!seen.add(node.id)) continue
            val children = byParent[node.id]?.size ?: 0
            rows.add(TreeRow(node, depth, children))
            if (node.id in open && node.holdsItems(children)) list(node.id, depth + 1)
        }
    }
    list(0, 0)
    return rows
}

/**
 * The Workbench design's library of [game]: a tab at the foot for each kind, over the last of the game's lines, and
 * the kind's items as one tree, in dense rows that open in place, each with a tick box that switches it on or off. What
 * holds items has a menu that makes items in it and edits it; the title bar's buttons search and make items at the
 * top level. The parameters are [org.mudlet.android.ui.scripts.ScriptLibraryScreen]'s.
 */
@Composable
internal fun WorkbenchLibrary(
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
    // What is opened, as encoded keys with a space between, as a saved state takes a string
    var opened by rememberSaveable { mutableStateOf("") }
    val open = opened.split(' ').mapNotNull(ScriptItemKey::decode).toSet()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    val options = SearchOptions(caseSensitive, wholeWord)
    var adding by remember { mutableStateOf(false) }
    SearchAsTyped(scripts, query, options)
    val lists = visibleLists(scripts)
    val items = lists[kind]
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    fun setOpen(keys: Set<ScriptItemKey>) {
        opened = keys.joinToString(" ") { it.encode() }
    }

    fun reveal(of: ScriptItemKind, id: Int) {
        kindWire = of.wire
        searching = false
        query = ""
        setOpen(open + lists[of].orEmpty().pathTo(id).map { ScriptItemKey(of, it.id) } + ScriptItemKey(of, id))
    }

    MudletScaffold(
        stringResource(R.string.scripts_title, game),
        onBack = onBack,
        actions = {
            actions()
            SearchButton(searching) {
                searching = !searching
                query = ""
            }
            // A new item goes after those listed, so not before they are
            if (items != null && adds(kind, null) != Adds.NOTHING) {
                Box {
                    IconButton({ adding = true }) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.designs_add)) }
                    DropdownMenu(adding, { adding = false }) { AddMenuItems(scripts, kind, null) { adding = false } }
                }
            }
            ScriptHelpButton(kind)
        },
        snackbarHost = { ScriptNotices(scripts) { added -> if (added.folder) reveal(added.key.kind, added.key.id) else onOpen(added.key) } },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding()) {
                ConsoleStrip(console, colors, atFoot = false, onGame = onBack)
                KindTabs(kind, lists) { kindWire = it.wire }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(SCRIPT_LIBRARY_TAG), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (searching) {
                item(key = "search") {
                    SearchHeader(query, { query = it }, options, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        caseSensitive = it.caseSensitive
                        wholeWord = it.wholeWord
                    }
                }
                if (query.isNotBlank()) {
                    searchResultItems(query, lists, options, scripts, divided = true) { result, key, children ->
                        TreeRowView(TreeRow(result.item, 0, children), key, scripts, whereText(result.kind, result.folders), open = false, selected = key == selected, onMenuOpen = {}, onEdit = { onOpen(key) }) {
                            if (result.item.holdsItems(children)) reveal(result.kind, result.item.id) else onOpen(key)
                        }
                    }
                }
            } else {
                if (onVariables != null) {
                    item(key = "variables") { VariablesChip(onVariables, Modifier.padding(horizontal = 16.dp)) }
                }
                when {
                    items == null -> item(key = "loading") { Hint(stringResource(R.string.scripts_loading)) }

                    items.isEmpty() -> item(key = "empty") { Hint(stringResource(R.string.scripts_none)) }

                    else -> items(treeRows(items, open.filter { it.kind == kind }.map { it.id }.toSet()), key = { "${kind.wire}/${it.node.id}" }) { row ->
                        val key = ScriptItemKey(kind, row.node.id)
                        val holds = row.node.holdsItems(row.children)
                        TreeRowView(row, key, scripts, where = null, open = key in open, selected = key == selected, onMenuOpen = { setOpen(open + key) }, onEdit = { onOpen(key) }) {
                            if (holds) setOpen(if (key in open) open - key else open + key) else onOpen(key)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    }
                }
            }
        }
    }
}

/** A tab for each kind, with its icon and how many items it has, folders aside, once listed; they scroll on a narrow screen. */
@Composable
private fun KindTabs(kind: ScriptItemKind, lists: Map<ScriptItemKind, List<ScriptItemNode>?>, onKind: (ScriptItemKind) -> Unit) {
    PrimaryScrollableTabRow(ScriptItemKind.entries.indexOf(kind), containerColor = MaterialTheme.colorScheme.surfaceContainer, edgePadding = 0.dp) {
        for (k in ScriptItemKind.entries) {
            val name = stringResource(k.title)
            val count = lists[k]?.count { !it.folder }
            Tab(
                k == kind,
                { onKind(k) },
                text = { Text(if (count == null) name else stringResource(R.string.scripts_kind_count, name, count)) },
                icon = { Icon(painterResource(k.icon), contentDescription = null) },
            )
        }
    }
}

/**
 * A row of the tree: an arrow for what holds items, the name, marked while it has a draft, what is to say of it, a menu
 * for what holds items and the tick box. [where] is given for a search's result, which is not set in and has no menu.
 */
@Composable
private fun TreeRowView(
    row: TreeRow,
    key: ScriptItemKey,
    scripts: ScriptItemsClient,
    where: String?,
    open: Boolean,
    selected: Boolean,
    onMenuOpen: () -> Unit,
    onEdit: () -> Unit,
    onClick: () -> Unit,
) {
    val node = row.node
    val name = nameOf(node)
    val colors = MaterialTheme.colorScheme
    val holds = node.holdsItems(row.children)
    var menu by remember { mutableStateOf(false) }
    val does = stringResource(
        when {
            !holds -> R.string.scripts_open_item
            open -> R.string.workbench_close_folder
            else -> R.string.scripts_open_folder
        },
    )
    Row(
        Modifier.fillMaxWidth().background(if (selected) colors.secondaryContainer else colors.surface)
            .heightIn(min = 48.dp).clickable(onClickLabel = does, onClick = onClick)
            .padding(start = (4 + INDENT_DP * minOf(row.depth, MAX_INDENT)).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (holds && where == null) {
            Icon(painterResource(if (open) R.drawable.ic_expand_more else R.drawable.ic_chevron_right), contentDescription = null, Modifier.padding(horizontal = 4.dp), tint = colors.onSurfaceVariant)
        } else {
            Spacer(Modifier.width(32.dp))
        }
        if (node.folder) Icon(painterResource(R.drawable.ic_folder), contentDescription = null, Modifier.padding(end = 8.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            if (where != null) Text(where, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = if (node.enabled) colors.onSurface else colors.onSurfaceVariant)
                if (key in scripts.drafts) DraftBadge(Modifier.padding(start = 6.dp))
            }
            // The tick box says whether it is off
            val status = node.rowStatus(row.children)
            if (status != null && status != RowStatus.Off) StatusText(status)
        }
        if (holds && where == null) {
            Box {
                IconButton({ menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.workbench_menu, name)) }
                DropdownMenu(menu, { menu = false }) {
                    AddMenuItems(scripts, key.kind, node) {
                        menu = false
                        onMenuOpen()
                    }
                    DropdownMenuItem(
                        { Text(if (node.folder) stringResource(R.string.scripts_edit_folder) else stringResource(R.string.scripts_edit_item, name)) },
                        {
                            menu = false
                            onEdit()
                        },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                    )
                }
            }
        }
        Checkbox(node.enabled, { scripts.enable(key, it) }, Modifier.semantics { contentDescription = name })
    }
}

/**
 * The Workbench design's page of the item [key]: its Lua first, in the editor, and all else it has on a second tab.
 * What is changed on either is one draft, which Apply in the title bar saves; [onGame] goes to the game and keeps it,
 * and Back asks whether to discard it. What is wrong with the Lua Apply was given shows over the tabs, with a button
 * that goes to its line.
 */
@Composable
internal fun WorkbenchItem(scripts: ScriptItemsClient, key: ScriptItemKey, functions: List<LuaFunction>, onBack: () -> Unit, onGame: () -> Unit) {
    val page = rememberItemPage(scripts, key)
    val item = page.item
    val dirty = page.dirty
    var leaving by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = dirty) { leaving = true }
    val name = item?.node?.let { nameOf(it) }
    val lua = rememberLuaFieldState(scripts, key, functions)
    val luaFocus = remember { FocusRequester() }
    val code = rememberScrollState()

    MudletScaffold(
        name ?: stringResource(R.string.scripts_loading),
        onBack = { if (dirty) leaving = true else onBack() },
        snackbarHost = { if (!LocalInDetailPane.current) ScriptNotices(scripts) {} },
        actions = {
            TextButton({ scripts.apply(key) }, enabled = dirty) { Text(stringResource(R.string.scripts_apply)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (item == null) {
                ItemNotOpen(page, Modifier.padding(16.dp))
            } else {
                NotRunningBanner(page.error, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                scripts.luaProblem(key)?.let { found ->
                    LuaProblemLine(found) {
                        tab = 0
                        lua.goTo(found.line)
                        luaFocus.requestFocus()
                    }
                }
                PrimaryTabRow(tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.workbench_code)) })
                    Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.workbench_settings)) })
                }
                if (tab == 0) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(code)) {
                        LuaField(lua, code.viewportSize, luaFocus, Modifier.fillMaxWidth())
                    }
                    if (lua.focused) LuaKeys(lua)
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(SCRIPT_ITEM_TAG), horizontalAlignment = Alignment.CenterHorizontally) {
                        val listed = page.listed
                        SwitchField(R.string.designs_on, R.string.designs_on_hint, listed.firstOrNull { it.id == key.id }?.enabled ?: item.node.enabled) { scripts.enable(key, it) }
                        NameField(item, onChange = scripts::edit)
                        KindFields(item, listed, scripts::edit)
                        Row(fieldWidth.padding(vertical = 16.dp)) {
                            GameButton(onGame)
                            Spacer(Modifier.weight(1f))
                            if (page.targets.isNotEmpty()) {
                                IconButton({ moving = true }) { Icon(painterResource(R.drawable.ic_drive_file_move), stringResource(R.string.scripts_move, name.orEmpty())) }
                            }
                        }
                        OutlinedButton(
                            {
                                scripts.deleteLater(key, name.orEmpty())
                                onBack()
                            },
                            fieldWidth.padding(bottom = 16.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                            Text(stringResource(R.string.scripts_delete, name.orEmpty()), Modifier.padding(start = 8.dp))
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
