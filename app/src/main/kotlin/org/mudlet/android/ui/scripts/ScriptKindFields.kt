package org.mudlet.android.ui.scripts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.BarPlace
import org.mudlet.android.protocol.KeyModifier
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.ui.SectionHeader

// Two of a timer's four fields fit side by side on the narrowest phone
private const val TIME_FIELDS_IN_A_ROW = 2

private const val CSS_LINES = 3

@get:StringRes
private val TimePart.label: Int
    get() = when (this) {
        TimePart.HOURS -> R.string.scripts_timer_hours
        TimePart.MINUTES -> R.string.scripts_timer_minutes
        TimePart.SECONDS -> R.string.scripts_timer_seconds
        TimePart.MILLISECONDS -> R.string.scripts_timer_milliseconds
    }

/** A timer's time, in the four fields the desktop editor has, and what it sends each time it fires. */
@Composable
internal fun TimerFields(timer: ScriptItemDetails.Timer, onChange: (ScriptItemDetails.Timer) -> Unit) {
    TimerTime(timer, onChange)
    CommandField(R.string.scripts_timer_command, timer.command) { onChange(timer.copy(command = it)) }
}

/** A timer's time, in the four fields the desktop editor has, under what it is: how often, or how long after its timer. */
@Composable
internal fun TimerTime(timer: ScriptItemDetails.Timer, onChange: (ScriptItemDetails.Timer) -> Unit) {
    SectionHeader(stringResource(if (timer.offset) R.string.scripts_timer_after else R.string.scripts_timer_every), fieldWidth)
    for (parts in TimePart.entries.chunked(TIME_FIELDS_IN_A_ROW)) {
        Row(fieldWidth, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (part in parts) {
                NumberField(stringResource(part.label), timer.time.timePart(part), 0..part.max, Modifier.weight(1f)) {
                    onChange(timer.copy(time = timer.time.withTimePart(part, it)))
                }
            }
        }
    }
    if (timer.offset) Note(R.string.scripts_timer_offset)
}

@get:StringRes
private val KeyModifier.label: Int
    get() = when (this) {
        KeyModifier.SHIFT -> R.string.scripts_key_shift
        KeyModifier.CONTROL -> R.string.scripts_key_control
        KeyModifier.ALT -> R.string.scripts_key_alt
        KeyModifier.META -> R.string.scripts_key_meta
        KeyModifier.KEYPAD -> R.string.scripts_key_keypad
    }

@StringRes
private fun keyNameOf(key: Int): Int? = when (key) {
    QtKeys.ESCAPE -> R.string.key_escape
    QtKeys.TAB -> R.string.key_tab
    QtKeys.BACKSPACE -> R.string.key_backspace
    QtKeys.RETURN -> R.string.key_return
    QtKeys.ENTER -> R.string.key_enter
    QtKeys.INSERT -> R.string.key_insert
    QtKeys.DELETE -> R.string.key_delete
    QtKeys.PAUSE -> R.string.key_pause
    QtKeys.HOME -> R.string.key_home
    QtKeys.END -> R.string.key_end
    QtKeys.LEFT -> R.string.key_left
    QtKeys.UP -> R.string.key_up
    QtKeys.RIGHT -> R.string.key_right
    QtKeys.DOWN -> R.string.key_down
    QtKeys.PAGE_UP -> R.string.key_page_up
    QtKeys.PAGE_DOWN -> R.string.key_page_down
    QtKeys.SPACE -> R.string.key_space
    else -> null
}

/** What the key is called: in the player's language where the app has a name, else as the engine wrote it, else by its number. */
@Composable
private fun keyLabel(key: Int, engineName: String = ""): String = when (key) {
    0 -> stringResource(R.string.scripts_key_none)
    else -> keyNameOf(key)?.let { stringResource(it) } ?: QtKeys.symbol(key) ?: engineName.ifEmpty { stringResource(R.string.scripts_key_code, key.toString(16)) }
}

/** The keys [binding] is pressed with, as "Control + F1": the keys held, then the key. */
@Composable
internal fun keyCombination(binding: ScriptItemDetails.Key): String = (heldKeys(binding.modifiers).map { stringResource(it.label) } + keyLabel(binding.key, binding.engineKeyName)).joinToString(" + ")

/**
 * A key binding's key, picked from a sheet of those a keyboard has, the keys held with it, and what it sends. A key the
 * sheet does not have, set on the desktop, stays until another is picked.
 */
@Composable
internal fun KeyFields(binding: ScriptItemDetails.Key, onChange: (ScriptItemDetails.Key) -> Unit) {
    KeyPick(binding, onChange)
    CommandField(R.string.scripts_key_command, binding.command) { onChange(binding.copy(command = it)) }
}

/** A key binding's key, picked from a sheet of those a keyboard has, and the keys held with it. */
@Composable
internal fun KeyPick(binding: ScriptItemDetails.Key, onChange: (ScriptItemDetails.Key) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.scripts_key_binding), fieldWidth)
    OutlinedButton({ picking = true }, fieldWidth) { Text(stringResource(R.string.scripts_key_pick, keyLabel(binding.key, binding.engineKeyName))) }
    val held = heldKeys(binding.modifiers)
    FlowRow(fieldWidth, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (modifier in KeyModifier.entries) {
            FilterChip(
                modifier in held,
                { onChange(binding.copy(modifiers = withHeld(binding.modifiers, modifier, modifier !in held))) },
                label = { Text(stringResource(modifier.label)) },
            )
        }
    }
    if (picking) {
        TitledSheet(stringResource(R.string.scripts_key_sheet), stringResource(R.string.scripts_key_sheet_close), onDismiss = { picking = false }) {
            FlowRow(
                Modifier.selectableGroup().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (key in listOf(0) + QtKeys.PICKABLE) {
                    FilterChip(
                        key == binding.key,
                        {
                            picking = false
                            onChange(binding.copy(key = key))
                        },
                        label = { Text(keyLabel(key)) },
                    )
                }
            }
        }
    }
}

@get:StringRes
private val BarPlace.label: Int
    get() = when (this) {
        BarPlace.TOP -> R.string.scripts_bar_top
        BarPlace.LEFT -> R.string.scripts_bar_left
        BarPlace.RIGHT -> R.string.scripts_bar_right
        BarPlace.FLOATING -> R.string.scripts_bar_floating
    }

/**
 * What the desktop editor asks of each [part] of the buttons: a button's commands, a toolbar's place, direction and
 * columns, and a style sheet for each of them and for a menu. A package's folder has nothing to change.
 */
@Composable
internal fun ButtonFields(button: ScriptItemDetails.Button, part: ButtonPart, onChange: (ScriptItemDetails.Button) -> Unit) {
    when (part) {
        ButtonPart.PACKAGE -> {
            Note(R.string.scripts_button_package)
            return
        }

        ButtonPart.TOOLBAR -> {
            SectionHeader(stringResource(R.string.scripts_bar_place), fieldWidth)
            Column(fieldWidth.selectableGroup()) {
                for (place in BarPlace.entries) {
                    Choice(stringResource(place.label), button.location == place) { onChange(button.copy(location = place)) }
                }
            }
            SwitchField(R.string.scripts_bar_vertical, R.string.scripts_bar_vertical_hint, button.vertical) { onChange(button.copy(vertical = it)) }
            NumberField(stringResource(R.string.scripts_bar_columns), button.columns, 1..MAX_BAR_COLUMNS, fieldWidth) { onChange(button.copy(columns = it)) }
        }

        ButtonPart.MENU -> Unit

        ButtonPart.BUTTON -> {
            CommandField(R.string.scripts_button_command, button.commandDown) { onChange(button.copy(commandDown = it)) }
            ButtonPushDown(button, onChange)
            if (button.pushDown) CommandField(R.string.scripts_button_command_up, button.commandUp) { onChange(button.copy(commandUp = it)) }
        }
    }
    ButtonCss(button, onChange)
}

/** Whether a button stays down when pressed, until it is pressed again. */
@Composable
internal fun ButtonPushDown(button: ScriptItemDetails.Button, onChange: (ScriptItemDetails.Button) -> Unit) {
    SwitchField(R.string.scripts_button_push_down, R.string.scripts_button_push_down_hint, button.pushDown) { onChange(button.copy(pushDown = it)) }
}

/** The style sheet of a button, a menu or a toolbar. */
@Composable
internal fun ButtonCss(button: ScriptItemDetails.Button, onChange: (ScriptItemDetails.Button) -> Unit) {
    OutlinedTextField(
        button.css,
        { onChange(button.copy(css = it)) },
        fieldWidth.padding(top = 8.dp),
        label = { Text(stringResource(R.string.scripts_button_css)) },
        supportingText = { Text(stringResource(R.string.scripts_button_css_hint)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        minLines = CSS_LINES,
    )
}

/** What an item sends to the game as it is. */
@Composable
internal fun CommandField(@StringRes label: Int, command: String, onCommand: (String) -> Unit) {
    OutlinedTextField(command, onCommand, fieldWidth.padding(top = 8.dp), label = { Text(stringResource(label)) }, singleLine = true)
}

@Composable
internal fun Note(@StringRes text: Int) {
    Text(
        stringResource(text),
        fieldWidth.padding(horizontal = 4.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
