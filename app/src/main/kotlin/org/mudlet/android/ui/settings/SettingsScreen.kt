package org.mudlet.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.CONSOLE_FOREGROUND
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MudletTopBar
import kotlin.math.roundToInt

/** The app's settings, one section each, in a column that scrolls at large font sizes. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(topBar = { MudletTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            TextSize(Settings.textSize)
            ScreenReader()
        }
    }
}

/** The screen reader settings, which are Mudlet's own: each profile that loads is given the app's values. */
@Composable
private fun ScreenReader() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_screen_reader), style = MaterialTheme.typography.titleMedium)
        SwitchRow(Settings.readNewText, R.string.settings_read_new_text, R.string.settings_read_new_text_hint)
        SwitchRow(Settings.advertiseScreenReader, R.string.settings_advertise_screen_reader, R.string.settings_advertise_screen_reader_hint)
        BlankLines(Settings.blankLines)
    }
}

/** A switch the whole row turns, which TalkBack reads as [label], then [hint]. */
@Composable
private fun SwitchRow(setting: Settings.BoolSetting, @StringRes label: Int, @StringRes hint: Int) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(setting.value, role = Role.Switch) { EngineClient.setMudletSetting(setting, it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
        }
        Switch(setting.value, onCheckedChange = null)
    }
}

/** Show, hide or replace with a space the blank lines a game sends: one radio button each, in a group. */
@Composable
private fun BlankLines(setting: Settings.EnumSetting<Settings.BlankLines>) {
    Text(stringResource(R.string.settings_blank_lines), style = MaterialTheme.typography.bodyLarge)
    Column(Modifier.selectableGroup()) {
        for (choice in setting.entries) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(choice == setting.value, role = Role.RadioButton) { EngineClient.setMudletSetting(setting, choice) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(choice == setting.value, onClick = null)
                Text(stringResource(blankLinesLabel(choice)), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@StringRes
private fun blankLinesLabel(choice: Settings.BlankLines) = when (choice) {
    Settings.BlankLines.SHOW -> R.string.settings_blank_lines_show
    Settings.BlankLines.HIDE -> R.string.settings_blank_lines_hide
    Settings.BlankLines.SPACE -> R.string.settings_blank_lines_space
}

/** The console's text size: a slider, with buttons a step either way, and a sample at that size. */
@Composable
private fun TextSize(setting: Settings.IntSetting) {
    val size = setting.value
    val range = setting.range
    val label = stringResource(R.string.settings_text_size)
    val value = stringResource(R.string.settings_text_size_value, size)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_text_size_hint), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ setting.set(size - 1) }, enabled = size > range.first) {
                Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.settings_text_smaller))
            }
            Slider(
                value = size.toFloat(),
                onValueChange = { setting.set(it.roundToInt()) },
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = label
                    stateDescription = value
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = range.last - range.first - 1,
            )
            IconButton({ setting.set(size + 1) }, enabled = size < range.last) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.settings_text_larger))
            }
        }
        Text(value, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.settings_text_size_sample),
            Modifier.fillMaxWidth().background(CONSOLE_BACKGROUND).padding(8.dp),
            color = CONSOLE_FOREGROUND,
            fontFamily = FontFamily.Monospace,
            fontSize = size.sp,
        )
    }
}
