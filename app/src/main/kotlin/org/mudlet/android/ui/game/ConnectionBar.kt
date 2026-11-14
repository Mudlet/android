package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.mudlet.android.R

/** The game connection as status `connection` gives it (docs/protocol.md, "Status keys"). */
sealed interface ConnectionState {
    /** The UI is not attached to the engine, so it knows nothing of the game. */
    data object NoEngine : ConnectionState
    data object Disconnected : ConnectionState
    data class Connecting(val target: String?) : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val reason: String) : ConnectionState

    /** A value this app does not know yet, shown as the engine sent it. */
    data class Other(val value: String) : ConnectionState

    companion object {
        /** [connection] is null when the engine has never connected, which reads as disconnected. */
        fun of(engineAttached: Boolean, connection: String?): ConnectionState = when {
            !engineAttached -> NoEngine
            connection == null || connection == "disconnected" -> Disconnected
            connection == "connected" -> Connected
            connection == "connecting" -> Connecting(null)
            connection.startsWith("connecting ") -> Connecting(connection.removePrefix("connecting "))
            connection.startsWith("error: ") -> Failed(connection.removePrefix("error: "))
            else -> Other(connection)
        }
    }
}

/**
 * The connection's state, which TalkBack reads out when it changes, with Reconnect (to the last address, when there
 * is one) and Disconnect.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionBar(state: ConnectionState, canReconnect: Boolean, onReconnect: () -> Unit, onDisconnect: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (state) {
        ConnectionState.NoEngine -> stringResource(R.string.connection_no_engine)

        ConnectionState.Disconnected -> stringResource(R.string.connection_disconnected)

        is ConnectionState.Connecting -> if (state.target == null) {
            stringResource(R.string.connection_connecting)
        } else {
            stringResource(R.string.connection_connecting_to, state.target)
        }

        ConnectionState.Connected -> stringResource(R.string.connection_connected)

        is ConnectionState.Failed -> stringResource(R.string.connection_error, state.reason)

        is ConnectionState.Other -> state.value
    }
    val engineAttached = state != ConnectionState.NoEngine
    // FlowRow, so that at a large font size the buttons go below the state rather than squeeze it
    FlowRow(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text,
            Modifier.align(Alignment.CenterVertically).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.align(Alignment.CenterVertically)) {
            TextButton(onReconnect, enabled = engineAttached && canReconnect) {
                Text(stringResource(R.string.connection_reconnect))
            }
            TextButton(onDisconnect, enabled = engineAttached && state != ConnectionState.Disconnected) {
                Text(stringResource(R.string.connection_disconnect))
            }
        }
    }
}
