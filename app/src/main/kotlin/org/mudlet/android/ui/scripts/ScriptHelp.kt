package org.mudlet.android.ui.scripts

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.mudlet.android.R
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.ui.about.Help
import org.mudlet.android.ui.about.openLink

/**
 * The script library's Help button: a menu of the manual's introduction to [kind], as desktop's script editor links
 * to it, the Lua functions and the manual, each opened in the browser.
 */
@Composable
internal fun ScriptHelpButton(kind: ScriptItemKind) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Box {
        IconButton({ expanded = true }) { Icon(painterResource(R.drawable.ic_help), stringResource(R.string.scripts_help)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (link in listOf(Help.introduction(kind), Help.LUA_FUNCTIONS, Help.MANUAL)) {
                DropdownMenuItem(
                    text = { Text(stringResource(link.label)) },
                    onClick = {
                        expanded = false
                        openLink(uriHandler, context, link.url)
                    },
                )
            }
        }
    }
}
