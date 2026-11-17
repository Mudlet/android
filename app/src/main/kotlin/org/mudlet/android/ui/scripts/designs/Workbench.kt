package org.mudlet.android.ui.scripts.designs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.ScriptItemsClient
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.scripts.AliasCommand
import org.mudlet.android.ui.scripts.AliasPattern
import org.mudlet.android.ui.scripts.Editor
import org.mudlet.android.ui.scripts.EventHandlers
import org.mudlet.android.ui.scripts.Hint
import org.mudlet.android.ui.scripts.LuaFunction
import org.mudlet.android.ui.scripts.SCRIPT_ITEM_TAG
import org.mudlet.android.ui.scripts.SCRIPT_LIBRARY_TAG
import org.mudlet.android.ui.scripts.ScriptItemKey
import org.mudlet.android.ui.scripts.ScriptNotices
import org.mudlet.android.ui.scripts.SearchField
import org.mudlet.android.ui.scripts.SwitchField
import org.mudlet.android.ui.scripts.TriggerCommand
import org.mudlet.android.ui.scripts.TriggerOptions
import org.mudlet.android.ui.scripts.TriggerPatterns
import org.mudlet.android.ui.scripts.fieldWidth
import org.mudlet.android.ui.scripts.pathTo
import org.mudlet.android.ui.scripts.searchScripts
import org.mudlet.android.ui.scripts.title

// How far each level of the tree is set in, and the deepest level that still is: a phone has no room for more
private const val INDENT_DP = 20
private const val MAX_INDENT = 6

/** A row of the tree: an item, how deep it is, and how many items a folder has. */
internal data class TreeRow(val node: ScriptItemNode, val depth: Int, val children: Int)

/** [items] as a tree with the folders in [open] opened: each folder's items under it, a level deeper. */
internal fun treeRows(items: List<ScriptItemNode>, open: Set<Int>): List<TreeRow> {
    val byParent = items.groupBy { it.parent }
    val rows = ArrayList<TreeRow>()
    // A parent's id names a listed folder, but a loop would never end, so each item is listed once
    val seen = HashSet<Int>()

    fun list(parent: Int, depth: Int) {
        for (node in byParent[parent].orEmpty()) {
            if (!seen.add(node.id)) continue
            rows.add(TreeRow(node, depth, byParent[node.id]?.size ?: 0))
            if (node.folder && node.id in open) list(node.id, depth + 1)
        }
    }
    list(0, 0)
    return rows
}

/**
 * Design A's library of [game]: a tab at the foot for each kind, and the kind's items as one tree whose folders open
 * in place, in dense rows with a tick box that switches each on or off. A folder's menu makes items in it or opens it
 * to edit; the title bar's buttons search every kind's names and make items at the top level. [onOpen] opens an item.
 */
@Composable
internal fun WorkbenchLibrary(
    scripts: ScriptItemsClient,
    game: String,
    onBack: () -> Unit,
    onOpen: (ScriptItemKey) -> Unit,
    console: () -> List<UiLine>,
    colors: ConsoleColors,
) {
    var kindWire by rememberSaveable { mutableStateOf(ScriptItemKind.TRIGGER.wire) }
    val kind = ScriptItemKind.fromWire(kindWire) ?: ScriptItemKind.TRIGGER
    // The folders opened, as encoded keys with a space between
    var opened by rememberSaveable { mutableStateOf("") }
    val open = opened.split(' ').mapNotNull(ScriptItemKey::decode).toSet()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val lists = visibleLists(scripts)
    val items = lists[kind]
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    fun setOpen(key: ScriptItemKey, to: Boolean) {
        opened = (if (to) open + key else open - key).joinToString(" ") { it.encode() }
    }

    fun reveal(of: ScriptItemKind, folder: Int) {
        kindWire = of.wire
        searching = false
        query = ""
        opened = (open + lists[of].orEmpty().pathTo(folder).map { ScriptItemKey(of, it.id) }).joinToString(" ") { it.encode() }
    }

    MudletScaffold(
        stringResource(R.string.scripts_title, game),
        onBack = onBack,
        actions = {
            IconButton({
                searching = !searching
                query = ""
            }) { Icon(painterResource(if (searching) R.drawable.ic_close else R.drawable.ic_search), stringResource(if (searching) R.string.design_search_close else R.string.design_search)) }
            // A new item goes after those listed, so not before they are
            if (items != null) {
                Box {
                    IconButton({ adding = true }) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.design_add)) }
                    DropdownMenu(adding, { adding = false }) { AddMenuItems(scripts, kind, 0) { adding = false } }
                }
            }
        },
        snackbarHost = { ScriptNotices(scripts) { added -> if (added.folder) setOpen(added.key, true) else onOpen(added.key) } },
        bottomBar = {
            Column {
                GameStrip(console, colors, onGame = onBack)
                NavigationBar {
                    for (k in ScriptItemKind.entries) {
                        val name = stringResource(k.title)
                        val count = lists[k]?.count { !it.folder }
                        NavigationBarItem(
                            k == kind,
                            { kindWire = k.wire },
                            icon = { Icon(painterResource(k.icon), contentDescription = null) },
                            label = { Text(if (count == null) name else stringResource(R.string.scripts_kind_count, name, count), maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(SCRIPT_LIBRARY_TAG), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (searching) {
                item(key = "search") { Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { SearchField(query) { query = it } } }
                val results = if (query.isBlank()) emptyList() else searchScripts(query, lists.mapValues { it.value.orEmpty() })
                if (query.isNotBlank() && results.isEmpty()) item(key = "none") { Hint(stringResource(R.string.scripts_search_none, query.trim())) }
                items(results, key = { "${it.kind.wire}/${it.item.id}" }) { result ->
                    val key = ScriptItemKey(result.kind, result.item.id)
                    val where = (listOf(stringResource(result.kind.title)) + result.folders.map { it.name }).joinToString(stringResource(R.string.scripts_path_separator))
                    TreeRowView(TreeRow(result.item, 0, 0), key, scripts, where, open = false, onMenuOpen = {}, onEdit = { onOpen(key) }) {
                        if (result.item.folder) reveal(result.kind, result.item.id) else onOpen(key)
                    }
                }
            } else if (items == null) {
                item(key = "loading") { Hint(stringResource(R.string.scripts_loading)) }
            } else if (items.isEmpty()) {
                item(key = "empty") { Hint(stringResource(R.string.scripts_none)) }
            } else {
                items(treeRows(items, open.filter { it.kind == kind }.map { it.id }.toSet()), key = { "${kind.wire}/${it.node.id}" }) { row ->
                    val key = ScriptItemKey(kind, row.node.id)
                    TreeRowView(row, key, scripts, where = null, open = key in open, onMenuOpen = { setOpen(key, true) }, onEdit = { onOpen(key) }) {
                        if (row.node.folder) setOpen(key, key !in open) else onOpen(key)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                }
            }
        }
    }
}

/**
 * A row of the tree, 48 dp high: a folder's arrow, the name, what is to say of it, a folder's menu and the tick box
 * that switches it on or off. [where] is given for a search's result, which is not set in.
 */
@Composable
private fun TreeRowView(
    row: TreeRow,
    key: ScriptItemKey,
    scripts: ScriptItemsClient,
    where: String?,
    open: Boolean,
    onMenuOpen: () -> Unit,
    onEdit: () -> Unit,
    onClick: () -> Unit,
) {
    val node = row.node
    val name = nameOf(node)
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val does = stringResource(
        when {
            !node.folder -> R.string.scripts_open_item
            open -> R.string.design_a_close_folder
            else -> R.string.scripts_open_folder
        },
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = does, onClick = onClick).padding(start = (4 + INDENT_DP * minOf(row.depth, MAX_INDENT)).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.folder) {
            Icon(painterResource(if (open) R.drawable.ic_expand_more else R.drawable.ic_chevron_right), contentDescription = null, Modifier.padding(horizontal = 4.dp), tint = colors.onSurfaceVariant)
            Icon(painterResource(R.drawable.ic_folder), contentDescription = null, Modifier.padding(end = 8.dp).size(20.dp), tint = colors.onSurfaceVariant)
        } else {
            Spacer(Modifier.width(32.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            if (where != null) Text(where, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = if (node.enabled) colors.onSurface else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (key in scripts.drafts) DraftBadge(Modifier.padding(start = 6.dp))
            }
            if (node.error.isNotEmpty()) Text(stringResource(R.string.scripts_not_running, node.error), style = MaterialTheme.typography.labelSmall, color = colors.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // The tick box says whether it is off, and the error is under the name
        val status = if (node.error.isNotEmpty() || (!node.folder && !node.enabled)) null else statusOf(node, row.children)
        if (status != null) {
            Text(status, Modifier.padding(start = 8.dp).widthIn(max = 112.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (node.folder && where == null) {
            Box {
                IconButton({ menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.design_a_folder_menu, name)) }
                DropdownMenu(menu, { menu = false }) {
                    AddMenuItems(scripts, key.kind, node.id) {
                        menu = false
                        onMenuOpen()
                    }
                    DropdownMenuItem(
                        { Text(stringResource(R.string.scripts_edit_folder)) },
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
 * Design A's page of the item [key]: its Lua first, in the editor, with all else it has on a second tab. What is
 * changed on either is one draft, which Save in the title bar saves; [onGame] goes to the game and keeps it, and Back
 * asks whether to discard it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkbenchItem(scripts: ScriptItemsClient, key: ScriptItemKey, functions: List<LuaFunction>, onBack: () -> Unit, onGame: () -> Unit) {
    // Asked again each time, as a script may have changed it since, and once the engine is back; a draft stays over what comes
    LaunchedEffect(key, scripts.attaches) { scripts.open(key) }
    val saved = scripts.saved[key]
    val draft = scripts.drafts[key]
    val item = draft ?: saved
    val dirty = draft != null
    var leaving by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = dirty) { leaving = true }
    val name = item?.node?.let { nameOf(it) }

    MudletScaffold(
        name ?: stringResource(R.string.scripts_loading),
        onBack = { if (dirty) leaving = true else onBack() },
        snackbarHost = { ScriptNotices(scripts) {} },
        actions = {
            IconButton(onGame) { Icon(painterResource(R.drawable.ic_videogame_asset), stringResource(R.string.scripts_back_to_game)) }
            TextButton({ scripts.apply(key) }, enabled = dirty) { Text(stringResource(R.string.design_save)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            val refusal = scripts.unopened[key]
            when {
                item != null -> {
                    NotRunning(saved?.node?.error.orEmpty(), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                    PrimaryTabRow(tab) {
                        Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.scripts_code)) })
                        Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.design_a_settings)) })
                    }
                    if (tab == 0) {
                        Editor(scripts, item, functions, Modifier.weight(1f).fillMaxWidth())
                    } else {
                        Settings(item, saved?.node?.enabled ?: item.node.enabled, scripts, Modifier.weight(1f)) {
                            scripts.deleteLater(key, name.orEmpty())
                            onBack()
                        }
                    }
                }

                refusal != null -> Text(stringResource(R.string.scripts_refused_open, refusal), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)

                else -> Text(stringResource(R.string.scripts_loading), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun Settings(item: ScriptItem, enabled: Boolean, scripts: ScriptItemsClient, modifier: Modifier, onDelete: () -> Unit) {
    val key = ScriptItemKey(item.kind, item.node.id)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(SCRIPT_ITEM_TAG), horizontalAlignment = Alignment.CenterHorizontally) {
        SwitchField(R.string.design_on, R.string.design_on_hint, enabled) { scripts.enable(key, it) }
        NameField(item, onChange = scripts::edit)
        when (val details = item.details) {
            is ScriptItemDetails.Trigger -> {
                val change = { to: ScriptItemDetails.Trigger -> scripts.edit(item.copy(details = to)) }
                TriggerPatterns(details, change)
                TriggerCommand(details, change)
                TriggerOptions(details, change)
            }

            is ScriptItemDetails.Alias -> {
                val change = { to: ScriptItemDetails.Alias -> scripts.edit(item.copy(details = to)) }
                AliasPattern(details, change)
                AliasCommand(details, change)
            }

            is ScriptItemDetails.Script -> EventHandlers(details) { scripts.edit(item.copy(details = it)) }
        }
        OutlinedButton(onDelete, fieldWidth.padding(vertical = 16.dp)) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
            Text(stringResource(R.string.scripts_delete, nameOf(item.node)), Modifier.padding(start = 8.dp))
        }
    }
}
