package org.mudlet.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.CONSOLE_FOREGROUND
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ListSegment
import org.mudlet.android.ui.LocalInDetailPane
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
    MudletScaffold(
        stringResource(page.category.title),
        onBack = onBack,
        // Beside the hub, the hub says it
        snackbarHost = { if (!LocalInDetailPane.current) SettingsNotices(listOfNotNull(page.game)) },
    ) { padding ->
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
                if (page.category == SettingsCategory.COLOURS) {
                    TextButton({ owner.resetToMudlets(Settings.colours) }, Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.settings_colours_reset))
                    }
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
        is SettingEntry.Pick -> PickRow(entry, owner)
        is SettingEntry.Typed -> TypedRow(entry, owner)
        is SettingEntry.Colour -> ColourRow(entry, owner)
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

/** A field for the text, which changes the setting as it loses the focus, as Done is pressed or as the page closes. */
@Composable
private fun TypedRow(entry: SettingEntry.Typed, owner: SettingsOwner) {
    val value = owner.value(entry.setting)
    // Not sent on each key: the engine's answer to one change could land between two keys and undo the second
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val focusManager = LocalFocusManager.current
    // The value read as it is done with: the focus going and the page closing can both come before a recomposition
    val done by rememberUpdatedState {
        if (draft != owner.value(entry.setting)) owner.change(entry.setting, draft)
    }
    DisposableEffect(Unit) { onDispose { done() } }
    OutlinedTextField(
        draft,
        { draft = it },
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).onFocusChanged { if (!it.isFocused) done() },
        label = { Text(stringResource(entry.label)) },
        supportingText = entry.hint?.let { { Text(stringResource(it)) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    )
}

/** The colour's swatch and "#rrggbb" beside its label; a tap opens [ColourDialog]. */
@Composable
private fun ColourRow(entry: SettingEntry.Colour, owner: SettingsOwner) {
    val rgb = owner.value(entry.setting)
    var changing by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.settings_colour_change), role = Role.Button) { changing = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(entry.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(Settings.colourName(rgb), Modifier.padding(end = 12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Swatch(rgb)
    }
    if (changing) {
        ColourDialog(stringResource(entry.label), rgb, onDismiss = { changing = false }) {
            changing = false
            owner.change(entry.setting, it)
        }
    }
}

@Composable
private fun Swatch(rgb: Int, size: Dp = 32.dp) {
    Box(
        Modifier.size(size).clip(MaterialTheme.shapes.small).background(Color(0xFF000000 or rgb.toLong()))
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
    )
}

/** A slider for each of red, green and blue, and the colour as "#rrggbb" to type, which [onPick] takes as OK is pressed. */
@Composable
private fun ColourDialog(title: String, initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var rgb by rememberSaveable { mutableIntStateOf(initial) }
    // What is typed, kept apart from rgb while it is not yet a whole colour
    var typed by rememberSaveable { mutableStateOf(Settings.colourName(initial)) }
    val channels = listOf(R.string.settings_colour_red_amount to 16, R.string.settings_colour_green_amount to 8, R.string.settings_colour_blue_amount to 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        // OK takes only a whole colour, never the last one the sliders left while the typed name is not one
        confirmButton = {
            TextButton({ onPick(rgb) }, enabled = Settings.parseColourName(typed) != null) { Text(stringResource(R.string.settings_colour_ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.settings_choice_cancel)) } },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatch(rgb, 48.dp)
                channels.forEach { (name, shift) ->
                    val amount = (rgb shr shift) and 0xFF
                    val label = stringResource(name)
                    Text(stringResource(R.string.settings_colour_amount, label, amount), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = amount.toFloat(),
                        onValueChange = {
                            rgb = (rgb and (0xFF shl shift).inv()) or (it.roundToInt() shl shift)
                            typed = Settings.colourName(rgb)
                        },
                        modifier = Modifier.semantics {
                            contentDescription = label
                            stateDescription = amount.toString()
                        },
                        valueRange = 0f..255f,
                    )
                }
                OutlinedTextField(
                    typed,
                    { text ->
                        typed = text
                        Settings.parseColourName(text)?.let { rgb = it }
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_colour_name)) },
                    isError = Settings.parseColourName(typed) == null,
                    supportingText = { Text(stringResource(R.string.settings_colour_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                )
            }
        },
    )
}

/** A choice's label, or the choice itself for one the app has no label for, such as a name a newer Mudlet added. */
@Composable
private fun SettingEntry.Pick.labelOf(choice: String): String = choiceLabel(choice)?.let { stringResource(it) } ?: choice

/** The current choice under the setting's label, and the list to pick another from, which a pick closes. */
@Composable
private fun PickRow(entry: SettingEntry.Pick, owner: SettingsOwner) {
    val chosen = owner.value(entry.setting)
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.settings_choose), role = Role.Button) { picking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(entry.label), style = MaterialTheme.typography.bodyLarge)
        Text(entry.labelOf(chosen), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        entry.hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (!picking) return
    // One the list lacks is shown first, so the current choice is always there to see
    val choices = if (chosen in entry.choices) entry.choices else listOf(chosen) + entry.choices
    AlertDialog(
        onDismissRequest = { picking = false },
        confirmButton = {},
        dismissButton = { TextButton({ picking = false }) { Text(stringResource(R.string.settings_choice_cancel)) } },
        title = { Text(stringResource(entry.label)) },
        text = {
            LazyColumn(Modifier.selectableGroup(), rememberLazyListState(choices.indexOf(chosen))) {
                items(choices) { choice ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(choice == chosen, role = Role.RadioButton) {
                                picking = false
                                owner.change(entry.setting, choice)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(choice == chosen, onClick = null)
                        Text(entry.labelOf(choice), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
    )
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
                onValueChange = { dragged = it.roundToInt() },
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
