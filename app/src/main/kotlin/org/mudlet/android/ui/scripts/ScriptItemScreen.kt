package org.mudlet.android.ui.scripts

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
 * toolbar's place) and its Lua, edited here as [LuaField] has it, with Mudlet's [functions] offered and [LuaKeys] over
 * the keyboard while the code has it. What the player changes is a draft, kept by [scripts] until Apply saves it as the
 * desktop editor's save does, Lua that does not compile too: what is wrong with it then shows over the buttons until
 * the code is changed, with a button that goes to its line. [onGame] goes to the game and keeps the draft, to come back
 * to, and Back asks whether to discard it. Why the item does not run shows at the top. Move puts it last in a folder
 * picked from [moveTargets]. Delete hides it with Undo offered by the library, and goes [onBack].
 */
@Composable
internal fun ScriptItemScreen(scripts: ScriptItemsClient, key: ScriptItemKey, functions: List<LuaFunction>, onBack: () -> Unit, onGame: () -> Unit) {
    val page = rememberItemPage(scripts, key)
    val item = page.item
    val dirty = page.dirty
    var leaving by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val listed = page.listed
    BackHandler(enabled = dirty) { leaving = true }
    val unnamed = stringResource(R.string.scripts_unnamed)
    val name = item?.node?.name?.ifEmpty { unnamed }
    val lua = rememberLuaFieldState(scripts, key, functions)
    val luaFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()

    MudletScaffold(
        name ?: stringResource(R.string.scripts_loading),
        onBack = { if (dirty) leaving = true else onBack() },
        // Beside the library, the library's show them
        snackbarHost = { if (!LocalInDetailPane.current) ScriptNotices(scripts) {} },
        actions = {
            if (item != null) ItemActions(scripts, page, name.orEmpty(), onMove = { moving = true }, onBack)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp).testTag(SCRIPT_ITEM_TAG),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (item != null) {
                    ItemFields(item, listed, page.error, scripts::edit)
                    LuaField(lua, scroll.viewportSize, luaFocus, fieldWidth.padding(vertical = 16.dp))
                } else {
                    ItemNotOpen(page, fieldWidth.padding(16.dp))
                }
            }
            scripts.luaProblem(key)?.let { found ->
                LuaProblemLine(found) {
                    lua.goTo(found.line)
                    luaFocus.requestFocus()
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Row(fieldWidth, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GameButton(onGame)
                            Button({ scripts.apply(key) }, Modifier.weight(1f), enabled = dirty) { Text(stringResource(R.string.scripts_apply)) }
                        }
                    }
                    if (item != null && lua.focused) LuaKeys(lua)
                }
            }
        }
    }

    ItemDialogs(scripts, page, name.orEmpty(), moving, leaving, onClose = {
        moving = false
        leaving = false
    }, onBack)
}

/**
 * The item [key] as its page shows it, from [scripts]: as the engine gave it, as the player has changed it, and the
 * folders it can move to.
 */
@Stable
internal class ItemPage(private val scripts: ScriptItemsClient, val key: ScriptItemKey) {
    val saved: ScriptItem? get() = scripts.saved[key]

    /** The draft, as the player has changed it; [saved] where there is none. */
    val item: ScriptItem? get() = scripts.drafts[key] ?: saved

    val dirty: Boolean get() = key in scripts.drafts

    /** Why the item does not run, as the engine said when it last gave it. */
    val error: String get() = saved?.node?.error.orEmpty()

    /** Why the engine would not give the item, until it is asked again. */
    val refusal: String? get() = scripts.unopened[key]

    // Without a folder deleted with its Undo offered, still listed while the library beside it is open: what moved in would go with it
    val listed: List<ScriptItemNode> get() = scripts.lists[key.kind].orEmpty().withoutHidden(key.kind, scripts.deleting)

    /** Where the item can be moved to ([moveTargets]). */
    val targets: List<ScriptItemNode?> get() = listed.let { all -> all.firstOrNull { it.id == key.id }?.let { all.moveTargets(key.kind, it) }.orEmpty() }
}

/** The [ItemPage] of [key], whose item is asked for as the page comes. */
@Composable
internal fun rememberItemPage(scripts: ScriptItemsClient, key: ScriptItemKey): ItemPage {
    // Asked again each time, as a script may have changed it since, and once the engine is back; a draft stays over what comes
    LaunchedEffect(key, scripts.attaches) {
        scripts.open(key)
        // Where the item is, which a button's fields and the folders it can move to depend on, only the list says
        if (scripts.lists[key.kind] == null) scripts.ask(key.kind)
    }
    return remember(scripts, key) { ItemPage(scripts, key) }
}

/** What an item's page says until the engine has given the item: that it is coming, or why the engine would not. */
@Composable
internal fun ItemNotOpen(page: ItemPage, modifier: Modifier) {
    val refusal = page.refusal
    if (refusal != null) {
        Text(stringResource(R.string.scripts_refused_open, refusal), modifier, color = MaterialTheme.colorScheme.error)
    } else {
        Text(stringResource(R.string.scripts_loading), modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An item's page's title-bar buttons: Move, where it can go elsewhere, and Delete, which goes [onBack], with Undo offered by the library. */
@Composable
internal fun ItemActions(scripts: ScriptItemsClient, page: ItemPage, name: String, onMove: () -> Unit, onBack: () -> Unit) {
    if (page.targets.isNotEmpty()) IconButton(onMove) { Icon(painterResource(R.drawable.ic_drive_file_move), stringResource(R.string.scripts_move, name)) }
    IconButton({
        scripts.deleteLater(page.key, name)
        onBack()
    }) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.scripts_delete, name)) }
}

/** The button that goes to the game, keeping what is changed to come back to. */
@Composable
internal fun GameButton(onGame: () -> Unit) {
    val toGame = stringResource(R.string.scripts_back_to_game)
    OutlinedButton(onGame, Modifier.semantics { contentDescription = toGame }) {
        Icon(painterResource(R.drawable.ic_videogame_asset), contentDescription = null)
        Text(stringResource(R.string.game_title), Modifier.padding(start = 8.dp))
    }
}

/**
 * What an item's page opens over it: while [moving], the sheet of where it can go; while [leaving], whether to drop what
 * was changed, which goes [onBack] when it is. Either closes with [onClose].
 */
@Composable
internal fun ItemDialogs(scripts: ScriptItemsClient, page: ItemPage, name: String, moving: Boolean, leaving: Boolean, onClose: () -> Unit, onBack: () -> Unit) {
    if (moving && page.targets.isNotEmpty()) {
        MoveSheet(name, page.listed, page.targets, onDismiss = onClose) { parent ->
            onClose()
            scripts.move(page.key, parent)
        }
    }
    if (leaving) {
        DiscardDialog(
            name,
            onDiscard = {
                onClose()
                scripts.discard(page.key)
                onBack()
            },
            onKeep = onClose,
        )
    }
}

/** Asks whether to drop what was changed in the item named [name]. */
@Composable
private fun DiscardDialog(name: String, onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        confirmButton = { TextButton(onDiscard) { Text(stringResource(R.string.scripts_discard)) } },
        dismissButton = { TextButton(onKeep) { Text(stringResource(R.string.scripts_keep_editing)) } },
        title = { Text(stringResource(R.string.scripts_discard_title, name)) },
        text = { Text(stringResource(R.string.scripts_discard_text)) },
    )
}

/** Why the item does not run, as the engine says it; nothing for one that runs. */
@Composable
internal fun NotRunningBanner(error: String, modifier: Modifier) {
    if (error.isEmpty()) return
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
        Text(stringResource(R.string.scripts_not_running, error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
internal fun NameField(item: ScriptItem, onChange: (ScriptItem) -> Unit) {
    OutlinedTextField(
        item.node.name,
        { onChange(item.copy(node = item.node.copy(name = it))) },
        fieldWidth.padding(top = 8.dp),
        label = { Text(stringResource(R.string.scripts_name)) },
        singleLine = true,
    )
}

/** All of an item's page but its Lua: why it does not run, from [error], its name and what its kind has ([KindFields]). */
@Composable
internal fun ColumnScope.ItemFields(item: ScriptItem, listed: List<ScriptItemNode>, error: String, onChange: (ScriptItem) -> Unit) {
    NotRunningBanner(error, fieldWidth.padding(top = 8.dp))
    NameField(item, onChange = onChange)
    KindFields(item, listed, onChange)
}

/**
 * What [item]'s kind has, [listed] with it: a trigger's patterns, its options unless [triggerOptions] is false, and its
 * command; an alias's pattern and command; a script's event handlers; a timer's time, a key's key, each with its
 * command; a button's commands or a toolbar's place.
 */
@Composable
internal fun ColumnScope.KindFields(item: ScriptItem, listed: List<ScriptItemNode>, onChange: (ScriptItem) -> Unit, triggerOptions: Boolean = true) {
    when (val details = item.details) {
        is ScriptItemDetails.Trigger -> {
            val change = { to: ScriptItemDetails.Trigger -> onChange(item.copy(details = to)) }
            TriggerPatterns(details, change)
            if (triggerOptions) TriggerOptions(details, change)
            TriggerCommand(details, change)
        }

        is ScriptItemDetails.Alias -> {
            val change = { to: ScriptItemDetails.Alias -> onChange(item.copy(details = to)) }
            AliasPattern(details, change)
            AliasCommand(details, change)
        }

        is ScriptItemDetails.Script -> EventHandlers(details) { onChange(item.copy(details = it)) }

        is ScriptItemDetails.Timer -> if (!item.node.folder) TimerFields(details) { onChange(item.copy(details = it)) }

        is ScriptItemDetails.Key -> if (!item.node.folder) KeyFields(details) { onChange(item.copy(details = it)) }

        is ScriptItemDetails.Button -> ButtonFields(details, listed.buttonPart(item.node)) { onChange(item.copy(details = it)) }
    }
}

/** A trigger's patterns, each with its type, which a sheet explains and picks, and a button that adds one. */
@Composable
internal fun ColumnScope.TriggerPatterns(trigger: ScriptItemDetails.Trigger, onChange: (ScriptItemDetails.Trigger) -> Unit) {
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
            onChange(trigger.copy(patterns = patterns.toMutableList().also { it[typed] = typedPattern.withType(type) }))
        }
    }
}

/** A trigger's options, under their heading: which patterns must match and over how many lines, and what it does to the line. */
@Composable
internal fun ColumnScope.TriggerOptions(trigger: ScriptItemDetails.Trigger, onChange: (ScriptItemDetails.Trigger) -> Unit) {
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
}

@Composable
internal fun TriggerCommand(trigger: ScriptItemDetails.Trigger, onChange: (ScriptItemDetails.Trigger) -> Unit) {
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
 * its type takes: a spacer's number of lines, none for a prompt, and a colour pattern's two colours.
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

            TriggerPatternType.COLOR -> ColorPatternFields(number, ColorPattern.parse(pattern.text), Modifier.fillMaxWidth().padding(end = 8.dp)) { onText(it.text) }

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

private val SCRIM_MIN_HEIGHT = 48.dp

/** A sheet of things to pick from, under its [title] and a button that closes it, which a screen reader reads as [close]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TitledSheet(title: String, close: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // No handle: its touch target is narrower than 48 dp, and the close button and Back do what it offers
        dragHandle = null,
    ) {
        // Never as tall as the screen, which would leave the scrim, which closes the sheet at a tap, a strip under 48 dp:
        // the status bar counted twice, as the sheet may pad its content by it too
        Column(Modifier.heightIn(max = window - statusBar * 2 - SCRIM_MIN_HEIGHT)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                IconButton(onDismiss) { Icon(painterResource(R.drawable.ic_close), close) }
            }
            content()
        }
    }
}

/** The sheet that explains each pattern type and picks one. */
@Composable
private fun PatternTypeSheet(current: TriggerPatternType, onDismiss: () -> Unit, onPick: (TriggerPatternType) -> Unit) {
    TitledSheet(stringResource(R.string.scripts_pattern_type), stringResource(R.string.scripts_pattern_type_close), onDismiss) {
        Column(Modifier.selectableGroup().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            for (type in TriggerPatternType.entries) {
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
internal fun ColumnScope.AliasPattern(alias: ScriptItemDetails.Alias, onChange: (ScriptItemDetails.Alias) -> Unit) {
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
}

@Composable
internal fun AliasCommand(alias: ScriptItemDetails.Alias, onChange: (ScriptItemDetails.Alias) -> Unit) {
    OutlinedTextField(
        alias.command,
        { onChange(alias.copy(command = it)) },
        fieldWidth,
        label = { Text(stringResource(R.string.scripts_alias_command)) },
        singleLine = true,
    )
}

@Composable
internal fun ColumnScope.EventHandlers(script: ScriptItemDetails.Script, onChange: (ScriptItemDetails.Script) -> Unit) {
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
private fun NumberField(@StringRes label: Int, value: Int, range: IntRange, onValue: (Int) -> Unit) = NumberField(stringResource(label), value, range, fieldWidth, onValue = onValue)

/**
 * A whole number in [range]; while what is typed is not one, the field says so, [value] stays as it was, and [onInvalid]
 * is told.
 */
@Composable
internal fun NumberField(label: String, value: Int, range: IntRange, modifier: Modifier, onInvalid: () -> Unit = {}, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val typed = text.toIntOrNull()
    OutlinedTextField(
        text,
        {
            text = it
            it.toIntOrNull()?.takeIf { n -> n in range }?.let(onValue) ?: onInvalid()
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
