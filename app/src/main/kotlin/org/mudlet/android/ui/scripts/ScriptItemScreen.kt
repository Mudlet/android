package org.mudlet.android.ui.scripts

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.protocol.TriggerPattern
import org.mudlet.android.protocol.TriggerPatternType
import org.mudlet.android.protocol.rgbColor
import org.mudlet.android.protocol.rgbText
import org.mudlet.android.ui.ListSegment
import org.mudlet.android.ui.LocalInDetailPane
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.ScriptItemsClient
import org.mudlet.android.ui.SectionHeader
import org.mudlet.android.ui.settings.MAX_WIDTH

/** The item page's fields, for tests to scroll. */
const val SCRIPT_ITEM_TAG = "scriptItem"

internal val fieldWidth = Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth()

/**
 * The page of the item [key]: its name, what its kind has (a trigger's patterns and options, an alias's pattern and
 * command, a script's event handlers, a timer's time and command, a key's key and command, a button's commands or a
 * toolbar's place) and its Lua, which [onEditCode] opens in the Lua editor. What the player changes is a draft, kept by
 * [scripts] until Apply saves it as the desktop editor's save does: [onGame] goes to the game and keeps it, to come
 * back to, and Back asks whether to discard it. Why the item does not run shows at the top. Delete hides it with Undo offered by the library,
 * and goes [onBack].
 */
@Composable
internal fun ScriptItemScreen(scripts: ScriptItemsClient, key: ScriptItemKey, onBack: () -> Unit, onGame: () -> Unit, onEditCode: () -> Unit) {
    // Asked again each time, as a script may have changed it since, and once the engine is back; a draft stays over what comes
    LaunchedEffect(key, scripts.attaches) {
        scripts.open(key)
        // What a button's page asks depends on where the button is, which only the list says
        if (key.kind == ScriptItemKind.BUTTON && scripts.lists[key.kind] == null) scripts.ask(key.kind)
    }
    val saved = scripts.saved[key]
    val draft = scripts.drafts[key]
    val item = draft ?: saved
    val dirty = draft != null
    var leaving by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { leaving = true }
    val unnamed = stringResource(R.string.scripts_unnamed)
    val name = item?.node?.name?.ifEmpty { unnamed }

    MudletScaffold(
        name ?: stringResource(R.string.scripts_loading),
        onBack = { if (dirty) leaving = true else onBack() },
        // Beside the library, the library's show them
        snackbarHost = { if (!LocalInDetailPane.current) ScriptNotices(scripts) {} },
        actions = {
            if (item != null) {
                IconButton({
                    scripts.deleteLater(key, name.orEmpty())
                    onBack()
                }) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.scripts_delete, name.orEmpty())) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(SCRIPT_ITEM_TAG),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val refusal = scripts.unopened[key]
                when {
                    item != null -> Fields(item, scripts.lists[key.kind].orEmpty(), saved?.node?.error.orEmpty(), scripts::edit, onEditCode)
                    refusal != null -> Text(stringResource(R.string.scripts_refused_open, refusal), fieldWidth.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    else -> Text(stringResource(R.string.scripts_loading), fieldWidth.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Row(fieldWidth, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val toGame = stringResource(R.string.scripts_back_to_game)
                        OutlinedButton(onGame, Modifier.semantics { contentDescription = toGame }) {
                            Icon(painterResource(R.drawable.ic_videogame_asset), contentDescription = null)
                            Text(stringResource(R.string.game_title), Modifier.padding(start = 8.dp))
                        }
                        Button({ scripts.apply(key) }, Modifier.weight(1f), enabled = dirty) { Text(stringResource(R.string.scripts_apply)) }
                    }
                }
            }
        }
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            confirmButton = {
                TextButton({
                    leaving = false
                    scripts.discard(key)
                    onBack()
                }) { Text(stringResource(R.string.scripts_discard)) }
            },
            dismissButton = { TextButton({ leaving = false }) { Text(stringResource(R.string.scripts_keep_editing)) } },
            title = { Text(stringResource(R.string.scripts_discard_title, name.orEmpty())) },
            text = { Text(stringResource(R.string.scripts_discard_text)) },
        )
    }
}

@Composable
private fun ColumnScope.Fields(item: ScriptItem, listed: List<ScriptItemNode>, error: String, onChange: (ScriptItem) -> Unit, onEditCode: () -> Unit) {
    if (error.isNotEmpty()) {
        Surface(fieldWidth.padding(top = 8.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
            Text(stringResource(R.string.scripts_not_running, error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    OutlinedTextField(
        item.node.name,
        { onChange(item.copy(node = item.node.copy(name = it))) },
        fieldWidth.padding(top = 8.dp),
        label = { Text(stringResource(R.string.scripts_name)) },
        singleLine = true,
    )
    when (val details = item.details) {
        is ScriptItemDetails.Trigger -> TriggerFields(details) { onChange(item.copy(details = it)) }
        is ScriptItemDetails.Alias -> AliasFields(details) { onChange(item.copy(details = it)) }
        is ScriptItemDetails.Script -> EventHandlers(details) { onChange(item.copy(details = it)) }
        is ScriptItemDetails.Timer -> if (!item.node.folder) TimerFields(details) { onChange(item.copy(details = it)) }
        is ScriptItemDetails.Key -> if (!item.node.folder) KeyFields(details) { onChange(item.copy(details = it)) }
        is ScriptItemDetails.Button -> ButtonFields(details, listed.buttonPart(item.node)) { onChange(item.copy(details = it)) }
    }
    SectionHeader(stringResource(R.string.scripts_code), fieldWidth)
    ListSegment(0, 1, fieldWidth.padding(bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = stringResource(R.string.scripts_lua_edit), onClick = onEditCode)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.scripts_lua), style = MaterialTheme.typography.bodyLarge)
                val lines = if (item.script.isBlank()) 0 else lineCount(item.script)
                Text(
                    if (lines == 0) stringResource(R.string.scripts_lua_empty) else pluralStringResource(R.plurals.scripts_lua_lines, lines, lines),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.TriggerFields(trigger: ScriptItemDetails.Trigger, onChange: (ScriptItemDetails.Trigger) -> Unit) {
    var typing by rememberSaveable { mutableStateOf<Int?>(null) }
    SectionHeader(stringResource(R.string.scripts_patterns), fieldWidth)
    val patterns = trigger.patterns
    for ((i, pattern) in patterns.withIndex()) {
        ListSegment(i, patterns.size, fieldWidth) {
            PatternRow(
                i,
                patterns.size,
                pattern,
                onText = { text -> onChange(trigger.copy(patterns = patterns.toMutableList().also { it[i] = pattern.copy(text = text) })) },
                onType = { typing = i },
                onMove = { to -> onChange(trigger.copy(patterns = patterns.moved(i, to))) },
                onRemove = { onChange(trigger.copy(patterns = patterns.filterIndexed { j, _ -> j != i })) },
            )
        }
    }
    TextButton({ onChange(trigger.copy(patterns = patterns + newPattern(TriggerPatternType.SUBSTRING))) }, fieldWidth) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
        Text(stringResource(R.string.scripts_add_pattern), Modifier.padding(start = 8.dp))
    }
    val typed = typing
    val typedPattern = typed?.let(patterns::getOrNull)
    if (typed != null && typedPattern != null) {
        PatternTypeSheet(typedPattern.type, onDismiss = { typing = null }) { type ->
            typing = null
            val text = if (type == TriggerPatternType.SPACER || typedPattern.type == TriggerPatternType.SPACER) newPattern(type).text else typedPattern.text
            onChange(trigger.copy(patterns = patterns.toMutableList().also { it[typed] = TriggerPattern(text, type) }))
        }
    }

    SectionHeader(stringResource(R.string.scripts_options), fieldWidth)
    val all = trigger.lineDelta >= 0
    Column(fieldWidth.selectableGroup()) {
        Choice(stringResource(R.string.scripts_match_any), !all) { onChange(trigger.withMatchAll(false)) }
        Choice(stringResource(R.string.scripts_match_all), all) { onChange(trigger.withMatchAll(true)) }
    }
    if (all) {
        NumberField(R.string.scripts_match_within, trigger.lineDelta, 0..MAX_MATCH_LINES) { onChange(trigger.withMatchAll(true, it)) }
    }
    SwitchField(R.string.scripts_every_occurrence, R.string.scripts_every_occurrence_hint, trigger.matchAll) { onChange(trigger.copy(matchAll = it)) }
    NumberField(R.string.scripts_keep_firing, trigger.fireLength, 0..MAX_FIRE_LINES) { onChange(trigger.copy(fireLength = it)) }
    SwitchField(R.string.scripts_filter, R.string.scripts_filter_hint, trigger.filter) { onChange(trigger.copy(filter = it)) }
    SwitchField(R.string.scripts_highlight, R.string.scripts_highlight_hint, trigger.highlight) { onChange(trigger.copy(highlight = it)) }
    if (trigger.highlight) {
        ColorField(R.string.scripts_highlight_fg, trigger.highlightFg) { onChange(trigger.copy(highlightFg = it)) }
        ColorField(R.string.scripts_highlight_bg, trigger.highlightBg) { onChange(trigger.copy(highlightBg = it)) }
    }
    SwitchField(R.string.scripts_sound, R.string.scripts_sound_hint, trigger.sound) { onChange(trigger.copy(sound = it)) }
    if (trigger.sound) {
        OutlinedTextField(
            trigger.soundFile,
            { onChange(trigger.copy(soundFile = it)) },
            fieldWidth,
            label = { Text(stringResource(R.string.scripts_sound_file)) },
            singleLine = true,
        )
    }
    OutlinedTextField(
        trigger.command,
        { onChange(trigger.copy(command = it)) },
        fieldWidth.padding(top = 8.dp),
        label = { Text(stringResource(R.string.scripts_trigger_command)) },
        singleLine = true,
    )
}

/** The words each pattern type goes by here, and what it does, as the type sheet explains them. */
private class PatternTypeText(@param:StringRes val name: Int, @param:StringRes val description: Int)

private val TriggerPatternType.text: PatternTypeText
    get() = when (this) {
        TriggerPatternType.SUBSTRING -> PatternTypeText(R.string.pattern_substring, R.string.pattern_substring_description)
        TriggerPatternType.REGEX -> PatternTypeText(R.string.pattern_regex, R.string.pattern_regex_description)
        TriggerPatternType.START_OF_LINE -> PatternTypeText(R.string.pattern_start, R.string.pattern_start_description)
        TriggerPatternType.EXACT -> PatternTypeText(R.string.pattern_exact, R.string.pattern_exact_description)
        TriggerPatternType.LUA -> PatternTypeText(R.string.pattern_lua, R.string.pattern_lua_description)
        TriggerPatternType.SPACER -> PatternTypeText(R.string.pattern_spacer, R.string.pattern_spacer_description)
        TriggerPatternType.COLOR -> PatternTypeText(R.string.pattern_color, R.string.pattern_color_description)
        TriggerPatternType.PROMPT -> PatternTypeText(R.string.pattern_prompt, R.string.pattern_prompt_description)
    }

/**
 * Pattern [index] of [count]: its type, which opens the type sheet, its moves and its removal, then its text in the field
 * its type takes: a spacer's number of lines, none for a prompt, and a colour pattern's colours, which are kept as they
 * are (an ANSI colour picker is for later).
 */
@Composable
private fun PatternRow(
    index: Int,
    count: Int,
    pattern: TriggerPattern,
    onText: (String) -> Unit,
    onType: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val number = index + 1
    Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
        // The buttons go under the type where both do not fit side by side, as on a narrow phone in a wordy language
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
            val typeName = stringResource(pattern.type.text.name)
            val typeLabel = stringResource(R.string.scripts_pattern_type_of, number, typeName)
            AssistChip(
                onType,
                label = { Text(typeName) },
                modifier = Modifier.semantics { contentDescription = typeLabel },
                trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
            )
            Row {
                IconButton({ onMove(index - 1) }, enabled = index > 0) {
                    Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.scripts_pattern_up, number))
                }
                IconButton({ onMove(index + 1) }, enabled = index < count - 1) {
                    Icon(painterResource(R.drawable.ic_arrow_downward), stringResource(R.string.scripts_pattern_down, number))
                }
                IconButton(onRemove) { Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.scripts_pattern_remove, number)) }
            }
        }
        val label = stringResource(R.string.scripts_pattern, number)
        when (pattern.type) {
            TriggerPatternType.PROMPT -> Text(stringResource(R.string.pattern_prompt_description), Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

            TriggerPatternType.COLOR -> {
                val colors = ColorPattern.parse(pattern.text)
                Text(
                    stringResource(R.string.scripts_color_pattern, colorName(colors.foreground), colorName(colors.background)),
                    Modifier.padding(end = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TriggerPatternType.SPACER -> NumberField(label, pattern.text.toIntOrNull() ?: 1, 1..MAX_FIRE_LINES, Modifier.fillMaxWidth().padding(end = 8.dp)) {
                onText(it.toString())
            }

            else -> OutlinedTextField(
                pattern.text,
                onText,
                Modifier.fillMaxWidth().padding(end = 8.dp),
                label = { Text(label) },
                textStyle = if (pattern.type == TriggerPatternType.REGEX || pattern.type == TriggerPatternType.LUA) {
                    MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                singleLine = true,
            )
        }
    }
}

@Composable
private fun colorName(color: Int): String = when (color) {
    ColorPattern.ANY_COLOR -> stringResource(R.string.scripts_color_any)
    ColorPattern.DEFAULT_COLOR -> stringResource(R.string.scripts_color_default)
    else -> color.toString()
}

/** A sheet of things to pick from, under its [title] and a button that closes it, which a screen reader reads as [close]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TitledSheet(title: String, close: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // No handle: its touch target is narrower than 48 dp, and the close button and Back do what it offers
        dragHandle = null,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            IconButton(onDismiss) { Icon(painterResource(R.drawable.ic_close), close) }
        }
        content()
    }
}

/** The sheet that explains each pattern type and picks one; a colour pattern is picked on the desktop for now. */
@Composable
private fun PatternTypeSheet(current: TriggerPatternType, onDismiss: () -> Unit, onPick: (TriggerPatternType) -> Unit) {
    TitledSheet(stringResource(R.string.scripts_pattern_type), stringResource(R.string.scripts_pattern_type_close), onDismiss) {
        Column(Modifier.selectableGroup().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            for (type in TriggerPatternType.entries) {
                if (type == TriggerPatternType.COLOR && current != TriggerPatternType.COLOR) continue
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(type == current, role = Role.RadioButton) { onPick(type) }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(type == current, onClick = null)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(stringResource(type.text.name), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(type.text.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AliasFields(alias: ScriptItemDetails.Alias, onChange: (ScriptItemDetails.Alias) -> Unit) {
    SectionHeader(stringResource(R.string.scripts_patterns), fieldWidth)
    OutlinedTextField(
        alias.pattern,
        { onChange(alias.copy(pattern = it)) },
        fieldWidth,
        label = { Text(stringResource(R.string.scripts_alias_pattern)) },
        supportingText = { Text(stringResource(R.string.scripts_alias_pattern_hint)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        singleLine = true,
    )
    OutlinedTextField(
        alias.command,
        { onChange(alias.copy(command = it)) },
        fieldWidth,
        label = { Text(stringResource(R.string.scripts_alias_command)) },
        singleLine = true,
    )
}

@Composable
private fun ColumnScope.EventHandlers(script: ScriptItemDetails.Script, onChange: (ScriptItemDetails.Script) -> Unit) {
    SectionHeader(stringResource(R.string.scripts_event_handlers), fieldWidth)
    val handlers = script.eventHandlers
    for ((i, event) in handlers.withIndex()) {
        ListSegment(i, handlers.size, fieldWidth) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(event, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                IconButton({ onChange(script.copy(eventHandlers = handlers.filterIndexed { j, _ -> j != i })) }) {
                    Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.scripts_event_remove, event))
                }
            }
        }
    }
    var adding by rememberSaveable { mutableStateOf("") }
    val event = adding.trim()
    OutlinedTextField(
        adding,
        { adding = it },
        fieldWidth.padding(top = 8.dp),
        label = { Text(stringResource(R.string.scripts_event_new)) },
        singleLine = true,
        trailingIcon = {
            IconButton({
                onChange(script.copy(eventHandlers = handlers + event))
                adding = ""
            }, enabled = event.isNotEmpty() && event !in handlers) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.scripts_event_add)) }
        },
    )
}

@Composable
internal fun Choice(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(text, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun SwitchField(@StringRes label: Int, @StringRes hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        fieldWidth.heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun NumberField(@StringRes label: Int, value: Int, range: IntRange, onValue: (Int) -> Unit) = NumberField(stringResource(label), value, range, fieldWidth, onValue)

/** A whole number in [range]; while what is typed is not one, the field says so and [value] stays as it was. */
@Composable
internal fun NumberField(label: String, value: Int, range: IntRange, modifier: Modifier, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val typed = text.toIntOrNull()
    OutlinedTextField(
        text,
        {
            text = it
            it.toIntOrNull()?.takeIf { n -> n in range }?.let(onValue)
        },
        modifier,
        label = { Text(label) },
        isError = typed == null || typed !in range,
        supportingText = if (typed == null || typed !in range) {
            { Text(stringResource(R.string.scripts_number_range, range.first, range.last)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

/** An opaque colour as `#rrggbb`, or empty to keep the text's own; a swatch shows it. */
@Composable
private fun ColorField(@StringRes label: Int, color: Int?, onColor: (Int?) -> Unit) {
    var text by remember(color) { mutableStateOf(rgbText(color)) }
    val valid = text.isEmpty() || rgbColor(text) != null
    OutlinedTextField(
        text,
        {
            text = it.trim()
            if (text.isEmpty()) onColor(null) else rgbColor(text)?.let(onColor)
        },
        fieldWidth,
        label = { Text(stringResource(label)) },
        leadingIcon = {
            Box(
                Modifier.size(24.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .background(color?.let { Color(it) } ?: Color.Transparent, CircleShape),
            )
        },
        isError = !valid,
        supportingText = { Text(stringResource(R.string.scripts_color_hint)) },
        singleLine = true,
    )
}
