package org.mudlet.android.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ProfileSession

/**
 * The game connection as the player sees it (docs/multi-profile.md, "The switcher"): status `connection`
 * (docs/protocol.md, "Status keys"), and whether it ended without the player's Disconnect ([ProfileSession.dropped]).
 */
sealed interface ConnectionState {
    /** The UI is not attached to the engine, so it knows nothing of the game. */
    data object NoEngine : ConnectionState

    /** Not connected, by the player's Disconnect or as it has not connected yet. */
    data object Disconnected : ConnectionState
    data class Connecting(val target: String?) : ConnectionState
    data object Connected : ConnectionState

    /** It dropped, and the profile's "Reconnect automatically" has Mudlet dial again on its own. */
    data object Reconnecting : ConnectionState

    /** It dropped, and nothing dials again until the player does. */
    data object Lost : ConnectionState
    data class Failed(val reason: String) : ConnectionState

    /** A value this app does not know yet, shown as the engine sent it. */
    data class Other(val value: String) : ConnectionState

    companion object {
        /**
         * [connection] is null when the engine has never connected, which reads as disconnected. While [dropped], Mudlet's
         * own retries (with [autoReconnect]) read as [Reconnecting], not as each attempt's connecting and disconnected.
         */
        fun of(engineAttached: Boolean, connection: String?, dropped: Boolean = false, autoReconnect: Boolean = false): ConnectionState = when {
            !engineAttached -> NoEngine
            connection == "connected" -> Connected
            dropped && autoReconnect -> Reconnecting
            connection == null || connection == "disconnected" -> if (dropped) Lost else Disconnected
            connection == "connecting" -> Connecting(null)
            connection.startsWith("connecting ") -> Connecting(connection.removePrefix("connecting "))
            connection.startsWith("error: ") -> Failed(connection.removePrefix("error: "))
            else -> Other(connection)
        }

        /** [session]'s connection, as the engine and the profile's own settings have it. */
        fun of(session: ProfileSession): ConnectionState = of(EngineClient.enginePid > 0, session.status["connection"], session.dropped, session.autoReconnect)
    }
}

/** The connection ended without the player asking: the game title says so in the error colour, and a banner shows. */
val ConnectionState.trouble: Boolean
    get() = this == ConnectionState.Lost || this == ConnectionState.Reconnecting || this is ConnectionState.Failed

/** Connected, or on the way to it: closing the game would end a connection. */
val ConnectionState.live: Boolean
    get() = this == ConnectionState.Connected || this is ConnectionState.Connecting || this == ConnectionState.Reconnecting

/** Whether Disconnect has a connection, or Mudlet's retries, to stop. */
val ConnectionState.canDisconnect: Boolean
    get() = this != ConnectionState.NoEngine && this != ConnectionState.Disconnected && this != ConnectionState.Lost

/** The state in words, as the game sheet shows it and TalkBack reads it. */
@Composable
fun ConnectionState.text(): String = when (this) {
    ConnectionState.NoEngine -> stringResource(R.string.connection_no_engine)

    ConnectionState.Disconnected -> stringResource(R.string.connection_disconnected)

    is ConnectionState.Connecting -> if (target == null) {
        stringResource(R.string.connection_connecting)
    } else {
        stringResource(R.string.connection_connecting_to, target)
    }

    ConnectionState.Connected -> stringResource(R.string.connection_connected)

    ConnectionState.Reconnecting -> stringResource(R.string.connection_reconnecting)

    ConnectionState.Lost -> stringResource(R.string.connection_lost)

    is ConnectionState.Failed -> stringResource(R.string.connection_error, reason)

    is ConnectionState.Other -> value
}

/**
 * The state as a small icon whose shape, not only its colour, differs per state: a filled dot while connected, an
 * open ring while disconnected, a three-quarter arc while connecting, an arc ending in an arrow while Mudlet
 * reconnects, a struck-through ring once lost, a ring with a bang when it failed, a short arc while the engine is
 * away. Decoration: the words go with it.
 */
@Composable
fun ConnectionIcon(state: ConnectionState, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val colors = MaterialTheme.colorScheme
    val color = when {
        state.trouble -> colors.error
        state == ConnectionState.Connected -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val stroke = Stroke(width = this.size.minDimension / 8, cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val ring = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        val topLeft = Offset(inset, inset)
        when (state) {
            ConnectionState.Connected -> drawCircle(color, radius = this.size.minDimension * 0.4f)

            ConnectionState.Disconnected, is ConnectionState.Other -> drawCircle(color, radius = ring.minDimension / 2, style = stroke)

            is ConnectionState.Connecting -> drawArc(color, -90f, 270f, false, topLeft, ring, style = stroke)

            ConnectionState.NoEngine -> drawArc(color, -90f, 90f, false, topLeft, ring, style = stroke)

            ConnectionState.Reconnecting -> {
                drawArc(color, -60f, 300f, false, topLeft, ring, style = stroke)
                arrowHead(color, stroke.width)
            }

            ConnectionState.Lost -> {
                drawCircle(color, radius = ring.minDimension / 2, style = stroke)
                drawLine(color, Offset(inset * 2, this.size.height - inset * 2), Offset(this.size.width - inset * 2, inset * 2), stroke.width, StrokeCap.Round)
            }

            is ConnectionState.Failed -> {
                drawCircle(color, radius = ring.minDimension / 2, style = stroke)
                val x = this.size.width / 2
                drawLine(color, Offset(x, this.size.height * 0.28f), Offset(x, this.size.height * 0.56f), stroke.width, StrokeCap.Round)
                drawCircle(color, radius = stroke.width * 0.6f, center = Offset(x, this.size.height * 0.72f))
            }
        }
    }
}

// The arrow at the arc's start, at the top right, pointing round the ring
private fun DrawScope.arrowHead(color: Color, width: Float) {
    val tip = Offset(size.width * 0.78f, size.height * 0.06f)
    val path = Path().apply {
        moveTo(tip.x - width * 2.2f, tip.y - width * 0.4f)
        lineTo(tip.x + width * 0.6f, tip.y - width * 0.6f)
        lineTo(tip.x - width * 0.2f, tip.y + width * 2.2f)
        close()
    }
    drawPath(path, color)
}
