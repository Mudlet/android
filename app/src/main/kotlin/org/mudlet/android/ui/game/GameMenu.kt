package org.mudlet.android.ui.game

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.mudlet.android.R

/**
 * The game screen's title bar menu, last on the bar: Reconnect to [reconnectGame] ([onReconnect]), Disconnect from
 * [game] ([onDisconnect]), or Stop connecting to it while [connecting]; then the search of the game's text
 * ([onSearch]), the packages of [profile] ([onOpenPackages]) and the settings ([onOpenSettings]). A null action is left
 * out; [modifier] is the menu button's. As buttons of their own they leave the title no room on a 320 dp wide screen.
 */
@Composable
internal fun GameMenuButton(
    profile: String,
    game: String,
    reconnectGame: String,
    connecting: Boolean,
    onReconnect: (() -> Unit)?,
    onDisconnect: (() -> Unit)?,
    onSearch: () -> Unit,
    onOpenPackages: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    @Composable
    fun Item(text: String, @DrawableRes icon: Int, onClick: () -> Unit) = DropdownMenuItem(
        text = { Text(text) },
        onClick = {
            expanded = false
            onClick()
        },
        leadingIcon = { Icon(painterResource(icon), null) },
    )

    Box {
        IconButton({ expanded = true }, modifier) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.game_menu)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (onReconnect != null) Item(stringResource(R.string.connection_reconnect_to, reconnectGame), R.drawable.ic_refresh, onReconnect)
            if (onDisconnect != null) {
                val label = if (connecting) R.string.connection_stop_to else R.string.connection_disconnect_from
                Item(stringResource(label, game), R.drawable.ic_close, onDisconnect)
            }
            if (onReconnect != null || onDisconnect != null) HorizontalDivider()
            Item(stringResource(R.string.console_search), R.drawable.ic_search, onSearch)
            Item(stringResource(R.string.packages_open, profile), R.drawable.ic_inventory_2, onOpenPackages)
            Item(stringResource(R.string.settings_title), R.drawable.ic_settings, onOpenSettings)
        }
    }
}
