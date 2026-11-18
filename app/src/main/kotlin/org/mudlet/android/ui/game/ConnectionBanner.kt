package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.mudlet.android.R

/** The connection banner's test tag. */
const val CONNECTION_BANNER_TAG = "connectionBanner"

/**
 * The banner under the game screen's title bar while a connection the player did not end is being dealt with: lost
 * while Mudlet dials [game] again on its own, with Stop ([onStop]) and Reconnect now; lost with nothing dialling again,
 * with Reconnect; or failed, with the reason and Try again. Reconnect, Reconnect now and Try again call [onReconnect]; a
 * null action has no button. Nothing shows in any other state, so not after the player's own Disconnect. Its message
 * is a polite live region, read as it changes while it shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionBanner(state: ConnectionState, game: String, onReconnect: (() -> Unit)?, onStop: (() -> Unit)?, modifier: Modifier = Modifier) {
    val message = when (state) {
        ConnectionState.Reconnecting -> stringResource(R.string.connection_banner_reconnecting, game)
        ConnectionState.Lost -> stringResource(R.string.connection_banner_lost, game)
        is ConnectionState.Failed -> stringResource(R.string.connection_banner_failed, game, state.reason)
        else -> return
    }
    val colors = MaterialTheme.colorScheme
    Surface(modifier.testTag(CONNECTION_BANNER_TAG), color = colors.errorContainer, contentColor = colors.onErrorContainer) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp)) {
            Text(message, Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (state == ConnectionState.Reconnecting && onStop != null) {
                    BannerButton(stringResource(R.string.connection_stop), stringResource(R.string.connection_stop_to, game), onStop)
                }
                if (onReconnect != null) {
                    when (state) {
                        ConnectionState.Reconnecting -> BannerButton(stringResource(R.string.connection_reconnect_now), stringResource(R.string.connection_reconnect_now_to, game), onReconnect)
                        ConnectionState.Lost -> BannerButton(stringResource(R.string.connection_reconnect), stringResource(R.string.connection_reconnect_to, game), onReconnect)
                        else -> BannerButton(stringResource(R.string.connection_try_again), stringResource(R.string.connection_try_again_to, game), onReconnect)
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerButton(label: String, description: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.semantics { contentDescription = description }) {
        Text(label, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
