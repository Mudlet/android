package org.mudlet.android.ui.scripts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.TriggerPatternType
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.ScriptItemsClient
import org.mudlet.android.ui.ScriptNotice
import org.mudlet.android.ui.SectionHeader

/** The page that makes a trigger from a line, for tests to scroll. */
const val TRIGGER_FROM_LINE_TAG = "triggerFromLine"

private val triggerFromLineSaver = listSaver<TriggerFromLine, Any>(
    save = { listOf(it.line, it.roles.map(WordRole::ordinal), it.wholeLine, it.wideCaptures) },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        TriggerFromLine(saved[0] as String, (saved[1] as List<Int>).map { WordRole.entries[it] }, saved[2] as Boolean, saved[3] as Boolean)
    },
)

@get:StringRes
private val WordRole.label: Int
    get() = when (this) {
        WordRole.FIXED -> R.string.line_trigger_fixed
        WordRole.CAPTURE -> R.string.line_trigger_capture
        WordRole.OPTIONAL -> R.string.line_trigger_optional
    }

@get:StringRes
private val TriggerPatternType.typeName: Int
    get() = when (this) {
        TriggerPatternType.REGEX -> R.string.pattern_regex
        TriggerPatternType.EXACT -> R.string.pattern_exact
        else -> R.string.pattern_substring
    }

/**
 * Makes a trigger from [line], a line of the game's text ([TriggerFromLine] has the rules): the line's words, each a
 * button that makes it captured, optional or fixed again, two switches, the pattern that comes of them, and what that
 * pattern takes from the line and how many of the game's [recent] lines, as they are when the page opens, it matches.
 * Its button has [scripts] make the trigger and save it with the pattern, and the trigger's page then opens through
 * [onMade], where it is named and given what it does.
 */
@Composable
internal fun TriggerFromLineScreen(scripts: ScriptItemsClient, line: String, recent: () -> List<String>, onBack: () -> Unit, onMade: (ScriptItemKey) -> Unit) {
    var draft by rememberSaveable(line, stateSaver = triggerFromLineSaver) { mutableStateOf(TriggerFromLine.of(line)) }
    // Until the engine answers, which it does not once the link to it is another: a second tap would make a second trigger
    var making by rememberSaveable(scripts.attaches) { mutableStateOf(false) }
    val notice = scripts.notices.firstOrNull()
    LaunchedEffect(notice) { if (notice != null && notice !is ScriptNotice.Added) making = false }

    MudletScaffold(
        stringResource(R.string.line_trigger_title),
        onBack = onBack,
        snackbarHost = { ScriptNotices(scripts) { onMade(it.key) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(TRIGGER_FROM_LINE_TAG),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(line.trim(), fieldWidth.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                Words(draft) { draft = draft.withNextRole(it) }
                SwitchField(R.string.line_trigger_whole, R.string.line_trigger_whole_hint, draft.wholeLine) { draft = draft.copy(wholeLine = it) }
                SwitchField(R.string.line_trigger_wide, R.string.line_trigger_wide_hint, draft.wideCaptures) { draft = draft.copy(wideCaptures = it) }
                if (draft.usable) {
                    Pattern(draft)
                    Test(draft, recent)
                } else {
                    Text(stringResource(R.string.line_trigger_unusable), fieldWidth.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.error)
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Button({ making = scripts.addTrigger(draft.name, draft.pattern) }, fieldWidth, enabled = draft.usable && !making) {
                        Text(stringResource(R.string.line_trigger_make))
                    }
                }
            }
        }
    }
}

@Composable
private fun Words(draft: TriggerFromLine, onNextRole: (Int) -> Unit) {
    val words = draft.words
    if (words.isEmpty()) return
    SectionHeader(stringResource(R.string.line_trigger_words), fieldWidth)
    val change = stringResource(R.string.line_trigger_change)
    FlowRow(fieldWidth, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        words.forEachIndexed { i, word ->
            val role = draft.roles[i]
            val state = stringResource(role.label)
            FilterChip(
                role != WordRole.FIXED,
                { onNextRole(i) },
                label = { Text(role.shows(word), fontFamily = FontFamily.Monospace) },
                // The role in words: the brackets and the question mark that show it are not read
                modifier = Modifier.semantics {
                    stateDescription = state
                    onClick(change, null)
                },
            )
        }
    }
    Text(
        stringResource(R.string.line_trigger_words_hint),
        fieldWidth.padding(horizontal = 4.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Pattern(draft: TriggerFromLine) {
    val pattern = draft.pattern
    SectionHeader(stringResource(R.string.line_trigger_pattern), fieldWidth)
    Text(stringResource(pattern.type.typeName), fieldWidth.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(pattern.text, fieldWidth.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
}

@Composable
private fun Test(draft: TriggerFromLine, recent: () -> List<String>) {
    SectionHeader(stringResource(R.string.line_trigger_test), fieldWidth)
    // Lua's matches[1] is all that matched: what is captured starts at 2
    draft.captures(draft.line).orEmpty().forEachIndexed { i, captured ->
        Text(stringResource(R.string.line_trigger_match, i + 2, captured), fieldWidth.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyLarge)
    }
    // Not followed as the game goes on: every new line would try the pattern on them all again
    val lines = remember { recent() }
    val matched = remember(draft) { lines.count { draft.captures(it) != null } }
    Text(
        pluralStringResource(R.plurals.line_trigger_scrollback, matched, matched, lines.size),
        fieldWidth.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}
