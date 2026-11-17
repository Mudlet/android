package org.mudlet.android.ui.errors

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.protocol.ErrorConsoleEntry
import org.mudlet.android.ui.ErrorConsoleClient
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.scripts.ScriptItemKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The error console's list, for the tests to find. */
const val ERROR_CONSOLE_TAG = "errorConsole"

// Above this, a badge shows errors_badge_many
private const val BADGE_MAX = 99

// The clipboard's text crosses Binder, whose transaction is at most 1 MB, so a larger clip throws
private const val COPY_MAX_CHARS = 100_000

/**
 * A profile's error console, as desktop Mudlet's editor shows it: each Lua error, printError() and debugc() its scripts
 * raised, the newest at the foot, with what raised it and when. An entry whose item the library shows opens it
 * ([onOpen]). Copy all puts the entries on the clipboard as text, the newest that fit in 100000 characters, and Clear empties the console in the engine. While it
 * shows, each new error is said by TalkBack once, and none counts as unseen.
 */
@Composable
internal fun ErrorConsoleScreen(errors: ErrorConsoleClient, game: String, onBack: () -> Unit, onOpen: (ScriptItemKey) -> Unit) {
    val entries = errors.entries
    val resources = LocalResources.current
    val context = LocalContext.current
    val format = timeFormat(LocalConfiguration.current.locales[0])
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val newest = entries.lastOrNull()?.n ?: 0L
    // The newest entry when the screen came, or that TalkBack was last told of
    var told by remember { mutableLongStateOf(newest) }
    // While the newest entry is in view the list follows new ones, else it stays where the player scrolled to, as the
    // game's console does; by the last entry, not the list's end, as one entry can be taller than the screen
    var following by rememberSaveable { mutableStateOf(true) }
    // Read only in the SideEffect, so setting it composes nothing again
    var scrolledFor by remember { mutableLongStateOf(-1L) }
    // Before the layout that shows the new entries, so that layout already has the newest in view; only for a new
    // newest, so a scroll down to the last entry does not jump to its top
    SideEffect {
        if (newest != scrolledFor) {
            scrolledFor = newest
            if (following && entries.isNotEmpty()) list.requestScrollToItem(entries.lastIndex)
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo }.collect { info ->
            if (info.viewportSize != IntSize.Zero && info.totalItemsCount > 0) following = info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
        }
    }
    LaunchedEffect(newest) {
        errors.markSeen()
        // A clear or a new engine instance numbers from below what was told
        if (newest < told) told = 0
        val fresh = entries.filter { !it.debug && it.n > told }
        if (fresh.isNotEmpty()) Announcer.announce(resources.announcement(fresh), assertive = false)
        told = newest
    }
    MudletScaffold(
        stringResource(R.string.errors_title, game),
        onBack = onBack,
        actions = {
            if (entries.isNotEmpty()) {
                IconButton({
                    val copied = newestThatFit(entries.map { resources.copyText(it, format) })
                    val clip = ClipData.newPlainText(resources.getString(R.string.errors_title, game), copied.joinToString("\n\n"))
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    if (copied.size < entries.size) {
                        scope.launch { snackbars.showSnackbar(resources.getQuantityString(R.plurals.errors_copied_newest, copied.size, copied.size), withDismissAction = true) }
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        // Android 13 and later show what was copied themselves
                        scope.launch { snackbars.showSnackbar(resources.getString(R.string.errors_copied)) }
                    }
                }) { Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.errors_copy)) }
                IconButton({
                    if (!errors.clear()) scope.launch { snackbars.showSnackbar(resources.getString(R.string.profiles_error_not_attached), withDismissAction = true) }
                }) { Icon(painterResource(R.drawable.ic_clear_all), stringResource(R.string.errors_clear)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.errors_none), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(ERROR_CONSOLE_TAG), list) {
                items(entries, key = { it.n }) { entry ->
                    EntryRow(entry, format, onOpen)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: ErrorConsoleEntry, format: DateTimeFormatter, onOpen: (ScriptItemKey) -> Unit) {
    val resources = LocalResources.current
    val kind = entry.kind
    val open = stringResource(R.string.errors_open_item)
    val row = if (kind != null) {
        Modifier.clickable(onClickLabel = open) { onOpen(ScriptItemKey(kind, entry.item)) }
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    Column(row.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            resources.heading(entry, format),
            style = MaterialTheme.typography.labelMedium,
            color = if (entry.debug) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        resources.source(entry)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
        Text(entry.message, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

/** The script library's title bar button to the error console, with how many errors are unseen. */
@Composable
internal fun ErrorConsoleButton(errors: ErrorConsoleClient, onClick: () -> Unit) {
    IconButton(onClick) {
        ErrorCountBadge(errors.unseen, stringResource(R.string.errors_open)) { Icon(painterResource(R.drawable.ic_error_outline), it) }
    }
}

/**
 * [content], a button's icon whose name is [label], with how many errors are [unseen] on it as a badge, and in its name
 * for a screen reader.
 */
@Composable
internal fun ErrorCountBadge(unseen: Int, label: String, content: @Composable (label: String) -> Unit) {
    if (unseen <= 0) {
        content(label)
        return
    }
    val count = if (unseen > BADGE_MAX) stringResource(R.string.errors_badge_many) else unseen.toString()
    BadgedBox(badge = { Badge(Modifier.clearAndSetSemantics {}) { Text(count) } }) {
        content(pluralStringResource(R.plurals.errors_unseen, unseen, label, unseen))
    }
}

private fun timeFormat(locale: Locale): DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault())

private fun Resources.heading(entry: ErrorConsoleEntry, format: DateTimeFormatter): String {
    val at = format.format(Instant.ofEpochMilli(entry.at))
    return getString(if (entry.debug) R.string.errors_debug_at else R.string.errors_error_at, at)
}

// The item's name and Lua function, or what else ran the Lua, the event and the line, those there are; null for none
private fun Resources.source(entry: ErrorConsoleEntry): String? {
    val parts = listOfNotNull(
        entry.obj.ifEmpty { null },
        entry.function.ifEmpty { null },
        entry.event.ifEmpty { null }?.let { getString(R.string.errors_event, it) },
        entry.line.takeIf { it > 0 }?.let { getString(R.string.errors_line, it) },
    )
    return if (parts.isEmpty()) null else parts.joinToString(getString(R.string.errors_separator))
}

// The newest of [texts] whose sum is within COPY_MAX_CHARS, the oldest first
private fun newestThatFit(texts: List<String>): List<String> {
    var chars = 0
    val fit = texts.asReversed().takeWhile {
        chars += it.length
        chars <= COPY_MAX_CHARS
    }
    return fit.asReversed()
}

private fun Resources.copyText(entry: ErrorConsoleEntry, format: DateTimeFormatter): String = listOfNotNull(heading(entry, format), source(entry), entry.message).joinToString("\n")

private fun Resources.announcement(fresh: List<ErrorConsoleEntry>): String = if (fresh.size == 1) {
    getString(R.string.errors_announce, fresh.last().message)
} else {
    getQuantityString(R.plurals.errors_announce_many, fresh.size, fresh.size, fresh.last().message)
}
