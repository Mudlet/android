package org.mudlet.android.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.MapEnv
import org.mudlet.android.protocol.RoomEdit
import org.mudlet.android.protocol.RoomExit
import org.mudlet.android.protocol.RoomInfo
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MapRooms

// The ways a room is moved or added towards, as on a compass, then up and down; null leaves a cell empty
private val PLACE_WAYS = listOf(
    listOf("nw", "n", "ne"),
    listOf("w", null, "e"),
    listOf("sw", "s", "se"),
    listOf("up", null, "down"),
)

// An exit can also lead in or out, which have no place on the map
private val EXIT_WAYS = PLACE_WAYS + listOf(listOf("in", null, "out"))

// How far each of PLACE_WAYS moves a room: east, north and up, as the map counts them
private val STEPS = mapOf(
    "n" to Triple(0, 1, 0),
    "ne" to Triple(1, 1, 0),
    "e" to Triple(1, 0, 0),
    "se" to Triple(1, -1, 0),
    "s" to Triple(0, -1, 0),
    "sw" to Triple(-1, -1, 0),
    "w" to Triple(-1, 0, 0),
    "nw" to Triple(-1, 1, 0),
    "up" to Triple(0, 0, 1),
    "down" to Triple(0, 0, -1),
)

private val SWATCH = 48.dp

private enum class RoomDialog { PROPERTIES, EXITS, ADD, MOVE, AREA, DELETE }

/**
 * What the player can do to the room [info] on an edited map (docs/protocol.md, "Editing a map"), each asked of
 * [rooms]: its name, symbol, colour, weight and lock; its exits; a room beside it; where it is; the player placed in
 * it; its area's name; and deleting it. All but placing the player open a dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoomEditActions(info: RoomInfo, rooms: MapRooms, modifier: Modifier = Modifier) {
    val attached = EngineClient.enginePid > 0
    val haptics = LocalHapticFeedback.current
    // Kept while the room's edits show it anew, and closed for another room, as a room added beside it is
    var dialog by remember(info.id) { mutableStateOf<RoomDialog?>(null) }
    FlowRow(modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            RoomDialog.PROPERTIES to R.string.room_edit_properties,
            RoomDialog.EXITS to R.string.room_edit_exits,
            RoomDialog.ADD to R.string.room_edit_add,
            RoomDialog.MOVE to R.string.room_edit_move,
        ).forEach { (which, label) ->
            AssistChip({ dialog = which }, { Text(stringResource(label)) }, enabled = attached)
        }
        AssistChip(
            { if (rooms.placePlayer(info.id)) haptics.performHapticFeedback(HapticFeedbackType.Confirm) },
            { Text(stringResource(R.string.room_edit_player_here)) },
            enabled = attached && info.steps != 0,
        )
        AssistChip({ dialog = RoomDialog.AREA }, { Text(stringResource(R.string.room_edit_rename_area)) }, enabled = attached)
        AssistChip({ dialog = RoomDialog.DELETE }, { Text(stringResource(R.string.room_edit_delete)) }, enabled = attached)
    }
    val close = { dialog = null }
    when (dialog) {
        RoomDialog.PROPERTIES -> RoomPropertiesDialog(info, rooms, close)
        RoomDialog.EXITS -> RoomExitsDialog(info, rooms, close)
        RoomDialog.ADD -> AddRoomDialog(info, rooms, close)
        RoomDialog.MOVE -> MoveRoomDialog(info, rooms, close)
        RoomDialog.AREA -> RenameAreaDialog(info, rooms, close)
        RoomDialog.DELETE -> DeleteRoomDialog(info, rooms, close)
        null -> {}
    }
}

/** While [exit] waits for the room it leads to: which way it goes, read out as it comes, and a button that cancels it. */
@Composable
internal fun ExitLinkBanner(exit: MapRooms.ExitLink, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.padding(4.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.room_edit_link_prompt, exitWay(exit.direction)),
                Modifier.weight(1f).padding(start = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onCancel, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.room_edit_cancel)) }
        }
    }
}

// The room's own name, or its number where it has none
@Composable
private fun roomName(info: RoomInfo): String = info.name.ifEmpty { stringResource(R.string.room_unnamed, info.id) }

@Composable
private fun RoomPropertiesDialog(info: RoomInfo, rooms: MapRooms, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(info.name) }
    var symbol by remember { mutableStateOf(info.symbol) }
    var weight by remember { mutableStateOf(info.weight.toString()) }
    var locked by remember { mutableStateOf(info.locked) }
    var env by remember { mutableStateOf(info.env) }
    LaunchedEffect(Unit) { rooms.askEnvs() }
    val weightValue = weight.toIntOrNull()?.takeIf { it >= 1 }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                {
                    val edit = RoomEdit(
                        name = name.takeIf { it != info.name },
                        symbol = symbol.takeIf { it != info.symbol },
                        env = env.takeIf { it != info.env },
                        weight = weightValue.takeIf { it != info.weight },
                        locked = locked.takeIf { it != info.locked },
                    )
                    if (edit == RoomEdit() || rooms.edit(info.id, edit)) onDismiss()
                },
                enabled = weightValue != null,
            ) { Text(stringResource(R.string.room_edit_save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.room_edit_cancel)) } },
        title = { Text(roomName(info)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.room_edit_name)) }, singleLine = true)
                OutlinedTextField(symbol, { symbol = it }, label = { Text(stringResource(R.string.room_edit_symbol)) }, singleLine = true)
                OutlinedTextField(
                    weight,
                    { weight = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.room_edit_weight)) },
                    supportingText = { Text(stringResource(R.string.room_edit_weight_hint)) },
                    isError = weightValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                CheckRow(locked, stringResource(R.string.room_edit_locked)) { locked = it }
                Text(stringResource(R.string.room_edit_env), style = MaterialTheme.typography.labelLarge)
                val envs = rooms.envs
                if (envs == null) Text(stringResource(R.string.room_edit_envs_loading)) else EnvPalette(envs, env) { env = it }
            }
        },
    )
}

// The environments [envs] as swatches of their colours, [selected] marked, each picked with [onPick]
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnvPalette(envs: List<MapEnv>, selected: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        envs.forEach { env ->
            val picked = env.env == selected
            val color = Color(env.color)
            val label = stringResource(R.string.room_edit_env_item, env.env)
            Box(
                Modifier
                    .size(SWATCH)
                    .clip(MaterialTheme.shapes.small)
                    .background(color)
                    .border(
                        if (picked) 3.dp else 1.dp,
                        if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        MaterialTheme.shapes.small,
                    )
                    .selectable(picked, role = Role.RadioButton) { onPick(env.env) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                if (picked) Icon(painterResource(R.drawable.ic_check), null, tint = if (color.luminance() > 0.5f) Color.Black else Color.White)
            }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().minimumInteractiveComponentSize().toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

/**
 * [ways] laid out as a compass, each a button that calls [onPick]: [chosen] marked, those not [enabled] greyed, and
 * each read with [state].
 */
@Composable
private fun DirectionGrid(ways: List<List<String?>>, onPick: (String) -> Unit, chosen: String? = null, enabled: (String) -> Boolean = { true }, state: @Composable (String) -> String? = { null }) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ways.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { way ->
                    if (way == null) {
                        Spacer(Modifier.weight(1f))
                        return@forEach
                    }
                    val read = state(way)
                    val cell = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                        selected = way == chosen
                        read?.let { stateDescription = it }
                    }
                    val padding = PaddingValues(4.dp)
                    val label: @Composable () -> Unit = {
                        Text(exitWay(way), textAlign = TextAlign.Center, fontWeight = if (read != null) FontWeight.Bold else null)
                    }
                    if (way == chosen) {
                        FilledTonalButton({ onPick(way) }, cell, enabled = enabled(way), contentPadding = padding) { label() }
                    } else {
                        OutlinedButton({ onPick(way) }, cell, enabled = enabled(way), contentPadding = padding) { label() }
                    }
                }
            }
        }
    }
}

// An exit in words, as the room card shows it
@Composable
private fun exitText(exit: RoomExit): String {
    val name = exit.toName.ifEmpty { stringResource(R.string.room_unnamed, exit.to) }
    return stringResource(if (exit.locked) R.string.room_exit_locked else R.string.room_exit, exitWay(exit.direction), name)
}

/**
 * The room's exits by way: one picked leads to a room given by its number, or tapped on the map next, either with the
 * exit back too, and an exit there is locked, unlocked or removed.
 */
@Composable
private fun RoomExitsDialog(info: RoomInfo, rooms: MapRooms, onDismiss: () -> Unit) {
    val exits = info.exits.associateBy { it.direction }
    var way by remember { mutableStateOf<String?>(null) }
    var to by remember { mutableStateOf("") }
    var bothWays by remember { mutableStateOf(true) }
    val toRoom = to.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.room_edit_done)) } },
        title = { Text(stringResource(R.string.room_edit_exits_title, roomName(info))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DirectionGrid(
                    EXIT_WAYS,
                    {
                        way = it
                        to = exits[it]?.to?.toString().orEmpty()
                    },
                    chosen = way,
                    state = { exits[it]?.let { exit -> exitText(exit) } },
                )
                way?.let { picked ->
                    val exit = exits[picked]
                    Text(
                        exit?.let { exitText(it) } ?: stringResource(R.string.room_edit_no_exit, exitWay(picked)),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        to,
                        { to = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.room_edit_exit_to)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    CheckRow(bothWays, stringResource(R.string.room_edit_both_ways)) { bothWays = it }
                    FlowRowButtons {
                        TextButton({ toRoom?.let { rooms.setExit(info.id, picked, it, bothWays) } }, enabled = toRoom != null) {
                            Text(stringResource(R.string.room_edit_exit_set))
                        }
                        TextButton({
                            rooms.pickExitRoom(MapRooms.ExitLink(info.id, picked, bothWays))
                            onDismiss()
                        }) { Text(stringResource(R.string.room_edit_exit_pick)) }
                        if (exit != null) {
                            TextButton({ rooms.lockExit(info.id, picked, !exit.locked) }) {
                                Text(stringResource(if (exit.locked) R.string.room_edit_exit_unlock else R.string.room_edit_exit_lock))
                            }
                            TextButton({ rooms.setExit(info.id, picked, 0, bothWays) }) { Text(stringResource(R.string.room_edit_exit_remove)) }
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** A new room beside this one, a way no exit goes yet; the new room shows once the engine makes it. */
@Composable
private fun AddRoomDialog(info: RoomInfo, rooms: MapRooms, onDismiss: () -> Unit) {
    val taken = info.exits.map { it.direction }.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.room_edit_cancel)) } },
        title = { Text(stringResource(R.string.room_edit_add_title, roomName(info))) },
        text = {
            DirectionGrid(PLACE_WAYS, { if (rooms.addRoom(info.id, it)) onDismiss() }, enabled = { it !in taken })
        },
    )
}

/** The room moved one place at a time, as each button shows where it then is; a drag of the room on the map does as much at once. */
@Composable
private fun MoveRoomDialog(info: RoomInfo, rooms: MapRooms, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.room_edit_done)) } },
        title = { Text(stringResource(R.string.room_edit_move_title, roomName(info))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.room_edit_position, info.x, info.y, info.z),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                )
                DirectionGrid(PLACE_WAYS, { way ->
                    STEPS[way]?.let { (east, north, up) -> rooms.edit(info.id, RoomEdit(place = Triple(info.x + east, info.y + north, info.z + up))) }
                })
                Text(stringResource(R.string.room_edit_move_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
private fun RenameAreaDialog(info: RoomInfo, rooms: MapRooms, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(info.area) }
    val wanted = name.trim().takeIf { it.isNotEmpty() && it != info.area }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({ wanted?.let { if (rooms.renameArea(info.areaId, it)) onDismiss() } }, enabled = wanted != null) {
                Text(stringResource(R.string.room_edit_save))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.room_edit_cancel)) } },
        title = { Text(stringResource(R.string.room_edit_rename_area_title)) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.room_edit_area_name)) }, singleLine = true) },
    )
}

@Composable
private fun DeleteRoomDialog(info: RoomInfo, rooms: MapRooms, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({ if (rooms.delete(info.id)) onDismiss() }) { Text(stringResource(R.string.room_edit_delete_confirm)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.room_edit_cancel)) } },
        title = { Text(stringResource(R.string.room_edit_delete_title, roomName(info))) },
        text = { Text(stringResource(R.string.room_edit_delete_text)) },
    )
}
