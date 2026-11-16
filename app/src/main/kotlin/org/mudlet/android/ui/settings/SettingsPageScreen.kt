package org.mudlet.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.CONSOLE_FOREGROUND
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ListSegment
import org.mudlet.android.ui.MudletScaffold
import kotlin.math.roundToInt

internal val MAX_WIDTH = 640.dp

// How long the row search opened a page at stays tinted
private const val HIGHLIGHT_MS = 1500L

private const val MAX_MARKED_STEPS = 40

/**
 * A settings page: its category's settings for its game, or the app's, in a column no wider than reads well. Each
 * change applies at once. A game's page asks Mudlet for the game's values as it opens, as a script may have changed
 * them, and closes once the game does.
 */
@Composable
fun SettingsPageScreen(page: SettingsPage, onBack: () -> Unit) {
    val owner = page.owner
    if (!owner.available) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    LaunchedEffect(page.game) { page.game?.let { EngineClient.session(it)?.refreshSettings() } }
    var highlight by remember(page) { mutableStateOf(page.highlight) }
    LaunchedEffect(page) {
        delay(HIGHLIGHT_MS)
        highlight = null
    }
    MudletScaffold(stringResource(page.category.title), onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text(
                    scopeLine(page),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val entries = page.entries
                entries.forEachIndexed { i, entry ->
                    val lit = entry.setting.key == highlight
                    val requester = remember { BringIntoViewRequester() }
                    if (lit) LaunchedEffect(Unit) { requester.bringIntoView() }
                    ListSegment(i, entries.size, Modifier.bringIntoViewRequester(requester), selected = lit) { EntryRows(entry, owner) }
                }
            }
        }
    }
}

/** Whose settings a page changes, under its title. */
@Composable
private fun scopeLine(page: SettingsPage): String = when {
    page.game != null -> stringResource(R.string.settings_for_game, page.game)
    page.category.forGame -> stringResource(R.string.settings_for_new_games)
    else -> stringResource(R.string.settings_for_app)
}

@Composable
private fun EntryRows(entry: SettingEntry, owner: SettingsOwner) {
    when (entry) {
        is SettingEntry.Switch -> SwitchRow(entry, owner)
        is SettingEntry.Choice<*> -> ChoiceRows(entry, owner)
        is SettingEntry.Number -> NumberRows(entry, owner)
    }
}

/** A switch the whole row turns, which TalkBack reads as its [overline] (where search found it), label, then hint. */
@Composable
internal fun SwitchRow(entry: SettingEntry.Switch, owner: SettingsOwner, overline: String? = null) {
    val checked = owner.value(entry.setting)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch) { owner.change(entry.setting, it) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            if (overline != null) Overline(overline)
            Text(stringResource(entry.label), style = MaterialTheme.typography.bodyLarge)
            entry.hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
internal fun Overline(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

/** One radio button a choice, in a group under the setting's label. */
@Composable
private fun ChoiceRows(entry: SettingEntry.Choice<*>, owner: SettingsOwner) {
    val chosen = entry.chosen(owner)
    Text(stringResource(entry.label), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = MaterialTheme.typography.bodyLarge)
    Column(Modifier.selectableGroup().padding(bottom = 4.dp)) {
        entry.choiceLabels().forEachIndexed { i, label ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(i == chosen, role = Role.RadioButton) { entry.choose(owner, i) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(i == chosen, onClick = null)
                Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * A whole number: a slider, with buttons a step either way, its value and any sample at that size. A drag changes it
 * as the finger lifts, so a game's is sent once.
 */
@Composable
private fun NumberRows(entry: SettingEntry.Number, owner: SettingsOwner) {
    val setting = entry.setting
    var dragged by remember { mutableStateOf<Int?>(null) }
    val size = dragged ?: owner.value(setting)
    val range = setting.range
    val label = stringResource(entry.label)
    val value = pluralStringResource(entry.valueText, size, size)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        entry.hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Clamped: a disabled button still has its click action, which a test or an accessibility service can call
            IconButton({ owner.change(setting, (size - 1).coerceIn(range)) }, enabled = size > range.first) {
                Icon(painterResource(R.drawable.ic_remove), stringResource(entry.less))
            }
            Slider(
                value = size.toFloat(),
                onValueChange = { owner.change(setting, it.roundToInt()) },
                onValueChangeFinished = {
                    dragged?.let { owner.change(setting, it) }
                    dragged = null
                },
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = label
                    stateDescription = value
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                // A mark a step on a short range; a long one's would run together
                steps = if (range.last - range.first <= MAX_MARKED_STEPS) range.last - range.first - 1 else 0,
            )
            IconButton({ owner.change(setting, (size + 1).coerceIn(range)) }, enabled = size < range.last) {
                Icon(painterResource(R.drawable.ic_add), stringResource(entry.more))
            }
        }
        Text(value, style = MaterialTheme.typography.bodyMedium)
        if (entry.sample) {
            Text(
                stringResource(R.string.settings_text_size_sample),
                Modifier.fillMaxWidth().background(CONSOLE_BACKGROUND).padding(8.dp),
                color = CONSOLE_FOREGROUND,
                fontFamily = FontFamily.Monospace,
                fontSize = size.sp,
            )
        }
    }
}
