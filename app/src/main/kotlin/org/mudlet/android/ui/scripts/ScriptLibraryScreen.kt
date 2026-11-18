package org.mudlet.android.ui.scripts

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.protocol.ScriptSearchField
import org.mudlet.android.protocol.ScriptSearchMatch
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

// A trigger's patterns are one a line: the first two show under its name
private const val SUMMARY_LINES = 2

/** How long typing pauses before the engine is asked to search. */
private const val SEARCH_PAUSE_MS = 300L

/** How many of the places the engine found in an item show under its row. */
private const val PLACES_SHOWN = 3

/** The plural name of each kind, as the library's chips show it. */
@get:StringRes
internal val ScriptItemKind.title: Int
    get() = when (this) {
        ScriptItemKind.TRIGGER -> R.string.scripts_triggers
        ScriptItemKind.ALIAS -> R.string.scripts_aliases
        ScriptItemKind.SCRIPT -> R.string.scripts_scripts
        ScriptItemKind.TIMER -> R.string.scripts_timers
        ScriptItemKind.KEY -> R.string.scripts_keys
        ScriptItemKind.BUTTON -> R.string.scripts_buttons
    }

/** The name a new item of each kind starts with. */
@get:StringRes
internal val ScriptItemKind.newName: Int
    get() = when (this) {
        ScriptItemKind.TRIGGER -> R.string.scripts_new_trigger
        ScriptItemKind.ALIAS -> R.string.scripts_new_alias
        ScriptItemKind.SCRIPT -> R.string.scripts_new_script
        ScriptItemKind.TIMER -> R.string.scripts_new_timer
        ScriptItemKind.KEY -> R.string.scripts_new_key
        ScriptItemKind.BUTTON -> R.string.scripts_new_button
    }

/**
 * The script library of [game]: a search field, a chip for each kind, then the kind's items one folder at a time under
 * a breadcrumb, each with what it matches, runs at or sends under its name, and its switch. An item, or a folder's Edit,
 * opens through [onOpen]; [selected] is the item shown beside the library. An item with others in it (a trigger that
 * others chain from, a timer with offset timers) is gone into as a folder is. New items go last in the folder shown,
 * and open once made; among the buttons the top level, as a package's folder, takes toolbars, which the buttons go
 * in ([adds]). A search lists the items of every kind it finds the text in, with where each lives and, under it, the
 * first places the engine found it, by the desktop editor's options, each a chip. At the foot, the last of the game's lines, [console] in [colors], go back to the game as [onBack] does. [actions] go first in
 * the title bar.
 * After the kinds' chips, one opens the profile's Lua variables through [onVariables].
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
    onVariables: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    var kindWire by rememberSaveable { mutableStateOf(ScriptItemKind.TRIGGER.wire) }
    val kind = ScriptItemKind.fromWire(kindWire) ?: ScriptItemKind.TRIGGER
    var openFolder by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    val options = SearchOptions(caseSensitive, wholeWord)
    // Asked again once the engine is back, as what was asked of the last link is not answered
    LaunchedEffect(scripts.attaches) { ScriptItemKind.entries.forEach(scripts::ask) }
    LaunchedEffect(query, options, scripts.attaches) {
        // Asked once typing pauses, not at each letter, as each search walks every line of every item
        delay(SEARCH_PAUSE_MS)
        if (query.isNotBlank()) scripts.search(query, options)
    }

    val hidden = scripts.deleting
    val lists = ScriptItemKind.entries.associateWith { k -> scripts.lists[k]?.withoutHidden(k, hidden) }
    val items = lists[kind]
    // A folder deleted, or gone with a new engine, leaves the library at the top level
    val folder = if (items != null && items.none { it.id == openFolder }) 0 else openFolder
    val shownIn = items?.firstOrNull { it.id == folder }
    val adds = adds(kind, shownIn)
    val newItem = stringResource(if (adds == Adds.TOOLBAR) R.string.scripts_new_toolbar else kind.newName)
    val newFolder = stringResource(R.string.scripts_new_folder)
    // Back leaves a search, then goes up a folder, before it leaves the library
    BackHandler(enabled = query.isNotBlank() || folder != 0) {
        if (query.isNotBlank()) query = "" else openFolder = shownIn?.parent ?: 0
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
            actions()
            if (shownIn != null) {
                val edit = if (shownIn.folder) stringResource(R.string.scripts_edit_folder) else stringResource(R.string.scripts_edit_item, shownIn.name)
                IconButton({ onOpen(ScriptItemKey(kind, folder)) }) { Icon(painterResource(R.drawable.ic_edit), edit) }
            }
            // A new item goes after those listed, so not before they are
            if (items != null && adds == Adds.ITEM_AND_FOLDER) {
                IconButton({ scripts.add(kind, folder, folder = true, newFolder) }) {
                    Icon(painterResource(R.drawable.ic_create_new_folder), stringResource(R.string.scripts_add_folder))
                }
            }
            ScriptHelpButton(kind)
        },
        snackbarHost = {
            ScriptNotices(scripts) { added -> if (added.folder) enter(added.key.kind, added.key.id) else onOpen(added.key) }
        },
        floatingActionButton = {
            if (items != null && adds != Adds.NOTHING) {
                ExtendedFloatingActionButton(
                    text = { Text(newItem) },
                    // The button's name for a screen reader: an extended button's text is not read
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = newItem) },
                    onClick = { scripts.add(kind, folder, folder = adds == Adds.TOOLBAR, newItem) },
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
                item(key = "kinds") { KindChips(kind, lists, onVariables) { enter(it, 0) } }
                folderRows(kind, folder, items, selected, scripts, onEnter = { openFolder = it }, onOpen = onOpen)
            } else {
                item(key = "options") {
                    SearchOptionChips(options) {
                        caseSensitive = it.caseSensitive
                        wholeWord = it.wholeWord
                    }
                }
                searchResults(query, options, lists, selected, scripts, onEnter = ::enter, onOpen = onOpen)
            }
        }
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

/** The desktop editor's two search options, each a chip. */
@Composable
private fun SearchOptionChips(options: SearchOptions, onOptions: (SearchOptions) -> Unit) {
    FlowRow(itemWidth.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(options.caseSensitive, { onOptions(options.copy(caseSensitive = !options.caseSensitive)) }, label = { Text(stringResource(R.string.scripts_search_case)) })
        FilterChip(options.wholeWord, { onOptions(options.copy(wholeWord = !options.wholeWord)) }, label = { Text(stringResource(R.string.scripts_search_whole_word)) })
    }
}

/**
 * A chip for each kind, with how many items it has, folders aside, once listed, then one that opens the variables; on as
 * many lines as they need, so all show.
 */
@Composable
private fun KindChips(kind: ScriptItemKind, lists: Map<ScriptItemKind, List<ScriptItemNode>?>, onVariables: (() -> Unit)?, onKind: (ScriptItemKind) -> Unit) {
    FlowRow(itemWidth.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in ScriptItemKind.entries) {
            val name = stringResource(k.title)
            val count = lists[k]?.count { !it.folder }
            FilterChip(
                k == kind,
                { onKind(k) },
                label = { Text(if (count == null) name else stringResource(R.string.scripts_kind_count, name, count)) },
            )
        }
        // Not a kind to pick but a screen of its own, so a chip that goes there rather than one that is on or off
        if (onVariables != null) {
            AssistChip(
                onVariables,
                label = { Text(stringResource(R.string.variables_chip)) },
                trailingIcon = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
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
            ItemRow(item, items.count { it.parent == item.id }, overline = null, scripts, key, onEnter = { onEnter(item.id) }, onOpen = { onOpen(key) })
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
 * An item's row: its name, marked while it has a draft not applied, then its summary (what it matches, its time, key or
 * command), then its [rowStatus], and its switch. A tap opens the item through [onOpen], or goes into one that holds
 * [children] others, or is a folder, through [onEnter].
 */
@Composable
private fun ItemRow(
    item: ScriptItemNode,
    children: Int,
    overline: String?,
    scripts: ScriptItemsClient,
    key: ScriptItemKey,
    onEnter: () -> Unit,
    onOpen: () -> Unit,
) {
    val name = item.name.ifEmpty { stringResource(R.string.scripts_unnamed) }
    val colors = MaterialTheme.colorScheme
    val holds = item.holdsItems(children)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(if (holds) R.string.scripts_open_folder else R.string.scripts_open_item), onClick = if (holds) onEnter else onOpen)
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
            if (!item.folder && item.summary.isNotEmpty()) {
                Text(
                    item.summary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurfaceVariant,
                    maxLines = SUMMARY_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val status = item.rowStatus(children)
            if (status != null) {
                Text(
                    when (status) {
                        is RowStatus.NotRunning -> stringResource(R.string.scripts_not_running, status.error)
                        is RowStatus.Holds -> pluralStringResource(R.plurals.scripts_folder_items, status.items, status.items)
                        RowStatus.Off -> stringResource(R.string.scripts_off)
                        RowStatus.Idle -> stringResource(R.string.scripts_idle)
                        is RowStatus.FromPackage -> stringResource(R.string.scripts_from_package, status.name)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status is RowStatus.NotRunning) colors.error else colors.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Switch(item.enabled, { scripts.enable(key, it) }, Modifier.semantics { contentDescription = name })
    }
}

private fun LazyListScope.searchResults(
    query: String,
    options: SearchOptions,
    lists: Map<ScriptItemKind, List<ScriptItemNode>?>,
    selected: ScriptItemKey?,
    scripts: ScriptItemsClient,
    onEnter: (ScriptItemKind, Int) -> Unit,
    onOpen: (ScriptItemKey) -> Unit,
) {
    val found = scripts.found
    val results = searchScripts(query, lists.mapValues { it.value.orEmpty() }, options, found)
    if (results.isEmpty()) {
        item(key = "none") { Hint(stringResource(R.string.scripts_search_none, query.trim())) }
        return
    }
    if (found != null && found.more && results.any { it.places.isNotEmpty() }) {
        item(key = "more") { Hint(pluralStringResource(R.plurals.scripts_search_more, found.matches.size, found.matches.size)) }
    }
    itemsIndexed(results, key = { _, result -> "${result.kind.wire}/${result.item.id}" }) { i, result ->
        val key = ScriptItemKey(result.kind, result.item.id)
        val where = (listOf(stringResource(result.kind.title)) + result.folders.map { it.name }).joinToString(stringResource(R.string.scripts_path_separator))
        val children = lists[result.kind].orEmpty().count { it.parent == result.item.id }
        ListSegment(i, results.size, itemWidth, selected = key == selected) {
            Column {
                ItemRow(result.item, children, overline = where, scripts, key, onEnter = { onEnter(result.kind, result.item.id) }, onOpen = { onOpen(key) })
                Places(result.kind, result.places, query.length)
            }
        }
    }
}

/**
 * The first of the [places] the engine's search found in an item of [kind], each what it is in and its text with the
 * [length] matched marked, then how many more there are.
 */
@Composable
private fun Places(kind: ScriptItemKind, places: List<ScriptSearchMatch>, length: Int) {
    if (places.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val mark = SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        for (place in places.take(PLACES_SHOWN)) {
            val start = place.start.coerceIn(0, place.text.length)
            val end = (start + length).coerceAtMost(place.text.length)
            // Read as one, so TalkBack says what each text is in with it
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(placeLabel(kind, place), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(
                    buildAnnotatedString {
                        append(place.text)
                        addStyle(mark, start, end)
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val more = places.size - PLACES_SHOWN
        if (more > 0) {
            Text(pluralStringResource(R.plurals.scripts_search_more_places, more, more), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

/** What a [place] is in, as the desktop editor's search results name it, with lines and patterns counted from 1. */
@Composable
private fun placeLabel(kind: ScriptItemKind, place: ScriptSearchMatch): String = when (place.field) {
    ScriptSearchField.NAME -> stringResource(R.string.scripts_place_name)

    ScriptSearchField.COMMAND -> stringResource(R.string.scripts_place_command)

    ScriptSearchField.COMMAND_DOWN -> stringResource(R.string.scripts_place_command_down)

    ScriptSearchField.COMMAND_UP -> stringResource(R.string.scripts_place_command_up)

    ScriptSearchField.PATTERN ->
        if (kind == ScriptItemKind.TRIGGER) stringResource(R.string.scripts_place_pattern_number, place.index + 1) else stringResource(R.string.scripts_place_pattern)

    ScriptSearchField.EVENT -> stringResource(R.string.scripts_place_event, place.index + 1)

    ScriptSearchField.CSS -> stringResource(R.string.scripts_place_css, place.index + 1)

    ScriptSearchField.LUA -> stringResource(R.string.scripts_place_lua, place.index + 1)
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
