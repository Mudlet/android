package org.mudlet.android.ui.scripts.designs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.settings.AppSettings
import org.mudlet.android.ui.settings.ChoiceButtons
import org.mudlet.android.ui.settings.SettingEntry
import org.mudlet.android.ui.settings.settingsCatalogue

private val designChoice by lazy {
    settingsCatalogue.filterIsInstance<SettingEntry.Choice<*>>().single { it.setting == Settings.scriptEditorDesign }
}

/**
 * The row last in every design's library that names the design the editor has, Settings > Experimental's "Script editor
 * design", marked experimental. A tap lists the designs as that page does, and a pick changes the setting, which the
 * editor follows at once. At the top, it would push the items down, off a small phone's screen.
 */
@Composable
internal fun EditorDesignRow(modifier: Modifier = Modifier) {
    val entry = designChoice
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier.heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.settings_choose), role = Role.Button) { picking = true }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_auto_fix_high), contentDescription = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(stringResource(entry.label), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(entry.choiceLabels()[entry.chosen(AppSettings)]), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.settings_experimental), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
    if (!picking) return
    AlertDialog(
        onDismissRequest = { picking = false },
        confirmButton = {},
        dismissButton = { TextButton({ picking = false }) { Text(stringResource(R.string.settings_choice_cancel)) } },
        title = { Text(stringResource(entry.label)) },
        // The designs' lines under them make the list taller than a small phone
        text = { ChoiceButtons(entry, AppSettings, Modifier.verticalScroll(rememberScrollState()), inset = 0.dp) { picking = false } },
    )
}
