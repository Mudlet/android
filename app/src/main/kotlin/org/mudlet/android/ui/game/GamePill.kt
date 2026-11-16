package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mudlet.android.R

/** The game pill's test tag. */
const val GAME_PILL_TAG = "gamePill"

/**
 * The game pill, at the left of the quick bar's row (docs/multi-profile.md, "The switcher"): the front game's
 * connection as an icon whose shape says the state, its [name], and how many games are open when more than one. A tap
 * calls [onShowGames], which opens the game sheet. TalkBack reads "<name>, <state>, game <index + 1> of <count>", and
 * offers Reconnect ([onReconnect]), Disconnect ([onDisconnect]) and the [previous] and [next] games ([onSwitch]) as
 * actions, so neither the sheet nor a gesture is ever needed; a null action is left out.
 */
@Composable
fun GamePill(
    name: String,
    state: ConnectionState,
    index: Int,
    count: Int,
    onShowGames: () -> Unit,
    onReconnect: (() -> Unit)?,
    onDisconnect: (() -> Unit)?,
    previous: String?,
    next: String?,
    onSwitch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateText = state.text()
    val description = if (index in 0 until count) {
        stringResource(R.string.game_pill_description, name, stateText, index + 1, count)
    } else {
        stringResource(R.string.game_state, name, stateText)
    }
    val showLabel = stringResource(R.string.game_pill_show)
    val actions = buildList {
        if (onReconnect != null) add(action(stringResource(R.string.connection_reconnect_to, name), onReconnect))
        if (onDisconnect != null) add(action(stringResource(R.string.connection_disconnect_from, name), onDisconnect))
        if (next != null) add(action(stringResource(R.string.game_next, next)) { onSwitch(next) })
        if (previous != null) add(action(stringResource(R.string.game_previous, previous)) { onSwitch(previous) })
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onShowGames,
        modifier = modifier.testTag(GAME_PILL_TAG).clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            onClick(showLabel) {
                onShowGames()
                true
            }
            if (actions.isNotEmpty()) customActions = actions
        },
        shape = CircleShape,
        color = if (state.trouble) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (state.trouble) colors.onErrorContainer else colors.onSecondaryContainer,
    ) {
        Row(
            Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionIcon(state)
            Text(name, Modifier.widthIn(max = 120.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count > 1) {
                Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                    Text(
                        count.toString(),
                        Modifier.sizeIn(minWidth = 20.dp).padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun action(label: String, run: () -> Unit) = CustomAccessibilityAction(label) {
    run()
    true
}

/**
 * The one chip beside the game pill while the game is not connected: Connect, Reconnect, Reconnect now or Try again,
 * which [onClick] does by connecting again, or Stop while it connects. TalkBack reads it with the [game]'s name.
 */
@Composable
fun ConnectionChip(action: ConnectionAction, game: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (label, description) = when (action) {
        ConnectionAction.CONNECT -> stringResource(R.string.connection_connect) to stringResource(R.string.connection_connect_to, game)
        ConnectionAction.RECONNECT -> stringResource(R.string.connection_reconnect) to stringResource(R.string.connection_reconnect_to, game)
        ConnectionAction.RECONNECT_NOW -> stringResource(R.string.connection_reconnect_now) to stringResource(R.string.connection_reconnect_now_to, game)
        ConnectionAction.TRY_AGAIN -> stringResource(R.string.connection_try_again) to stringResource(R.string.connection_try_again_to, game)
        ConnectionAction.STOP -> stringResource(R.string.connection_stop) to stringResource(R.string.connection_stop_to, game)
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = modifier.semantics { contentDescription = description },
    )
}
