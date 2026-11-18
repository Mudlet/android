package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.AddonCommand
import org.mudlet.android.protocol.CommandEntry
import org.mudlet.android.protocol.CommandMenu
import org.mudlet.android.ui.EngineClient

/** Whether [ScriptCommandsButton] shows: the profile's packages added commands the GUI shows. */
@Composable
internal fun scriptCommandsShown(): Boolean {
    val commands = EngineClient.front.windows.commands
    return remember(commands) { CommandMenu.of(commands).isNotEmpty() }
}

/**
 * The top bar's Extensions button, while the profile's packages added commands the GUI shows: a menu of them, nested by
 * their menu paths, as [CommandMenu] lists them. A tap sends `activateCommand`; a checkable command is ticked while
 * checked, and one that pulses has a dot, as does the button. Commands are off while there is no engine to tell.
 */
@Composable
fun ScriptCommandsButton() {
    val commands = EngineClient.front.windows.commands
    val entries = remember(commands) { CommandMenu.of(commands) }
    if (entries.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    // The submenus opened, from the outermost
    var opened by remember(expanded) { mutableStateOf(emptyList<CommandEntry.Submenu>()) }
    val pulsing = commands.any { it.pulse && (it.onMenu || it.onToolbar) }

    Box {
        IconButton({ expanded = true }) {
            val description = stringResource(if (pulsing) R.string.commands_menu_attention else R.string.commands_menu)
            BadgedBox(badge = { if (pulsing) Badge() }) { Icon(painterResource(R.drawable.ic_extension), description) }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (opened.isNotEmpty()) MenuBackItem { opened = opened.dropLast(1) }
            for (entry in opened.lastOrNull()?.entries ?: entries) {
                when (entry) {
                    is CommandEntry.Submenu -> SubmenuItem(entry.name) { opened = opened + entry }

                    is CommandEntry.Command -> CommandItem(entry.command) {
                        expanded = false
                        EngineClient.front.windows.activateCommand(entry.command.id)
                    }
                }
            }
        }
    }
}

/** A command of the menu: its icon, name and tool tip, its tick while checked, its dot while it pulses. */
@Composable
private fun CommandItem(command: AddonCommand, onClick: () -> Unit) {
    val attention = stringResource(R.string.command_attention)
    val ticked = command.checkable && command.checked
    DropdownMenuItem(
        text = {
            Column {
                Text(command.name)
                if (command.tooltip.isNotEmpty()) Text(command.tooltip, style = MaterialTheme.typography.bodySmall)
            }
        },
        onClick = onClick,
        modifier = if (command.checkable) Modifier.semantics { toggleableState = ToggleableState(command.checked) } else Modifier,
        enabled = command.enabled && EngineClient.enginePid > 0,
        leadingIcon = if (command.icon.isNotEmpty()) {
            { EngineIcon(command.icon) }
        } else {
            null
        },
        trailingIcon = if (command.pulse || ticked) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ticked) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                    if (command.pulse) Badge(Modifier.semantics { contentDescription = attention })
                }
            }
        } else {
            null
        },
    )
}
