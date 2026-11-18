package org.mudlet.android.ui.game

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mudlet.android.R
import org.mudlet.android.data.ProfileArchive
import org.mudlet.android.ui.ProfileSession
import java.io.File
import java.io.IOException

/** The replay recording switch's test tag. */
const val RECORD_REPLAY_TAG = "recordReplay"

/** The test tag of the button that shares the last replay. */
const val SHARE_REPLAY_TAG = "shareReplay"

/**
 * Desktop Mudlet's replay recording button as a switch on the game sheet's card for [front], on while the engine
 * records; once a recording has been saved, and while none runs, a button that offers its file to Android's share
 * sheet, for desktop Mudlet's replay to play.
 */
@Composable
internal fun ReplayRecording(front: ProfileSession) {
    val recording = front.replayRecording
    SheetSwitch(
        R.drawable.ic_record,
        stringResource(R.string.game_record_replay),
        stringResource(if (recording.recording) R.string.game_record_replay_on else R.string.game_record_replay_off),
        checked = recording.recording,
        enabled = front.settingsAvailable,
        tag = RECORD_REPLAY_TAG,
        onChange = front::setReplayRecording,
    )
    if (!recording.recording && recording.file.isNotEmpty()) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val title = stringResource(R.string.game_share_replay_title)
        val failed = stringResource(R.string.game_share_replay_failed)
        val noApp = stringResource(R.string.game_share_replay_no_app)
        OutlinedButton(
            {
                scope.launch {
                    val copy = withContext(Dispatchers.IO) { ReplayShare.copy(context, front.name, recording.file) }
                    if (copy == null) Toast.makeText(context, failed, Toast.LENGTH_LONG).show() else ReplayShare.offer(context, copy, title, noApp)
                }
            },
            Modifier.padding(start = 40.dp).testTag(SHARE_REPLAY_TAG),
        ) {
            Text(stringResource(R.string.game_share_replay))
        }
    }
}

/** A saved replay copied where the app's FileProvider serves it, and offered to Android's share sheet. */
internal object ReplayShare {
    private const val TAG = "ReplayShare"
    private const val DIR = "replays"
    private const val MIME_TYPE = "application/octet-stream"

    /** The profile [profile]'s replay [file], from its log folder, copied into the cache in place of earlier copies; null if it cannot be read. */
    fun copy(context: Context, profile: String, file: String): File? {
        val source = File(File(ProfileArchive.folder(context.filesDir, profile), "log"), file)
        val dir = File(context.cacheDir, DIR)
        return try {
            dir.listFiles()?.forEach(File::delete)
            source.copyTo(File(dir, source.name), overwrite = true)
        } catch (e: IOException) {
            Log.w(TAG, "a replay could not be copied to share: ${e.javaClass.simpleName}")
            null
        }
    }

    fun offer(context: Context, copy: File, title: String, noApp: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.$DIR", copy)
        val send = Intent(Intent.ACTION_SEND).setType(MIME_TYPE).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(send, title))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, noApp, Toast.LENGTH_LONG).show()
        }
    }
}
