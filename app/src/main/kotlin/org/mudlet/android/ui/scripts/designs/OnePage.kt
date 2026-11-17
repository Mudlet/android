package org.mudlet.android.ui.scripts.designs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.ListSegment
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
import org.mudlet.android.ui.scripts.SCRIPT_LIBRARY_TAG
import org.mudlet.android.ui.scripts.ScriptItemKey
import org.mudlet.android.ui.scripts.ScriptNotices
import org.mudlet.android.ui.scripts.SearchField
import org.mudlet.android.ui.scripts.TriggerCommand
import org.mudlet.android.ui.scripts.TriggerOptions
import org.mudlet.android.ui.scripts.TriggerPatterns
import org.mudlet.android.ui.scripts.fieldWidth
import org.mudlet.android.ui.scripts.itemWidth
import org.mudlet.android.ui.scripts.lineCount
import org.mudlet.android.ui.scripts.newName
import org.mudlet.android.ui.scripts.searchScripts
import org.mudlet.android.ui.scripts.title

/** What the page lists, in order: a heading for each kind and each folder, and the items under it. */
internal sealed interface PageEntry {
    val id: String

    /** The heading of [folder] of [kind], or of the kind's top level for none, reached through [path]; [count] items are right under it. */
    data class Heading(val kind: ScriptItemKind, val folder: ScriptItemNode?, val path: List<ScriptItemNode>, val collapsed: Boolean, val count: Int) : PageEntry {
        val key get() = ScriptItemKey(kind, folder?.id ?: 0)
        override val id get() = "h/${key.encode()}"
    }

    /** The item [node], [index] of the [count] under its heading; [where] is given for a search's result. */
    data class Item(val kind: ScriptItemKind, val node: ScriptItemNode, val index: Int, val count: Int, val where: List<ScriptItemNode>? = null) : PageEntry {
        val key get() = ScriptItemKey(kind, node.id)
        override val id get() = "i/${key.encode()}"
    }
}

/**
 * The page's entries for [kinds]: each kind's top level, then each folder as the engine lists them, depth first, with
 * the items right under it; the folders in [collapsed] hide theirs and the folders in them.
 */
internal fun pageEntries(lists: Map<ScriptItemKind, List<ScriptItemNode>?>, kinds: List<ScriptItemKind>, collapsed: Set<ScriptItemKey>): List<PageEntry> {
    val entries = ArrayList<PageEntry>()
    for (kind in kinds) {
        val byParent = lists[kind]?.groupBy { it.parent } ?: continue
        // A parent's id names a listed folder, but a loop would never end, so each folder is listed once
        val seen = HashSet<Int>()

        fun section(folder: ScriptItemNode?, path: List<ScriptItemNode>) {
            val id = folder?.id ?: 0
            if (!seen.add(id)) return
            val children = byParent[id].orEmpty()
            val items = children.filter { !it.folder }
            val closed = ScriptItemKey(kind, id) in collapsed
            entries.add(PageEntry.Heading(kind, folder, path, closed, items.size))
            if (closed) return
            items.forEachIndexed { i, node -> entries.add(PageEntry.Item(kind, node, i, items.size)) }
            for (sub in children) if (sub.folder) section(sub, path + sub)
        }
        section(null, emptyList())
    }
    return entries
}

/**
 * Design B, the whole editor of [game] on one page: every kind's items in one list under headings that name their
 * folder, narrowed by a chip for each kind and a search field. A tap unfolds an item where it is, with its fields and
 * its Lua; folding it, unfolding another or leaving saves what was changed, with Undo offered, so there is no button
 * to save with. While the Lua has the keyboard, the keys that type Lua's symbols are over it.
 */
@Composable
internal fun OnePage(scripts: ScriptItemsClient, game: String, functions: List<LuaFunction>, onBack: () -> Unit, console: () -> List<UiLine>, colors: ConsoleColors) {
    var filter by rememberSaveable { mutableStateOf("") }
    val kinds = ScriptItemKind.fromWire(filter)?.let(::listOf) ?: ScriptItemKind.entries
    var query by rememberSaveable { mutableStateOf("") }
    // The folders whose items are hidden, as encoded keys with a space between
    var closed by rememberSaveable { mutableStateOf("") }
    val collapsed = closed.split(' ').mapNotNull(ScriptItemKey::decode).toSet()
    var unfolded by rememberSaveable { mutableStateOf<String?>(null) }
    val open = unfolded?.let(ScriptItemKey::decode)
    var adding by remember { mutableStateOf(false) }
    var lua by remember { mutableStateOf<LuaSession?>(null) }
    val lists = visibleLists(scripts)
    val list = rememberLazyListState()

    fun fold() {
        open?.let { if (it in scripts.drafts) scripts.apply(it) }
        unfolded = null
    }

    fun unfold(key: ScriptItemKey) {
        if (key == open) return
        fold()
        unfolded = key.encode()
    }

    fun setCollapsed(key: ScriptItemKey, to: Boolean) {
        closed = (if (to) collapsed + key else collapsed - key).joinToString(" ") { it.encode() }
    }

    val leave = {
        fold()
        onBack()
    }
    BackHandler(enabled = open != null || query.isNotBlank()) { if (open != null) fold() else query = "" }
    // Whatever way the page goes, what was changed in the item unfolded is saved
    val last by rememberUpdatedState(open)
    DisposableEffect(scripts) { onDispose { last?.let { if (it in scripts.drafts) scripts.apply(it) } } }

    val entries = if (query.isBlank()) {
        pageEntries(lists, kinds, collapsed)
    } else {
        searchScripts(query, lists.mapValues { it.value.orEmpty() }).map { result ->
            if (result.item.folder) {
                PageEntry.Heading(result.kind, result.item, result.folders + result.item, collapsed = true, count = 0)
            } else {
                PageEntry.Item(result.kind, result.item, 0, 1, result.folders)
            }
        }
    }
    // The item unfolded goes to the top of the page, as it is taller than its row was
    val at = entries.indexOfFirst { (it is PageEntry.Item && it.key == open) || (it is PageEntry.Heading && it.folder != null && it.key == open) }
    LaunchedEffect(unfolded, at >= 0) { if (at >= 0) list.animateScrollToItem(at + FIXED_ROWS) }

    MudletScaffold(
        stringResource(R.string.scripts_title, game),
        onBack = leave,
        actions = {
            Box {
                IconButton({ adding = true }) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.design_add)) }
                DropdownMenu(adding, { adding = false }) {
                    for (kind in ScriptItemKind.entries) {
                        val name = stringResource(kind.newName)
                        DropdownMenuItem(
                            { Text(name) },
                            {
                                adding = false
                                setCollapsed(ScriptItemKey(kind, 0), false)
                                scripts.add(kind, 0, folder = false, name)
                            },
                            leadingIcon = { Icon(painterResource(kind.icon), contentDescription = null) },
                            enabled = lists[kind] != null,
                        )
                    }
                }
            }
        },
        snackbarHost = {
            ScriptNotices(scripts) { added ->
                query = ""
                if (filter.isNotEmpty()) filter = added.key.kind.wire
                unfold(added.key)
            }
        },
        bottomBar = {
            val typing = lua?.takeIf { it.focused }
            if (typing != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.navigationBarsPadding().imePadding()) {
                        LuaCompletions(typing)
                        HorizontalDivider()
                        LuaKeys(typing)
                    }
                }
            } else {
                GameStrip(console, colors, Modifier.navigationBarsPadding(), onGame = leave)
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().testTag(SCRIPT_LIBRARY_TAG),
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "search") { SearchField(query) { query = it } }
            item(key = "kinds") {
                Row(itemWidth.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter.isEmpty(), { filter = "" }, label = { Text(stringResource(R.string.design_b_all)) })
                    for (kind in ScriptItemKind.entries) {
                        val name = stringResource(kind.title)
                        val count = lists[kind]?.count { !it.folder }
                        FilterChip(filter == kind.wire, { filter = kind.wire }, label = { Text(if (count == null) name else stringResource(R.string.scripts_kind_count, name, count)) })
                    }
                }
            }
            item(key = "hint") {
                Text(stringResource(R.string.design_b_hint), itemWidth.padding(horizontal = 4.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (query.isNotBlank() && entries.isEmpty()) item(key = "none") { Hint(stringResource(R.string.scripts_search_none, query.trim())) }
            if (query.isBlank() && kinds.any { lists[it] == null }) item(key = "loading") { Hint(stringResource(R.string.scripts_loading)) }
            items(entries, key = { it.id }) { entry ->
                when (entry) {
                    is PageEntry.Heading -> {
                        Heading(entry, scripts, searched = query.isNotBlank(), onEdit = { unfold(entry.key) }, onAdded = { setCollapsed(entry.key, false) }) {
                            if (query.isNotBlank()) {
                                query = ""
                                if (filter.isNotEmpty()) filter = entry.kind.wire
                                closed = (collapsed - (entry.path.map { ScriptItemKey(entry.kind, it.id) } + ScriptItemKey(entry.kind, 0)).toSet()).joinToString(" ") { it.encode() }
                            } else {
                                setCollapsed(entry.key, !entry.collapsed)
                            }
                        }
                        if (entry.folder != null && entry.key == open) {
                            Unfolded(scripts, entry.key, functions, onLua = { lua = it }, onFold = ::fold) { unfolded = null }
                        } else if (!entry.collapsed && entry.count == 0 && entry.folder != null) {
                            Text(stringResource(R.string.scripts_folder_empty), itemWidth.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    is PageEntry.Item -> if (entry.key == open) {
                        Unfolded(scripts, entry.key, functions, onLua = { lua = it }, onFold = ::fold) { unfolded = null }
                    } else {
                        ListSegment(entry.index, entry.count, itemWidth) { Folded(entry, scripts) { unfold(entry.key) } }
                    }
                }
            }
        }
    }
}

// The rows over the page's entries: the search field, the chips and the hint
private const val FIXED_ROWS = 3

/** A kind's or a folder's heading: where it is, a tap hides or shows what is under it; its button adds to it, and a folder has its switch. */
@Composable
private fun Heading(entry: PageEntry.Heading, scripts: ScriptItemsClient, searched: Boolean, onEdit: () -> Unit, onAdded: () -> Unit, onClick: () -> Unit) {
    val where = (listOf(stringResource(entry.kind.title)) + entry.path.map { nameOf(it) }).joinToString(stringResource(R.string.scripts_path_separator))
    var menu by remember { mutableStateOf(false) }
    val does = stringResource(
        when {
            searched -> R.string.scripts_open_folder
            entry.collapsed -> R.string.design_b_show
            else -> R.string.design_b_hide
        },
    )
    Row(itemWidth.padding(top = 8.dp).heightIn(min = 48.dp).clickable(onClickLabel = does, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(if (entry.collapsed) R.drawable.ic_chevron_right else R.drawable.ic_expand_more), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(where, Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!searched) {
            Box {
                IconButton({ menu = true }) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.design_b_add_to, where)) }
                DropdownMenu(menu, { menu = false }) {
                    AddMenuItems(scripts, entry.kind, entry.key.id) {
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

/** An item folded: its kind's icon, its name, what is to say of it, and its switch. A tap unfolds it. */
@Composable
private fun Folded(entry: PageEntry.Item, scripts: ScriptItemsClient, onClick: () -> Unit) {
    val node = entry.node
    val name = nameOf(node)
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = stringResource(R.string.scripts_open_item), onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(entry.kind.icon), contentDescription = null, Modifier.padding(end = 16.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            if (entry.where != null) {
                val where = (listOf(stringResource(entry.kind.title)) + entry.where.map { it.name }).joinToString(stringResource(R.string.scripts_path_separator))
                Text(where, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                if (entry.key in scripts.drafts) DraftBadge(Modifier.padding(start = 8.dp))
            }
            val status = statusOf(node, 0)
            if (status != null) Text(status, style = MaterialTheme.typography.bodyMedium, color = if (node.error.isNotEmpty()) colors.error else colors.onSurfaceVariant, maxLines = 2)
        }
        Switch(node.enabled, { scripts.enable(entry.key, it) }, Modifier.semantics { contentDescription = name })
    }
}

/**
 * The item [key] unfolded: its name, what its kind matches and sends, its Lua, and for a trigger the rest of its options
 * behind a button. Done folds it, which saves it, as [onFold] does; Discard drops what was changed; Delete hides it with
 * Undo offered, and [onGone] folds it without saving.
 */
@Composable
private fun Unfolded(scripts: ScriptItemsClient, key: ScriptItemKey, functions: List<LuaFunction>, onLua: (LuaSession?) -> Unit, onFold: () -> Unit, onGone: () -> Unit) {
    // Asked again each time, as a script may have changed it since, and once the engine is back; a draft stays over what comes
    LaunchedEffect(key, scripts.attaches) { scripts.open(key) }
    val saved = scripts.saved[key]
    val draft = scripts.drafts[key]
    val item = draft ?: saved
    val colors = MaterialTheme.colorScheme
    Surface(itemWidth.padding(vertical = 4.dp), shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh, border = BorderStroke(1.dp, colors.primary)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val name = item?.node?.let { nameOf(it) } ?: stringResource(R.string.scripts_loading)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (item?.node?.folder == true) R.drawable.ic_folder else key.kind.icon), contentDescription = null, Modifier.padding(end = 12.dp), tint = colors.primary)
                Text(name, Modifier.weight(1f, fill = false).semantics { heading() }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (draft != null) DraftBadge(Modifier.padding(start = 8.dp))
                Box(Modifier.weight(1f))
                IconButton(onFold) { Icon(painterResource(R.drawable.ic_expand_less), stringResource(R.string.design_b_fold, name)) }
            }
            val refusal = scripts.unopened[key]
            when {
                item != null -> UnfoldedFields(scripts, item, saved?.node?.error.orEmpty(), functions, onLua)
                refusal != null -> Text(stringResource(R.string.scripts_refused_open, refusal), Modifier.padding(8.dp), color = colors.error)
                else -> Text(stringResource(R.string.scripts_loading), Modifier.padding(8.dp), color = colors.onSurfaceVariant)
            }
            // The buttons go on a second line where they do not fit on one, as on a narrow phone in a wordy language
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), itemVerticalAlignment = Alignment.CenterVertically) {
                if (item != null) {
                    TextButton({
                        scripts.deleteLater(key, name)
                        onGone()
                    }) { Text(stringResource(R.string.scripts_delete, name), color = colors.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    TextButton({ scripts.discard(key) }, enabled = draft != null) { Text(stringResource(R.string.scripts_discard)) }
                }
                Button(onFold) { Text(stringResource(R.string.design_b_done)) }
            }
        }
    }
}

@Composable
private fun UnfoldedFields(scripts: ScriptItemsClient, item: ScriptItem, error: String, functions: List<LuaFunction>, onLua: (LuaSession?) -> Unit) {
    var more by rememberSaveable { mutableStateOf(false) }
    val session = rememberLuaSession(scripts, item, functions)
    DisposableEffect(session) {
        onLua(session)
        onDispose { onLua(null) }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        NotRunning(error, Modifier.fillMaxWidth())
        NameField(item, onChange = scripts::edit)
        val details = item.details
        when (details) {
            is ScriptItemDetails.Trigger -> {
                val change = { to: ScriptItemDetails.Trigger -> scripts.edit(item.copy(details = to)) }
                TriggerPatterns(details, change)
                TriggerCommand(details, change)
            }

            is ScriptItemDetails.Alias -> {
                val change = { to: ScriptItemDetails.Alias -> scripts.edit(item.copy(details = to)) }
                AliasPattern(details, change)
                AliasCommand(details, change)
            }

            is ScriptItemDetails.Script -> EventHandlers(details) { scripts.edit(item.copy(details = it)) }
        }
        SectionHeader(stringResource(R.string.scripts_code), fieldWidth)
        InlineLua(session)
        if (details is ScriptItemDetails.Trigger) {
            TextButton({ more = !more }, fieldWidth) {
                Icon(painterResource(if (more) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null)
                Text(stringResource(if (more) R.string.design_b_fewer else R.string.design_b_more), Modifier.padding(start = 8.dp))
            }
            if (more) TriggerOptions(details) { scripts.edit(item.copy(details = it)) }
        }
    }
}

/**
 * The item's Lua in the page: its lines numbered and none wrapped, as tall as the code is, so that it is the page that
 * scrolls. What the engine finds wrong is over it; the keys that type Lua's symbols are the page's, over the keyboard.
 */
@Composable
private fun InlineLua(session: LuaSession) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = colors.onSurface)
    val label = stringResource(R.string.lua_label)
    val value = session.value
    Column(fieldWidth) {
        session.problem?.let { found -> Problem(found) { session.goTo(found.line) } }
        Surface(shape = MaterialTheme.shapes.small, color = colors.surfaceContainerLowest, border = BorderStroke(1.dp, if (session.focused) colors.primary else colors.outline)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(horizontal = 8.dp).clearAndSetSemantics {}, horizontalAlignment = Alignment.End) {
                    // As tall as the label beside it, so that each number is beside its line
                    Text("", style = MaterialTheme.typography.labelMedium)
                    Text((1..lineCount(value.text)).joinToString("\n"), style = style, color = colors.onSurfaceVariant, textAlign = TextAlign.End)
                }
                BoxWithConstraints(Modifier.weight(1f)) {
                    // At least as wide as its place, so that a tap beside a short line reaches the field
                    val width = maxWidth
                    BasicTextField(
                        value,
                        session::type,
                        Modifier.horizontalScroll(rememberScrollState()).focusRequester(session.focus).onFocusChanged { session.focused = it.isFocused }.testTag(LUA_EDITOR_TAG),
                        textStyle = style,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        cursorBrush = SolidColor(colors.primary),
                        decorationBox = { field ->
                            Column(Modifier.widthIn(min = width).heightIn(min = 96.dp).padding(end = 12.dp)) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                                field()
                            }
                        },
                    )
                }
            }
        }
    }
}
