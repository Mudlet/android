package org.mudlet.android.ui.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ProfileSession

/** The game sheet's test tag. */
const val GAME_SHEET_TAG = "gameSheet"

/**
 * The game sheet, which the game pill opens (docs/multi-profile.md, "The switcher"): first a card for the game in
 * front, [front] (named [name]), with its state, its address, Reconnect ([onReconnect], while [canReconnect]) and
 * Disconnect ([onDisconnect]); then a row for each of the open [games], in the order opened, the front one ticked, which
 * a tap brings to the front ([onSwitch]) and whose close button closes it ([onClose]) after asking first when it is
 * connected; last "Open another game" ([onOpenAnother]), which says why not once [EngineClient.MAX_OPEN_GAMES] are
 * open. Every button but a game's close button closes the sheet once it has acted, as do the sheet's own close
 * button, Back and a tap outside it; [onDismiss] is called once it has gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSheet(
    front: ProfileSession,
    name: String,
    games: List<ProfileSession>,
    canReconnect: Boolean,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSwitch: (String) -> Unit,
    onClose: (String) -> Unit,
    onOpenAnother: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // The game whose close waits for the player to confirm it
    var closing by rememberSaveable { mutableStateOf<String?>(null) }

    fun hide() {
        scope.launch { sheet.hide() }.invokeOnCompletion { if (!sheet.isVisible) onDismiss() }
    }

    fun andHide(action: () -> Unit): () -> Unit = {
        action()
        hide()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(GAME_SHEET_TAG),
        sheetState = sheet,
        // No handle: its full-width target is shorter than 48 dp, and the close button and Back do what it offers
        dragHandle = null,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.games_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                IconButton(::hide) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.games_close)) }
            }
            FrontCard(front, name, canReconnect, andHide(onReconnect), andHide(onDisconnect))
            for (game in games) {
                val state = ConnectionState.of(game)
                GameRow(
                    game.name,
                    state,
                    isFront = game === front,
                    onSwitch = andHide { onSwitch(game.name) },
                    onClose = { if (state.live) closing = game.name else onClose(game.name) },
                )
            }
            OpenAnother(full = games.size >= EngineClient.MAX_OPEN_GAMES, onOpenAnother = andHide(onOpenAnother))
        }
    }
    closing?.let { game ->
        AlertDialog(
            onDismissRequest = { closing = null },
            confirmButton = {
                TextButton({
                    closing = null
                    onClose(game)
                }) { Text(stringResource(R.string.game_close_confirm)) }
            },
            dismissButton = { TextButton({ closing = null }) { Text(stringResource(R.string.game_close_cancel)) } },
            title = { Text(stringResource(R.string.game_close_title, game)) },
            text = { Text(stringResource(R.string.game_close_text)) },
        )
    }
}

@Composable
private fun FrontCard(front: ProfileSession, name: String, canReconnect: Boolean, onReconnect: () -> Unit, onDisconnect: () -> Unit) {
    val state = ConnectionState.of(front)
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = if (state.trouble) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ConnectionIcon(state, size = 20.dp)
                Text(name, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(state.text(), style = MaterialTheme.typography.bodyMedium)
            front.lastAddress?.let { address ->
                Text(
                    stringResource(R.string.profile_address, address.host, address.port),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val reconnect = stringResource(R.string.connection_reconnect_to, name)
                val disconnect = stringResource(R.string.connection_disconnect_from, name)
                FilledTonalButton(onReconnect, Modifier.semantics { contentDescription = reconnect }, enabled = canReconnect) {
                    Text(stringResource(R.string.connection_reconnect))
                }
                OutlinedButton(onDisconnect, Modifier.semantics { contentDescription = disconnect }, enabled = front.name.isNotEmpty() && state.canDisconnect) {
                    Text(stringResource(R.string.connection_disconnect))
                }
            }
        }
    }
}

/** An open game: its name and state, which a tap brings to the front, ticked while it is; then its close button. */
@Composable
private fun GameRow(name: String, state: ConnectionState, isFront: Boolean, onSwitch: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val stateText = state.text()
        val description = stringResource(R.string.game_state, name, stateText)
        Row(
            Modifier.weight(1f).heightIn(min = 56.dp)
                .clickable(onClickLabel = stringResource(R.string.game_show), role = Role.Button, onClick = onSwitch)
                .semantics { selected = isFront }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (isFront) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = description }) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ConnectionIcon(state, size = 12.dp)
                    Text(stateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        IconButton(onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.game_close, name)) }
    }
}

@Composable
private fun OpenAnother(full: Boolean, onOpenAnother: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = !full, role = Role.Button, onClick = onOpenAnother)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (full) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
        Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = color)
        Column {
            Text(stringResource(R.string.game_open_another), color = color, style = MaterialTheme.typography.bodyLarge)
            if (full) {
                Text(
                    pluralStringResource(R.plurals.game_open_another_full, EngineClient.MAX_OPEN_GAMES, EngineClient.MAX_OPEN_GAMES),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
