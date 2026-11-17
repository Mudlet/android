package org.mudlet.android.ui.scripts

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.mudlet.android.R
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.ListSegment
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.ScriptItemsClient
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.ansiText
import org.mudlet.android.ui.settings.MAX_WIDTH
import org.mudlet.android.ui.settings.Overline

/** The script library's list, for tests to scroll. */
const val SCRIPT_LIBRARY_TAG = "scriptLibrary"

private val itemWidth = Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth()

private const val CONSOLE_STRIP_LINES = 2

/** The plural name of each kind, as the library's chips show it. */
@get:StringRes
internal val ScriptItemKind.title: Int
    get() = when (this) {
        ScriptItemKind.TRIGGER -> R.string.scripts_triggers
        ScriptItemKind.ALIAS -> R.string.scripts_aliases
        ScriptItemKind.SCRIPT -> R.string.scripts_scripts
    }

/** The name a new item of each kind starts with. */
@get:StringRes
internal val ScriptItemKind.newName: Int
    get() = when (this) {
        ScriptItemKind.TRIGGER -> R.string.scripts_new_trigger
        ScriptItemKind.ALIAS -> R.string.scripts_new_alias
        ScriptItemKind.SCRIPT -> R.string.scripts_new_script
    }

/**
 * The script library of [game]: a search field, a chip for each kind, then the kind's items one folder at a time under
 * a breadcrumb, each with its switch. An item, or a folder's Edit, opens through [onOpen]; [selected] is the item shown
 * beside the library. New items go last in the folder shown, and open once made. A search lists the items of every
 * kind whose name it finds, with where each lives. At the foot, the last of the game's lines, [console] in [colors], go
 * back to the game as [onBack] does.
 */
@Composable
internal fun ScriptLibraryScreen(
    scripts: ScriptItemsClient,
    game: String,
    onBack: () -> Unit,
    onOpen: (ScriptItemKey) -> Unit,
    selected: ScriptItemKey? = null,
    console: () -> List<UiLine> = { emptyList() },
    colors: ConsoleColors = ConsoleColors(),
) {
    var kindWire by rememberSaveable { mutableStateOf(ScriptItemKind.TRIGGER.wire) }
    val kind = ScriptItemKind.fromWire(kindWire) ?: ScriptItemKind.TRIGGER
    var openFolder by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    // Asked again once the engine is back, as what was asked of the last link is not answered
    LaunchedEffect(scripts.attaches) { ScriptItemKind.entries.forEach(scripts::ask) }

    val hidden = scripts.deleting
    val lists = ScriptItemKind.entries.associateWith { k -> scripts.lists[k]?.withoutHidden(hidden.filter { it.kind == k }.map { it.id }.toSet()) }
    val items = lists[kind]
    // A folder deleted, or gone with a new engine, leaves the library at the top level
    val folder = if (items != null && items.none { it.id == openFolder }) 0 else openFolder
    val newItem = stringResource(kind.newName)
    val newFolder = stringResource(R.string.scripts_new_folder)
    // Back leaves a search, then goes up a folder, before it leaves the library
    BackHandler(enabled = query.isNotBlank() || folder != 0) {
        if (query.isNotBlank()) query = "" else openFolder = items?.firstOrNull { it.id == folder }?.parent ?: 0
    }

    fun enter(of: ScriptItemKind, id: Int) {
        kindWire = of.wire
        query = ""
        openFolder = id
    }

    MudletScaffold(
        stringResource(R.string.scripts_title, game),
        onBack = onBack,
        actions = {
            if (folder != 0) {
                IconButton({ onOpen(ScriptItemKey(kind, folder)) }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.scripts_edit_folder)) }
            }
            // A new item goes after those listed, so not before they are
            if (items != null) {
                IconButton({ scripts.add(kind, folder, folder = true, newFolder) }) {
                    Icon(painterResource(R.drawable.ic_create_new_folder), stringResource(R.string.scripts_add_folder))
                }
            }
        },
        snackbarHost = {
            ScriptNotices(scripts) { added -> if (added.folder) enter(added.key.kind, added.key.id) else onOpen(added.key) }
        },
        floatingActionButton = {
            if (items != null) {
                ExtendedFloatingActionButton(
                    text = { Text(newItem) },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    onClick = { scripts.add(kind, folder, folder = false, newItem) },
                )
            }
        },
        bottomBar = { ConsoleStrip(console, colors, onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag(SCRIPT_LIBRARY_TAG),
            // Room at the foot for the floating button over the last row
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "search") { SearchField(query) { query = it } }
            if (query.isBlank()) {
                item(key = "kinds") { KindChips(kind, lists) { enter(it, 0) } }
                folderRows(kind, folder, items, selected, scripts, onEnter = { openFolder = it }, onOpen = onOpen)
            } else {
                searchResults(query, lists, selected, scripts) { result ->
                    if (result.item.folder) enter(result.kind, result.item.id) else onOpen(ScriptItemKey(result.kind, result.item.id))
                }
            }
        }
    }
}

// The items left once those deleted, and what is in them, are hidden
private fun List<ScriptItemNode>.withoutHidden(ids: Set<Int>): List<ScriptItemNode> {
    if (ids.isEmpty()) return this
    val gone = HashSet(ids)
    // Depth first, so each folder comes before what is in it
    return filter { item ->
        val hidden = item.id in gone || item.parent in gone
        if (hidden) gone.add(item.id)
        !hidden
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        query,
        onQuery,
        itemWidth.padding(vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.scripts_search)) },
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

/** A chip for each kind, with how many items it has, folders aside, once listed. */
@Composable
private fun KindChips(kind: ScriptItemKind, lists: Map<ScriptItemKind, List<ScriptItemNode>?>, onKind: (ScriptItemKind) -> Unit) {
    Row(itemWidth.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in ScriptItemKind.entries) {
            val name = stringResource(k.title)
            val count = lists[k]?.count { !it.folder }
            FilterChip(
                k == kind,
                { onKind(k) },
                label = { Text(if (count == null) name else stringResource(R.string.scripts_kind_count, name, count)) },
            )
        }
    }
}

private fun LazyListScope.folderRows(
    kind: ScriptItemKind,
    folder: Int,
    items: List<ScriptItemNode>?,
    selected: ScriptItemKey?,
    scripts: ScriptItemsClient,
    onEnter: (Int) -> Unit,
    onOpen: (ScriptItemKey) -> Unit,
) {
    if (items == null) {
        item(key = "loading") { Hint(stringResource(R.string.scripts_loading)) }
        return
    }
    val path = items.pathTo(folder)
    item(key = "crumbs") { Breadcrumb(kind, path, onEnter) }
    val shown = items.childrenOf(folder)
    if (shown.isEmpty()) {
        item(key = "empty") { Hint(stringResource(if (folder == 0) R.string.scripts_none else R.string.scripts_folder_empty)) }
        return
    }
    itemsIndexed(shown, key = { _, item -> "${kind.wire}/${item.id}" }) { i, item ->
        val key = ScriptItemKey(kind, item.id)
        ListSegment(i, shown.size, itemWidth, selected = key == selected) {
            ItemRow(item, items.count { it.parent == item.id }, overline = null, scripts, key) { if (item.folder) onEnter(item.id) else onOpen(key) }
        }
    }
}

/** Where the folder shown is: the kind, then each folder down to it, each a button that goes there but the last. */
@Composable
private fun Breadcrumb(kind: ScriptItemKind, path: List<ScriptItemNode>, onEnter: (Int) -> Unit) {
    val top = stringResource(R.string.scripts_all, stringResource(kind.title))
    if (path.isEmpty()) {
        Text(top, itemWidth.padding(start = 16.dp, top = 8.dp, bottom = 8.dp).semantics { heading() }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        return
    }
    Row(itemWidth.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        TextButton({ onEnter(0) }) { Text(top) }
        for ((i, crumb) in path.withIndex()) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
            if (i == path.lastIndex) {
                Text(
                    crumb.name,
                    Modifier.padding(horizontal = 12.dp).semantics { heading() },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                )
            } else {
                TextButton({ onEnter(crumb.id) }) { Text(crumb.name) }
            }
        }
    }
}

/**
 * An item's row: its name, marked while it has a draft not applied, then why it does not run, or how many items a
 * folder has, or that it is off or not running though on (in a folder that is off, or new and not applied yet), or the
 * package it came with; and its switch. A tap opens the item, or goes into the folder.
 */
@Composable
private fun ItemRow(item: ScriptItemNode, children: Int, overline: String?, scripts: ScriptItemsClient, key: ScriptItemKey, onClick: () -> Unit) {
    val name = item.name.ifEmpty { stringResource(R.string.scripts_unnamed) }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(if (item.folder) R.string.scripts_open_folder else R.string.scripts_open_item), onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.folder) Icon(painterResource(R.drawable.ic_folder), contentDescription = null, Modifier.padding(end = 16.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            if (overline != null) Overline(overline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                if (key in scripts.drafts) {
                    Surface(Modifier.padding(start = 8.dp), shape = MaterialTheme.shapes.extraSmall, color = colors.tertiaryContainer) {
                        Text(
                            stringResource(R.string.scripts_draft),
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = colors.onTertiaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            val status = when {
                item.error.isNotEmpty() -> stringResource(R.string.scripts_not_running, item.error)
                item.folder -> pluralStringResource(R.plurals.scripts_folder_items, children, children)
                !item.enabled -> stringResource(R.string.scripts_off)
                !item.active -> stringResource(R.string.scripts_idle)
                item.packageName.isNotEmpty() -> stringResource(R.string.scripts_from_package, item.packageName)
                else -> null
            }
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.error.isNotEmpty()) colors.error else colors.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Switch(item.enabled, { scripts.enable(key, it) }, Modifier.semantics { contentDescription = name })
    }
}

private fun LazyListScope.searchResults(
    query: String,
    lists: Map<ScriptItemKind, List<ScriptItemNode>?>,
    selected: ScriptItemKey?,
    scripts: ScriptItemsClient,
    onPick: (ScriptSearchResult) -> Unit,
) {
    val results = searchScripts(query, lists.mapValues { it.value.orEmpty() })
    if (results.isEmpty()) {
        item(key = "none") { Hint(stringResource(R.string.scripts_search_none, query.trim())) }
        return
    }
    itemsIndexed(results, key = { _, result -> "${result.kind.wire}/${result.item.id}" }) { i, result ->
        val key = ScriptItemKey(result.kind, result.item.id)
        val where = (listOf(stringResource(result.kind.title)) + result.folders.map { it.name }).joinToString(stringResource(R.string.scripts_path_separator))
        val children = lists[result.kind].orEmpty().count { it.parent == result.item.id }
        ListSegment(i, results.size, itemWidth, selected = key == selected) {
            ItemRow(result.item, children, overline = where, scripts, key) { onPick(result) }
        }
    }
}

/** The last of the game's lines, as the console shows them: the way back to the game, kept in sight. */
@Composable
private fun ConsoleStrip(console: () -> List<UiLine>, colors: ConsoleColors, onGame: () -> Unit) {
    // Read here, so that a new line draws the strip again and not the library
    val lines = console().takeLast(CONSOLE_STRIP_LINES)
    if (lines.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().background(colors.background)
            .clickable(onClickLabel = stringResource(R.string.scripts_to_game), role = Role.Button, onClick = onGame)
            .navigationBarsPadding().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        for (line in lines) {
            Text(
                ansiText(line, colors),
                color = colors.foreground,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, itemWidth.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
}
