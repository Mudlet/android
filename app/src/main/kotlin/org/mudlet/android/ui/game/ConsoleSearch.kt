package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.protocol.nextMatchingLine
import org.mudlet.android.ui.UiLine

// Typing pauses this long before the count of matching lines is read
private const val COUNT_SETTLE_MS = 600L

/** How a match is drawn: desktop's `TTextEdit` search highlight, black on yellow. */
internal val SEARCH_MATCH = SpanStyle(color = Color.Black, background = Color.Yellow)

/**
 * A search of the main console's scrollback, as desktop's search bar does it: case ignored, the first search up from
 * the bottom, and each next one up or down from the line the last one found.
 */
@Stable
internal class ConsoleSearch {
    var query by mutableStateOf("")
        private set

    /** The line the last search found, numbered as the profile session numbers lines; null before one. */
    var found by mutableStateOf<Long?>(null)
        private set

    /** The last search found nothing more that way. */
    var exhausted by mutableStateOf(false)
        private set

    fun queryChanged(text: String) {
        query = text
        found = null
        exhausted = false
    }

    /** Finds the next line holding the query, up (older) or down (newer), among [lines], the last of [linesAdded]. */
    fun step(lines: List<UiLine>, linesAdded: Long, up: Boolean) {
        val firstLine = linesAdded - lines.size
        // A found line since dropped from the scrollback is above all of it
        val from = found?.let { (it - firstLine).coerceIn(-1L, lines.size.toLong()).toInt() } ?: lines.size
        val at = nextMatchingLine(lines.map { it.text }, query, from, up)
        exhausted = at == null
        if (at != null) found = firstLine + at
    }

    companion object {
        /** Keeps the query across a rotation or a switch of profile; the search after it starts from the bottom again. */
        val Saver = Saver<ConsoleSearch, String>({ it.query }, { ConsoleSearch().apply { queryChanged(it) } })
    }
}

/**
 * The bar of a [search] of [lines], the last of [linesAdded]: its field, older and newer matches, and closing it with
 * [onClose]. Once typing pauses it says how many lines match, and after each step the line found, or that there is none.
 */
@Composable
internal fun ConsoleSearchBar(search: ConsoleSearch, lines: List<UiLine>, linesAdded: Long, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var counted by remember { mutableStateOf<Int?>(null) }
    val latestLines by rememberUpdatedState(lines)
    val query = search.query
    LaunchedEffect(query) {
        counted = null
        if (query.isEmpty()) return@LaunchedEffect
        delay(COUNT_SETTLE_MS)
        counted = latestLines.count { it.text.contains(query, ignoreCase = true) }
    }
    val found = search.found?.let { n -> lines.getOrNull((n - (linesAdded - lines.size)).toInt())?.text }
    val count = counted
    val status = when {
        search.exhausted -> stringResource(R.string.console_search_none)
        found != null -> found
        count != null -> pluralStringResource(R.plurals.console_search_count, count, count)
        else -> null
    }
    fun step(up: Boolean) = search.step(lines, linesAdded, up)
    Column(modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query,
                search::queryChanged,
                Modifier.weight(1f).focusRequester(focus),
                label = { Text(stringResource(R.string.console_search_field)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // As desktop's Enter in its search box: the next match up
                keyboardActions = KeyboardActions(onSearch = { step(up = true) }),
            )
            IconButton({ step(up = true) }, enabled = query.isNotEmpty()) {
                Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.console_search_older))
            }
            IconButton({ step(up = false) }, enabled = query.isNotEmpty()) {
                Icon(painterResource(R.drawable.ic_arrow_downward), stringResource(R.string.console_search_newer))
            }
            IconButton(onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.console_search_close)) }
        }
        status?.let {
            Text(it, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}
