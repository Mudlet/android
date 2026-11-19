package org.mudlet.android.ui.settings

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ListSegment
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.SectionHeader

/** The settings hub's test tag. */
const val SETTINGS_HUB_TAG = "settingsHub"

/**
 * A section of the settings hub: [game]'s categories, or, where it is null, the app's values of the game categories
 * ([forGame], what games added in the app start with) or of the app's own.
 */
private data class HubSection(val game: String?, val forGame: Boolean) {
    val categories get() = SettingsCategory.entries.filter(::holds)

    fun holds(category: SettingsCategory) = category.forGame == forGame && (game != null || !category.sharedByGames) && category.offered()

    // For rememberSaveable, as the search's filter
    val key get() = game?.let { "g:$it" } ?: if (forGame) "new" else "app"

    @Composable
    fun title(): String = game ?: stringResource(if (forGame) R.string.settings_new_games else R.string.settings_app)

    // The breadcrumb's first part: shorter than the title for the app's own
    fun place(resources: Resources): String = game ?: resources.getString(if (forGame) R.string.settings_new_games else R.string.settings_app_place)
}

/**
 * The settings hub: a search field, then [game]'s categories, then the app's. Opened from the profile list, where
 * [game] is null, it has each open game's categories, and those of the values new games start with. A category opens
 * its page through [onOpen]; [selected] is the page shown beside the hub. A search lists every setting it finds with
 * where it lives, a switch turning where it is found and any other opening its page at it.
 */
@Composable
fun SettingsScreen(game: String?, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit, selected: SettingsPage? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val sections = hubSections(game)
    val resources = LocalResources.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    MudletScaffold(stringResource(R.string.settings_title), onBack = onBack, snackbarHost = { SettingsNotices(sections.mapNotNull { it.game }) }) { padding ->
        // The field stays above the list: as its first row, it is half hidden when the hub comes back scrolled to a
        // category, and TalkBack finds no label on what shows of it
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            SearchField(query) {
                // Results replace the categories: shown from the first, not from where the categories were scrolled
                if (it.isBlank() != query.isBlank()) scope.launch { list.scrollToItem(0) }
                query = it
            }
            SettingsList(query, filter, sections, selected, resources, list, onOpen) { filter = it }
        }
    }
}

@Composable
private fun SettingsList(
    query: String,
    filter: String?,
    sections: List<HubSection>,
    selected: SettingsPage?,
    resources: Resources,
    list: LazyListState,
    onOpen: (SettingsPage) -> Unit,
    onFilter: (String?) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag(SETTINGS_HUB_TAG),
        state = list,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (query.isBlank()) {
            for (section in sections) hubSection(section, selected, onOpen)
        } else {
            val shown = sections.filter { filter == null || it.key == filter }
            item(key = "filters") { Filters(sections, filter, onFilter) }
            searchResults(query, shown, resources, onOpen)
        }
    }
}

// The settings opened from a game have that game's categories; from the profile list, every open game's
@Composable
private fun hubSections(game: String?): List<HubSection> {
    val games = if (game != null) listOf(game) else EngineClient.sessions.map { it.name }
    val open = games.filter { GameSettings(it).available }.map { HubSection(it, forGame = true) }
    val newGames = if (game == null) listOf(HubSection(null, forGame = true)) else emptyList()
    return open + newGames + HubSection(null, forGame = false)
}

private val itemWidth = Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth()

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        query,
        onQuery,
        // Over the list: Compose gives a row the list's top cuts a touch area 24 dp above the cut, and takes it out of
        // whatever is drawn before the list, which left accessibility a field 40 dp high
        Modifier.zIndex(1f).padding(horizontal = 16.dp).then(itemWidth).padding(vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.settings_search)) },
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

private fun LazyListScope.hubSection(section: HubSection, selected: SettingsPage?, onOpen: (SettingsPage) -> Unit) {
    item(key = "header-${section.key}") { SectionHeader(section.title(), itemWidth) }
    val categories = section.categories
    itemsIndexed(categories, key = { _, category -> "${section.key}/${category.name}" }) { i, category ->
        val page = SettingsPage(category, section.game)
        ListSegment(i, categories.size, itemWidth, selected = selected?.let { it.category == category && it.game == section.game } == true) {
            val title = stringResource(category.title)
            val summary = stringResource(category.summary)
            val reading = stringResource(R.string.settings_category_reading, title, summary)
            ListItem(
                // Hidden rather than cleared, so the tests still find them
                headlineContent = { Text(title, Modifier.semantics { hideFromAccessibility() }) },
                supportingContent = { Text(summary, Modifier.semantics { hideFromAccessibility() }) },
                leadingContent = { Icon(painterResource(category.icon), contentDescription = null) },
                trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                // Read from the row itself: the list's bottom can cut a row above its texts, and Compose then hands
                // accessibility the row without them, a sliver with no label
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.settings_open)) { onOpen(page) }.semantics { contentDescription = reading },
                // The segment under it is the container, tinted when selected
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

/** A chip for every section, and one for all of them, that narrow the search to it. */
@Composable
private fun Filters(sections: List<HubSection>, filter: String?, onFilter: (String?) -> Unit) {
    Row(itemWidth.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(filter == null, { onFilter(null) }, label = { Text(stringResource(R.string.settings_search_all)) })
        for (section in sections) {
            FilterChip(filter == section.key, { onFilter(section.key) }, label = { Text(section.title()) })
        }
    }
}

private class SearchResult(val section: HubSection, val entry: SettingEntry) {
    val page get() = SettingsPage(entry.category, section.game, entry.key)
}

private fun LazyListScope.searchResults(query: String, sections: List<HubSection>, resources: Resources, onOpen: (SettingsPage) -> Unit) {
    val results = sections.flatMap { section ->
        settingsCatalogue.filter { section.holds(it.category) && it.shownFor(section.game) }
            .filter { it.shownWith(SettingsPage(it.category, section.game).owner) }
            .filter { searchMatches(query, it.searchTexts(resources) + section.place(resources)) }
            .map { SearchResult(section, it) }
    }
    if (results.isEmpty()) {
        item(key = "none") {
            Text(
                stringResource(R.string.settings_search_none, query.trim()),
                itemWidth.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }
    itemsIndexed(results, key = { _, result -> "${result.section.key}/${result.entry.key}" }) { i, result ->
        val crumb = stringResource(R.string.settings_breadcrumb, result.section.place(resources), stringResource(result.entry.category.title))
        ListSegment(i, results.size, itemWidth) {
            when (val entry = result.entry) {
                is SettingEntry.Switch -> SwitchRow(entry, result.page.owner, overline = crumb)

                is SettingEntry.Action -> result.section.game?.let { ActionRow(entry, it, overline = crumb) }

                else -> ListItem(
                    headlineContent = { Text(stringResource(entry.label)) },
                    overlineContent = { Overline(crumb) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                    modifier = Modifier.clickable(onClickLabel = stringResource(R.string.settings_open)) { onOpen(result.page) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

// The texts search looks in: the category's title too, so "accessibility" lists that page's settings
private fun SettingEntry.searchTexts(resources: Resources): List<String> {
    val texts = mutableListOf(resources.getString(label), resources.getString(keywords), resources.getString(category.title))
    hint?.let { texts += resources.getString(it) }
    if (this is SettingEntry.Choice<*>) choiceLabels().mapTo(texts) { resources.getString(it) }
    if (this is SettingEntry.Pick) choices.mapNotNull(choiceLabel).mapTo(texts) { resources.getString(it) }
    return texts
}
