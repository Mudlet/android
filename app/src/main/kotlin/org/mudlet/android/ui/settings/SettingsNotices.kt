package org.mudlet.android.ui.settings

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import org.mudlet.android.R
import org.mudlet.android.ui.EngineClient

/**
 * Says that a setting changed in one of [games] while it is connected takes effect at its next connection: once for
 * the settings changed while it shows, and not again on the next screen where the player left this one meanwhile. Only
 * one of these is shown at a time, or it would be said twice.
 */
@Composable
internal fun SettingsNotices(games: List<String>) {
    val host = remember { SnackbarHostState() }
    val message = stringResource(R.string.settings_next_connection)
    val waiting = games.firstNotNullOfOrNull { name -> EngineClient.session(name)?.takeIf { it.settingAwaitsConnection } }
    LaunchedEffect(waiting) {
        if (waiting == null) return@LaunchedEffect
        try {
            host.showSnackbar(message, withDismissAction = true)
        } finally {
            waiting.settingAwaitsConnectionShown()
        }
    }
    SnackbarHost(host)
}
