package org.mudlet.android.ui.game

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mudlet.android.R
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.ui.ProfileSession
import java.io.File
import java.io.IOException

/** The test tag of the game sheet's button that picks a replay file to play. */
const val PLAY_REPLAY_TAG = "playReplay"

/** The replay bar's test tag. */
const val REPLAY_BAR_TAG = "replayBar"

/** The highest speed desktop Mudlet's replay plays at, in times its recorded pace. */
private const val FASTEST = 1024

/**
 * Desktop Mudlet's replay button on the game sheet's card for [front], while no replay plays: Android's file picker,
 * then the file chosen played by the engine, and [onStarted] once it has been asked to.
 */
@Composable
internal fun PlayReplayButton(front: ProfileSession, onStarted: () -> Unit) {
    if (front.replay.playing) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.game_play_replay_failed)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copy = withContext(Dispatchers.IO) { ReplayFiles.copyPicked(context, uri) }
            if (copy == null) {
                Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
            } else if (front.playReplay(copy.path)) {
                onStarted()
            }
        }
    }
    // A replay has no media type of its own: desktop Mudlet saves it as .dat
    OutlinedButton({ pick.launch(arrayOf("*/*")) }, Modifier.testTag(PLAY_REPLAY_TAG), enabled = front.settingsAvailable) {
        Text(stringResource(R.string.game_play_replay))
    }
}

/**
 * Desktop Mudlet's replay toolbar for [replay], while it plays: its speed, read by TalkBack as it changes, and pause or
 * resume ([onPaused]), slower and faster ([onFaster]) and stop ([onStop]).
 */
@Composable
internal fun ReplayBar(replay: EngineMessage.Replay, onPaused: (Boolean) -> Unit, onFaster: (Boolean) -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.testTag(REPLAY_BAR_TAG), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (replay.paused) R.string.replay_bar_paused else R.string.replay_bar_playing, replay.speed),
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge,
            )
            BarButton(if (replay.paused) R.drawable.ic_play else R.drawable.ic_pause, if (replay.paused) R.string.replay_resume else R.string.replay_pause) {
                onPaused(!replay.paused)
            }
            BarButton(R.drawable.ic_fast_rewind, R.string.replay_slower, enabled = replay.speed > 1) { onFaster(false) }
            BarButton(R.drawable.ic_fast_forward, R.string.replay_faster, enabled = replay.speed < FASTEST) { onFaster(true) }
            BarButton(R.drawable.ic_stop, R.string.replay_stop, onClick = onStop)
        }
    }
}

@Composable
private fun BarButton(icon: Int, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

/** A replay file the player picked, copied where the engine can read it. */
internal object ReplayFiles {
    private const val TAG = "ReplayFiles"
    private const val DIR = "played-replays"

    /** The document [uri] copied into the cache in place of earlier copies, which no replay plays from then; null if it cannot be read. */
    fun copyPicked(context: Context, uri: Uri): File? {
        val dir = File(context.cacheDir, DIR)
        return try {
            dir.listFiles()?.forEach(File::delete)
            dir.mkdirs()
            val copy = File(dir, "replay.dat")
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
            input.use { from -> copy.outputStream().use { from.copyTo(it) } }
            copy
        } catch (e: IOException) {
            Log.w(TAG, "a picked replay could not be copied: ${e.javaClass.simpleName}")
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "a picked replay could not be read: ${e.javaClass.simpleName}")
            null
        }
    }
}
