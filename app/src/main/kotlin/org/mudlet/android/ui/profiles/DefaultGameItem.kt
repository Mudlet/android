package org.mudlet.android.ui.profiles

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONException
import org.mudlet.android.R
import org.mudlet.android.ui.ListSegment
import org.mudlet.android.ui.about.openLink
import org.mudlet.android.ui.about.readAsset
import org.mudlet.android.ui.inBackground
import java.io.IOException
import java.text.Collator

private const val TAG = "DefaultGames"

// Desktop draws the logos at 120 x 30; a row here has less room beside its text
private val LOGO_WIDTH = 96.dp
private val LOGO_HEIGHT = 24.dp

/** Mudlet's games from the APK's assets, sorted by name for the player's language; empty, and logged, if unreadable. */
@Composable
fun rememberDefaultGames(): List<DefaultGame> = rememberDefaultGamesRead() ?: emptyList()

/** As [rememberDefaultGames], but null until they have been read. */
@Composable
fun rememberDefaultGamesRead(): List<DefaultGame>? {
    val context = LocalContext.current
    val games by produceState(GameAssets.games) {
        if (GameAssets.games == null) value = inBackground { GameAssets.loadGames(context) }
    }
    return games
}

/** A game of Mudlet's the player has no profile for: its logo, name and summary. A tap connects to it; ⓘ tells of it. */
@Composable
fun DefaultGameItem(game: DefaultGame, index: Int, count: Int, enabled: Boolean, onConnect: () -> Unit, onInfo: () -> Unit) {
    val summary = game.summary.ifEmpty { stringResource(R.string.profile_address, game.host, game.port) }
    val description = stringResource(R.string.default_game_description, game.name, summary)
    ListSegment(index, count) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .clickable(enabled, onClickLabel = stringResource(R.string.profile_connect_to, game.name), role = Role.Button, onClick = onConnect)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GameLogo(game.icon, Modifier.size(LOGO_WIDTH, LOGO_HEIGHT))
            // TalkBack reads the whole summary, which the row may cut short
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = description }) {
                Text(game.name, style = MaterialTheme.typography.titleMedium)
                // Cut short on purpose, and the game's own untranslated English: kept out of the semantics, even the
                // unmerged tree, so PseudoLocaleTest checks only the app's text; the row's description reads it whole
                Text(
                    summary,
                    Modifier.clearAndSetSemantics {},
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onInfo) {
                Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.default_game_info, game.name))
            }
        }
    }
}

/** What [game] is: its logo, address, description and links, with Connect. */
@Composable
fun DefaultGameInfo(game: DefaultGame, onConnect: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(game.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GameLogo(game.icon, Modifier.size(LOGO_WIDTH * 2, LOGO_HEIGHT * 2))
                Text(
                    stringResource(if (game.tls) R.string.default_game_address_secure else R.string.profile_address, game.host, game.port),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (game.description.isNotEmpty()) Text(game.description, style = MaterialTheme.typography.bodyMedium)
                game.links.forEach { link ->
                    TextButton({ openLink(uriHandler, context, link.url) }) { Text(link.label) }
                }
            }
        },
        confirmButton = { TextButton(onConnect) { Text(stringResource(R.string.profile_connect)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.default_game_close)) } },
    )
}

// Decorative: the name beside it says what it shows
@Composable
private fun GameLogo(file: String, modifier: Modifier) {
    val context = LocalContext.current
    val logo by produceState(GameAssets.cachedLogo(file), file) {
        if (value == null) value = inBackground { GameAssets.loadLogo(context, file) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        logo?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit) }
    }
}

// Read once each, off the main thread; the list's ~50 logos decoded are under 3 MB
private object GameAssets {
    @Volatile
    var games: List<DefaultGame>? = null
        private set

    private val logos = LruCache<String, ImageBitmap>(64)

    fun loadGames(context: Context): List<DefaultGame> = try {
        val collator = Collator.getInstance()
        DefaultGames.parse(readAsset(context, DefaultGames.FILE)).sortedWith(compareBy(collator) { it.name }).also { games = it }
    } catch (e: IOException) {
        unreadable(e)
    } catch (e: JSONException) {
        unreadable(e)
    }

    // A broken APK: scripts/sync-mudlet-games.py --check and DefaultGamesTest check the file
    private fun unreadable(e: Exception): List<DefaultGame> {
        Log.e(TAG, "Could not read ${DefaultGames.FILE}", e)
        return emptyList()
    }

    fun cachedLogo(file: String): ImageBitmap? = logos.get(file)

    fun loadLogo(context: Context, file: String): ImageBitmap? = try {
        val bitmap = context.assets.open("${DefaultGames.ICON_DIR}/$file").use { BitmapFactory.decodeStream(it) }
        if (bitmap == null) Log.e(TAG, "Could not decode the logo $file")
        bitmap?.asImageBitmap()?.also { logos.put(file, it) }
    } catch (e: IOException) {
        Log.e(TAG, "Could not read the logo $file", e)
        null
    }
}
