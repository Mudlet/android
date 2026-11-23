package org.mudlet.android.ui.scripts.designs

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.mudlet.android.R
import org.mudlet.android.data.Settings.ScriptEditorDesign
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.ScriptItemsClient
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.scripts.Adds
import org.mudlet.android.ui.scripts.LibrarySearchField
import org.mudlet.android.ui.scripts.LuaFunction
import org.mudlet.android.ui.scripts.Places
import org.mudlet.android.ui.scripts.ScriptItemKey
import org.mudlet.android.ui.scripts.ScriptItemScreen
import org.mudlet.android.ui.scripts.ScriptLibraryScreen
import org.mudlet.android.ui.scripts.ScriptSearchResult
import org.mudlet.android.ui.scripts.SearchOptionChips
import org.mudlet.android.ui.scripts.SearchOptions
import org.mudlet.android.ui.scripts.adds
import org.mudlet.android.ui.scripts.newName
import org.mudlet.android.ui.scripts.searchHints
import org.mudlet.android.ui.scripts.searchScripts

/**
 * The script library in [design], beside which an item opens on an expanded screen: the current editor's, or
 * Workbench's or Recipes'. One page is not one of these, as it is a list and its items' pages at once ([OnePage]).
 * The parameters are [ScriptLibraryScreen]'s.
 */
@Composable
internal fun ScriptLibrary(
    design: ScriptEditorDesign,
    scripts: ScriptItemsClient,
    game: String,
    onBack: () -> Unit,
    onOpen: (ScriptItemKey) -> Unit,
    selected: ScriptItemKey?,
    console: () -> List<UiLine>,
    colors: ConsoleColors,
    onVariables: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    when (design) {
        ScriptEditorDesign.WORKBENCH -> WorkbenchLibrary(scripts, game, onBack, onOpen, selected, console, colors, onVariables, actions)
        ScriptEditorDesign.RECIPES -> RecipesLibrary(scripts, game, onBack, onOpen, selected, console, colors, onVariables, actions)
        ScriptEditorDesign.CURRENT, ScriptEditorDesign.ONE_PAGE -> ScriptLibraryScreen(scripts, game, onBack, onOpen, selected, console, colors, onVariables, actions)
    }
}

/**
 * The page of the item [key] in [design]: the current editor's, or Workbench's or Recipes'. One page has none of its
 * own, as its items are changed in its list ([OnePage]).
 */
@Composable
internal fun ScriptItemPage(design: ScriptEditorDesign, scripts: ScriptItemsClient, key: ScriptItemKey, functions: List<LuaFunction>, onBack: () -> Unit, onGame: () -> Unit) {
    when (design) {
        ScriptEditorDesign.WORKBENCH -> WorkbenchItem(scripts, key, functions, onBack, onGame)
        ScriptEditorDesign.RECIPES -> RecipesItem(scripts, key, functions, onBack, onGame)
        ScriptEditorDesign.CURRENT, ScriptEditorDesign.ONE_PAGE -> ScriptItemScreen(scripts, key, functions, onBack, onGame)
    }
}

@get:DrawableRes
internal val ScriptItemKind.icon: Int
    get() = when (this) {
        ScriptItemKind.TRIGGER -> R.drawable.ic_bolt
        ScriptItemKind.ALIAS -> R.drawable.ic_text_fields
        ScriptItemKind.SCRIPT -> R.drawable.ic_code
        ScriptItemKind.TIMER -> R.drawable.ic_schedule
        ScriptItemKind.KEY -> R.drawable.ic_keyboard
        ScriptItemKind.BUTTON -> R.drawable.ic_smart_button
    }

@Composable
internal fun nameOf(node: ScriptItemNode): String = node.name.ifEmpty { stringResource(R.string.scripts_unnamed) }

/**
 * A menu's entries that make what [parent], null for the top level, takes among the items of [kind] ([adds]): an item
 * and a folder, or a toolbar, last in it. None where nothing can be added.
 */
@Composable
internal fun AddMenuItems(scripts: ScriptItemsClient, kind: ScriptItemKind, parent: ScriptItemNode?, onPicked: () -> Unit) {
    val into = parent?.id ?: 0
    when (adds(kind, parent)) {
        Adds.ITEM_AND_FOLDER -> {
            AddMenuItem(stringResource(kind.newName), kind.icon) {
                onPicked()
                scripts.add(kind, into, folder = false, it)
            }
            AddMenuItem(stringResource(R.string.scripts_new_folder), R.drawable.ic_create_new_folder) {
                onPicked()
                scripts.add(kind, into, folder = true, it)
            }
        }

        Adds.TOOLBAR -> AddMenuItem(stringResource(R.string.scripts_new_toolbar), R.drawable.ic_create_new_folder) {
            onPicked()
            scripts.add(kind, into, folder = true, it)
        }

        Adds.NOTHING -> Unit
    }
}

/** The title bar's button that opens the search, or closes it. */
@Composable
internal fun SearchButton(searching: Boolean, onClick: () -> Unit) {
    IconButton(onClick) {
        Icon(painterResource(if (searching) R.drawable.ic_close else R.drawable.ic_search), stringResource(if (searching) R.string.designs_search_close else R.string.scripts_search))
    }
}

/** The search's field, holding [query], and its [options], over what it finds. */
@Composable
internal fun SearchHeader(query: String, onQuery: (String) -> Unit, options: SearchOptions, modifier: Modifier, onOptions: (SearchOptions) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        LibrarySearchField(query, onQuery)
        SearchOptionChips(options, onOptions)
    }
}

/**
 * What [query] finds in [lists], with the search's hints: each item as [row] draws it, given how many items are directly
 * in it, and where in it the text is found under it, then a line under both if [divided].
 */
internal fun LazyListScope.searchResultItems(
    query: String,
    lists: Map<ScriptItemKind, List<ScriptItemNode>?>,
    options: SearchOptions,
    scripts: ScriptItemsClient,
    divided: Boolean = false,
    row: @Composable (result: ScriptSearchResult, key: ScriptItemKey, children: Int) -> Unit,
) {
    val results = searchScripts(query, lists.mapValues { it.value.orEmpty() }, options, scripts.found)
    searchHints(query, results, scripts.found)
    items(results, key = { "${it.kind.wire}/${it.item.id}" }) { result ->
        Column {
            row(result, ScriptItemKey(result.kind, result.item.id), lists[result.kind].orEmpty().count { it.parent == result.item.id })
            Places(result.kind, result.places, query.length)
            if (divided) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
    }
}

// [onClick] is given [name], which the new item is called
@Composable
private fun AddMenuItem(name: String, @DrawableRes icon: Int, onClick: (String) -> Unit) {
    DropdownMenuItem({ Text(name) }, { onClick(name) }, leadingIcon = { Icon(painterResource(icon), contentDescription = null) })
}
