package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import org.mudlet.android.R

/**
 * The game screen's title bar menu, last on the bar: the search of the game's text ([onSearch]) and the packages of
 * [profile] ([onOpenPackages]). As buttons of their own they leave the title no room on a 320 dp wide screen, and the
 * bar grows as tall as the title then wraps.
 */
@Composable
internal fun GameMenuButton(profile: String, onSearch: () -> Unit, onOpenPackages: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.game_menu)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.console_search)) },
                onClick = {
                    expanded = false
                    onSearch()
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.packages_open, profile), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = {
                    expanded = false
                    onOpenPackages()
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_inventory_2), null) },
            )
        }
    }
}
