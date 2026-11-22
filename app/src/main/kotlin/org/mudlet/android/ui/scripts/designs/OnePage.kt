package org.mudlet.android.ui.scripts.designs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.protocol.ScriptSearchMatch
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.ListSegment
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.ScriptItemsClient
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.scripts.Adds
import org.mudlet.android.ui.scripts.ConsoleStrip
import org.mudlet.android.ui.scripts.DraftBadge
import org.mudlet.android.ui.scripts.Hint
import org.mudlet.android.ui.scripts.ItemDialogs
import org.mudlet.android.ui.scripts.ItemNotOpen
import org.mudlet.android.ui.scripts.KindFields
import org.mudlet.android.ui.scripts.LibrarySearchField
import org.mudlet.android.ui.scripts.LuaField
import org.mudlet.android.ui.scripts.LuaFieldState
import org.mudlet.android.ui.scripts.LuaFunction
import org.mudlet.android.ui.scripts.LuaKeys
import org.mudlet.android.ui.scripts.LuaProblemLine
import org.mudlet.android.ui.scripts.NameField
import org.mudlet.android.ui.scripts.NotRunningBanner
import org.mudlet.android.ui.scripts.Places
import org.mudlet.android.ui.scripts.SCRIPT_LIBRARY_TAG
import org.mudlet.android.ui.scripts.ScriptHelpButton
import org.mudlet.android.ui.scripts.ScriptItemKey
import org.mudlet.android.ui.scripts.ScriptNotices
import org.mudlet.android.ui.scripts.SearchAsTyped
import org.mudlet.android.ui.scripts.SearchOptionChips
import org.mudlet.android.ui.scripts.SearchOptions
import org.mudlet.android.ui.scripts.StatusText
import org.mudlet.android.ui.scripts.TriggerOptions
import org.mudlet.android.ui.scripts.VariablesChip
import org.mudlet.android.ui.scripts.adds
import org.mudlet.android.ui.scripts.fieldWidth
import org.mudlet.android.ui.scripts.itemWidth
import org.mudlet.android.ui.scripts.newName
import org.mudlet.android.ui.scripts.rememberItemPage
import org.mudlet.android.ui.scripts.rememberLuaFieldState
import org.mudlet.android.ui.scripts.rowStatus
import org.mudlet.android.ui.scripts.searchHints
import org.mudlet.android.ui.scripts.searchScripts
import org.mudlet.android.ui.scripts.title
import org.mudlet.android.ui.scripts.visibleLists
import org.mudlet.android.ui.scripts.whereText

// The rows over the page's entries: the search field with the chips and the hint, in one
private const val FIXED_ROWS = 1

// How far each level of items that hold others is set in, and the deepest level that still is
private const val INDENT_DP = 16
private const val MAX_INDENT = 4

// As many lines of an item's summary as the current editor's library shows
private const val SUMMARY_LINES = 2

/** What the page lists, in order: a heading for each kind's top level and each folder, and the items under it. */
internal sealed interface PageEntry {
    val id: String

    /**
     * The heading of [folder] of [kind], or of the kind's top level for none, inside the folders of [path]; [count]
     * items are listed under it, and none while [collapsed].
     */
    data class Heading(val kind: ScriptItemKind, val folder: ScriptItemNode?, val path: List<ScriptItemNode>, val collapsed: Boolean, val count: Int) : PageEntry {
        val key get() = ScriptItemKey(kind, folder?.id ?: 0)
        override val id get() = idOf(key)

        companion object {
            fun idOf(key: ScriptItemKey) = "h/${key.encode()}"
        }
    }

    /**
     * The item [node], [index] of the [count] under its heading, set in [depth] levels for an item inside another that
     * is not a folder; a search's result has the folders it is in, [where], and the [places] found in it.
     */
    data class Item(
        val kind: ScriptItemKind,
        val node: ScriptItemNode,
        val index: Int,
        val count: Int,
        val depth: Int = 0,
        val where: List<ScriptItemNode>? = null,
        val places: List<ScriptSearchMatch> = emptyList(),
    ) : PageEntry {
        val key get() = ScriptItemKey(kind, node.id)
        override val id get() = idOf(key)

        companion object {
            fun idOf(key: ScriptItemKey) = "i/${key.encode()}"
        }
    }
}

/**
 * The page's entries for [kinds]: each kind's top level, then each folder, depth first, with the items under it; an
 * item inside another that is not a folder (a trigger that chains from another, an offset timer) comes after it, a
 * level deeper, and a folder inside one comes after the items of its heading. The folders in [collapsed] hide their
 * items and the folders in them.
 */
internal fun pageEntries(lists: Map<ScriptItemKind, List<ScriptItemNode>?>, kinds: List<ScriptItemKind>, collapsed: Set<ScriptItemKey>): List<PageEntry> {
    val entries = ArrayList<PageEntry>()
    for (kind in kinds) {
        val byParent = lists[kind]?.groupBy { it.parent } ?: continue
        // A loop of parents would never end, so each item is listed once
        val seen = HashSet<Int>()

        fun section(folder: ScriptItemNode?, path: List<ScriptItemNode>) {
            val items = ArrayList<Pair<ScriptItemNode, Int>>()
            val folders = ArrayList<ScriptItemNode>()

            fun gather(parent: Int, depth: Int) {
                for (node in byParent[parent].orEmpty()) {
                    if (!seen.add(node.id)) continue
                    if (node.folder) {
                        folders.add(node)
                    } else {
                        items.add(node to depth)
                        gather(node.id, depth + 1)
                    }
                }
            }
            gather(folder?.id ?: 0, 0)
            val closed = ScriptItemKey(kind, folder?.id ?: 0) in collapsed
            entries.add(PageEntry.Heading(kind, folder, path, closed, items.size))
            if (closed) return
            items.forEachIndexed { i, (node, depth) -> entries.add(PageEntry.Item(kind, node, i, items.size, depth)) }
            for (sub in folders) section(sub, path + sub)
        }
        section(null, emptyList())
    }
    return entries
}

/**
 * The One page design, the whole editor of [game] on one page: every kind's items in one list under headings that
 * name their folder, narrowed by a chip for each kind and a search. A tap unfolds an item where it is, with its fields
 * and its Lua; folding it, unfolding another or leaving the page applies what was changed, with Undo offered, so there
 * is no Apply button. What is wrong with the Lua so applied shows on its row, and on it unfolded with a button that goes
 * to its line. [start] is unfolded as the page comes. A rotation, which [changingConfigurations] tells, applies nothing.
 * The other parameters are [org.mudlet.android.ui.scripts.ScriptLibraryScreen]'s.
 */
@Composable
internal fun OnePage(
    scripts: ScriptItemsClient,
    game: String,
    functions: List<LuaFunction>,
    onBack: () -> Unit,
    console: () -> List<UiLine>,
    colors: ConsoleColors,
    onVariables: (() -> Unit)?,
    changingConfigurations: () -> Boolean,
    start: ScriptItemKey? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    var filter by rememberSaveable { mutableStateOf("") }
    val only = ScriptItemKind.fromWire(filter)
    val kinds = only?.let(::listOf) ?: ScriptItemKind.entries
    var query by rememberSaveable { mutableStateOf("") }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    val options = SearchOptions(caseSensitive, wholeWord)
    // The folders whose items are hidden, as encoded keys with a space between, as a saved state takes a string
    var closed by rememberSaveable { mutableStateOf("") }
    val collapsed = closed.split(' ').mapNotNull(ScriptItemKey::decode).toSet()
    var unfolded by rememberSaveable { mutableStateOf(start?.encode()) }
    val open = unfolded?.let(ScriptItemKey::decode)
    var adding by remember { mutableStateOf(false) }
    SearchAsTyped(scripts, query, options)
    val lists = visibleLists(scripts)
    val list = rememberLazyListState()
    val viewport by remember { derivedStateOf { list.layoutInfo.viewportSize.height } }
    val lua = open?.let { key(it) { rememberLuaFieldState(scripts, it, functions) } }

    fun fold() {
        open?.let { key ->
            if (key in scripts.drafts) scripts.apply(key)
            // Scrolled into a tall item, the list would keep that offset past its short row, which would leave the screen
            val ids = setOf(PageEntry.Item.idOf(key), PageEntry.Heading.idOf(key))
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.key in ids }?.let { if (it.offset < 0) list.requestScrollToItem(it.index) }
        }
        unfolded = null
    }

    fun unfold(key: ScriptItemKey) {
        if (key == open) return
        fold()
        unfolded = key.encode()
    }

    fun setCollapsed(keys: Set<ScriptItemKey>) {
        closed = keys.joinToString(" ") { it.encode() }
    }

    // BITE B2: leaving the page applies nothing
    val leave = { onBack() }
    BackHandler(enabled = open != null || query.isNotBlank()) { if (open != null) fold() else query = "" }
    // Whatever way the page goes, what was changed in the item unfolded is applied; read as it is then, as leave() folds it first

    val results = if (query.isBlank()) null else searchScripts(query, lists.mapValues { it.value.orEmpty() }, options, scripts.found)
    val entries = results?.map { result ->
        if (result.item.folder) {
            PageEntry.Heading(result.kind, result.item, result.folders + result.item, collapsed = true, count = 0)
        } else {
            PageEntry.Item(result.kind, result.item, 0, 1, where = result.folders, places = result.places)
        }
    } ?: pageEntries(lists, kinds, collapsed)
    // The item unfolded goes to the top of the page, as it is taller than its row was
    val at = entries.indexOfFirst { (it is PageEntry.Item && it.key == open) || (it is PageEntry.Heading && it.folder != null && it.key == open) }
    LaunchedEffect(unfolded, at >= 0) { if (at >= 0) list.animateScrollToItem(at + FIXED_ROWS) }

    MudletScaffold(
        stringResource(R.string.scripts_title, game),
        onBack = leave,
        actions = {
            actions()
            Box {
                IconButton({ adding = true }) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.designs_add)) }
                DropdownMenu(adding, { adding = false }) {
                    for (kind in ScriptItemKind.entries) {
                        val toolbar = adds(kind, null) == Adds.TOOLBAR
                        val name = stringResource(if (toolbar) R.string.scripts_new_toolbar else kind.newName)
                        DropdownMenuItem(
                            { Text(name) },
                            {
                                adding = false
                                setCollapsed(collapsed - ScriptItemKey(kind, 0))
                                scripts.add(kind, 0, folder = toolbar, name)
                            },
                            leadingIcon = { Icon(painterResource(kind.icon), contentDescription = null) },
                            // A new item goes after those listed, so not before they are
                            enabled = lists[kind] != null,
                        )
                    }
                }
            }
            ScriptHelpButton(only ?: ScriptItemKind.TRIGGER)
        },
        snackbarHost = {
            ScriptNotices(scripts) { added ->
                query = ""
                if (only != null) filter = added.key.kind.wire
                if (added.folder) setCollapsed(collapsed - added.key) else unfold(added.key)
            }
        },
        bottomBar = { if (lua?.focused != true) ConsoleStrip(console, colors, onGame = leave) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag(SCRIPT_LIBRARY_TAG),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(key = "top") {
                    Column(itemWidth, horizontalAlignment = Alignment.CenterHorizontally) {
                        LibrarySearchField(query) { query = it }
                        if (query.isBlank()) {
                            KindFilter(only, lists, onVariables) { filter = it?.wire.orEmpty() }
                            Text(stringResource(R.string.onepage_hint), itemWidth.padding(horizontal = 4.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        } else {
                            SearchOptionChips(options) {
                                caseSensitive = it.caseSensitive
                                wholeWord = it.wholeWord
                            }
                        }
                    }
                }
                if (results != null) searchHints(query, results, scripts.found)
                items(entries, key = { it.id }) { entry ->
                    when (entry) {
                        is PageEntry.Heading -> {
                            Heading(entry, scripts, searched = query.isNotBlank(), onEdit = { unfold(entry.key) }, onAdded = { setCollapsed(collapsed - entry.key) }) {
                                if (query.isNotBlank()) {
                                    query = ""
                                    if (only != null) filter = entry.kind.wire
                                    setCollapsed(collapsed - (entry.path.map { ScriptItemKey(entry.kind, it.id) } + ScriptItemKey(entry.kind, 0)).toSet())
                                } else {
                                    setCollapsed(if (entry.collapsed) collapsed - entry.key else collapsed + entry.key)
                                }
                            }
                            if (entry.folder != null && entry.key == open && lua != null) {
                                Unfolded(scripts, entry.key, lua, viewport, onFold = ::fold) { unfolded = null }
                            } else if (!entry.collapsed && entry.count == 0 && query.isBlank()) {
                                Text(
                                    stringResource(if (entry.folder == null) R.string.scripts_none else R.string.scripts_folder_empty),
                                    itemWidth.padding(horizontal = 16.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        is PageEntry.Item -> if (entry.key == open && lua != null) {
                            Unfolded(scripts, entry.key, lua, viewport, onFold = ::fold) { unfolded = null }
                        } else {
                            ListSegment(entry.index, entry.count, itemWidth) {
                                Folded(entry, scripts) { unfold(entry.key) }
                                if (entry.where != null) Places(entry.kind, entry.places, query.length)
                            }
                        }
                    }
                }
                if (query.isBlank() && kinds.any { lists[it] == null }) item(key = "loading") { Hint(stringResource(R.string.scripts_loading)) }
            }
            if (lua?.focused == true) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) { LuaKeys(lua) }
            }
        }
    }
}

/**
 * A chip for all kinds, one for each kind with how many items it has, folders aside, once listed, then the variables'.
 * They scroll sideways on a narrow screen: on more lines, in a wordy language, they would push the items off it.
 */
@Composable
private fun KindFilter(only: ScriptItemKind?, lists: Map<ScriptItemKind, List<ScriptItemNode>?>, onVariables: (() -> Unit)?, onFilter: (ScriptItemKind?) -> Unit) {
    Row(itemWidth.padding(bottom = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(only == null, { onFilter(null) }, label = { Text(stringResource(R.string.onepage_all)) })
        for (kind in ScriptItemKind.entries) {
            val name = stringResource(kind.title)
            val count = lists[kind]?.count { !it.folder }
            FilterChip(only == kind, { onFilter(kind) }, label = { Text(if (count == null) name else stringResource(R.string.scripts_kind_count, name, count)) })
        }
        if (onVariables != null) VariablesChip(onVariables)
    }
}

/**
 * A kind's or a folder's heading: where it is, and a tap hides or shows what is under it, or for a search's result goes
 * there. Its button adds to it, and edits a folder; a folder has its switch.
 */
@Composable
private fun Heading(entry: PageEntry.Heading, scripts: ScriptItemsClient, searched: Boolean, onEdit: () -> Unit, onAdded: () -> Unit, onClick: () -> Unit) {
    val where = whereText(entry.kind, entry.path)
    var menu by remember { mutableStateOf(false) }
    val does = stringResource(
        when {
            searched -> R.string.scripts_open_folder
            entry.collapsed -> R.string.onepage_show
            else -> R.string.onepage_hide
        },
    )
    val canAdd = adds(entry.kind, entry.folder) != Adds.NOTHING
    Row(itemWidth.padding(top = 8.dp).heightIn(min = 48.dp).clickable(onClickLabel = does, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(if (entry.collapsed) R.drawable.ic_chevron_right else R.drawable.ic_expand_more), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        // BITE P2: a heading's text is cut short
        Text(where, Modifier.weight(1f, fill = false).widthIn(max = 40.dp).padding(start = 4.dp).semantics { heading() }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        if (!searched && (canAdd || entry.folder != null)) {
            Box {
                IconButton({ menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.onepage_menu, where)) }
                DropdownMenu(menu, { menu = false }) {
                    AddMenuItems(scripts, entry.kind, entry.folder) {
                        menu = false
                        onAdded()
                    }
                    if (entry.folder != null) {
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
        }
        if (entry.folder != null) {
            val name = nameOf(entry.folder)
            Switch(entry.folder.enabled, { scripts.enable(entry.key, it) }, Modifier.padding(end = 8.dp).semantics { contentDescription = name })
        }
    }
}

/**
 * An item folded: its kind's icon, its name, marked while it has a draft, its summary, then what is wrong with the Lua
 * it was last applied with, or else its [rowStatus], and its switch. A tap unfolds it.
 */
@Composable
private fun Folded(entry: PageEntry.Item, scripts: ScriptItemsClient, onClick: () -> Unit) {
    val node = entry.node
    val name = nameOf(node)
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = stringResource(R.string.scripts_open_item), onClick = onClick)
            .padding(start = (16 + INDENT_DP * minOf(entry.depth, MAX_INDENT)).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(entry.kind.icon), contentDescription = null, Modifier.padding(end = 16.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            if (entry.where != null) Text(whereText(entry.kind, entry.where), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                if (entry.key in scripts.drafts) DraftBadge(Modifier.padding(start = 8.dp))
            }
            if (node.summary.isNotEmpty()) {
                Text(node.summary, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = colors.onSurfaceVariant, maxLines = SUMMARY_LINES, overflow = TextOverflow.Ellipsis)
            }
            val problem = scripts.luaProblem(entry.key)
            if (problem != null) {
                Text(
                    if (problem.line > 0) stringResource(R.string.lua_problem_at, problem.line, problem.message) else problem.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                )
            } else {
                node.rowStatus(0)?.let { StatusText(it) }
            }
        }
        Switch(node.enabled, { scripts.enable(entry.key, it) }, Modifier.semantics { contentDescription = name })
    }
}

/**
 * The item [key] unfolded: why it does not run, its name, what its kind has, what is wrong with its Lua, then its Lua in
 * [lua], with what shows of the page [viewport] high, and for a trigger the rest of its options behind a button. Done
 * folds it, which applies it, as [onFold] does; Discard drops what was changed; Move puts it in another folder; Delete
 * hides it with Undo offered, and [onGone] folds it without applying.
 */
@Composable
private fun Unfolded(scripts: ScriptItemsClient, key: ScriptItemKey, lua: LuaFieldState, viewport: Int, onFold: () -> Unit, onGone: () -> Unit) {
    val page = rememberItemPage(scripts, key)
    val item = page.item
    val colors = MaterialTheme.colorScheme
    var more by rememberSaveable { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val luaFocus = remember { FocusRequester() }
    val name = item?.node?.let { nameOf(it) } ?: stringResource(R.string.scripts_loading)
    Surface(itemWidth.padding(vertical = 4.dp), shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh, border = BorderStroke(1.dp, colors.primary)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (item?.node?.folder == true) R.drawable.ic_folder else key.kind.icon), contentDescription = null, Modifier.padding(end = 12.dp), tint = colors.primary)
                Text(name, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                if (page.dirty) DraftBadge(Modifier.padding(horizontal = 8.dp))
                IconButton(onFold) { Icon(painterResource(R.drawable.ic_expand_less), stringResource(R.string.onepage_fold, name)) }
            }
            if (item == null) {
                ItemNotOpen(page, Modifier.padding(8.dp))
            } else {
                NotRunningBanner(page.error, fieldWidth.padding(top = 8.dp))
                NameField(item, onChange = scripts::edit)
                KindFields(item, page.listed, scripts::edit, triggerOptions = false)
                scripts.luaProblem(key)?.let { found ->
                    Box(fieldWidth.padding(top = 16.dp)) {
                        LuaProblemLine(found) {
                            lua.goTo(found.line)
                            luaFocus.requestFocus()
                        }
                    }
                }
                LuaField(lua, viewport, luaFocus, fieldWidth.padding(top = 16.dp))
                val details = item.details
                if (details is ScriptItemDetails.Trigger) {
                    TextButton({ more = !more }, fieldWidth) {
                        Icon(painterResource(if (more) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null)
                        Text(stringResource(if (more) R.string.onepage_fewer else R.string.onepage_more), Modifier.padding(start = 8.dp))
                    }
                    if (more) TriggerOptions(details) { scripts.edit(item.copy(details = it)) }
                }
            }
            // The buttons go on more lines where they do not fit on one, as on a narrow phone in a wordy language
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), itemVerticalAlignment = Alignment.CenterVertically) {
                if (item != null) {
                    TextButton({
                        scripts.deleteLater(key, name)
                        onGone()
                    }) { Text(stringResource(R.string.scripts_delete, name), color = colors.error) }
                    if (page.targets.isNotEmpty()) TextButton({ moving = true }) { Text(stringResource(R.string.scripts_move, name)) }
                    TextButton({ scripts.discard(key) }, enabled = page.dirty) { Text(stringResource(R.string.scripts_discard)) }
                }
                Button(onFold) { Text(stringResource(R.string.onepage_done)) }
            }
        }
    }

    ItemDialogs(scripts, page, name, moving, leaving = false, onClose = { moving = false }, onBack = {})
}
