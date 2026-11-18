package org.mudlet.android.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.game.ConnectionState

/**
 * What a `telnet://` link opened from outside the app does, as desktop's handleTelnetUri: the player's profile at its
 * address, or the game of Mudlet's there they have no profile for yet, connects once they have said yes to a question
 * that names the address, as desktop's connectFromGameLink asks; any other address opens a new game's editor filled
 * in from the link ([onNewProfile], with the name to suggest), which connects nothing. A link never changes a saved
 * profile. Nothing shows until the engine has listed its profiles since the UI attached: a list from an earlier
 * attach may be out of date. [onDone] once the link is dealt with.
 */
@Composable
fun GameLinkPrompt(link: TelnetLink, onDone: () -> Unit, onOpenGame: () -> Unit, onNewProfile: (link: TelnetLink, name: String) -> Unit) {
    val profiles = EngineClient.profiles
    val games = rememberDefaultGamesRead()
    if (!EngineClient.profilesKnown || profiles == null || games == null) return
    // Not recomputed as the profiles change: adding Mudlet's game makes it a profile, and the question stays the one asked
    val profile = remember(link) { link.profileIn(profiles) }
    val game = remember(link) { if (profile == null) link.gameIn(DefaultGames.offered(games, profiles)) else null }
    when {
        profile != null -> LinkQuestion(link, profile.name, null, onDone, onOpenGame)

        game != null -> LinkQuestion(link, game.name, game, onDone, onOpenGame)

        else -> LaunchedEffect(link) {
            onNewProfile(link, link.newProfile(profiles.map { it.name } + games.map { it.name })?.name.orEmpty())
            onDone()
        }
    }
}

// [game] when [name] is one of Mudlet's games the player has yet to add
@Composable
private fun LinkQuestion(link: TelnetLink, name: String, game: DefaultGame?, onDone: () -> Unit, onOpenGame: () -> Unit) {
    val context = LocalContext.current
    val passwords = remember { PasswordStore(context) }
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val notAttached = stringResource(R.string.profiles_error_not_attached)

    fun answered(refused: String?) {
        waiting = false
        if (refused != null) {
            error = refused
            return
        }
        onOpenGame()
        onDone()
    }

    // As the profile list's Connect: an open game comes to the front, and connects to its own address if it is not
    // connected, as desktop dials an open profile that is not
    fun connect() {
        val session = EngineClient.session(name)
        if (session == null) {
            val open = if (EngineClient.sessions.isEmpty()) EngineClient::openProfile else EngineClient::openProfileAlongside
            open(name, passwords.get(name), ::answered)
            return
        }
        if (!EngineClient.setFront(session.name)) return answered(notAttached)
        val state = ConnectionState.of(session)
        if (state == ConnectionState.Disconnected || state == ConnectionState.Lost || state is ConnectionState.Failed) {
            session.forgetAddress()
            if (!session.reconnect()) return answered(notAttached)
        }
        answered(null)
    }

    val question = stringResource(if (game == null) R.string.game_link_question_profile else R.string.game_link_question_game, link.address, name)
    AlertDialog(
        onDismissRequest = { if (!waiting) onDone() },
        title = { Text(stringResource(R.string.game_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(question)
                error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = null
                    waiting = true
                    if (game == null) {
                        connect()
                    } else {
                        EngineClient.saveProfile(game.newProfile()) { refused -> if (refused == null) connect() else answered(refused) }
                    }
                },
                enabled = !waiting,
            ) { Text(stringResource(R.string.game_link_connect)) }
        },
        dismissButton = { TextButton(onDone, enabled = !waiting) { Text(stringResource(R.string.game_link_cancel)) } },
    )
}

/** Says a link from outside the app was no game's address, so nothing was opened, as desktop opens nothing for one. */
@Composable
fun GameLinkRefused(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_link_refused_title)) },
        text = { Text(stringResource(R.string.game_link_refused_text)) },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.game_link_refused_close)) } },
    )
}
