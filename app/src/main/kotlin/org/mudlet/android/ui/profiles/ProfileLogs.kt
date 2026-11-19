package org.mudlet.android.ui.profiles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mudlet.android.R
import org.mudlet.android.data.LogFile
import org.mudlet.android.data.LogFiles
import java.io.File
import java.io.IOException

/** The test tag of each log's row in [ProfileLogs]. */
const val LOG_FILE_TAG = "logFile"

// The rest wait for "Show all": one log a session, a profile soon has hundreds
private const val NEWEST_SHOWN = 11

private const val DATE_FLAGS = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH

/**
 * The logs Mudlet wrote of the profile [name]'s games ([LogFiles]), newest first, each with its date and size, a button
 * that opens it in another app and one that offers it to Android's share sheet. Either hands the other app a copy
 * through the app's FileProvider with a read grant for that one file, so the app needs no storage permission.
 */
@Composable
fun ProfileLogs(name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val folder = remember(name) { LogFiles.folder(context.filesDir, name) }
    var logs by remember(folder) { mutableStateOf<List<LogFile>?>(null) }
    LaunchedEffect(folder) { logs = withContext(Dispatchers.IO) { LogFiles.list(folder) } }
    var all by rememberSaveable(name) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.profile_log_failed)
    val title = stringResource(R.string.profile_log_share_title)
    val noApp = stringResource(R.string.profile_log_no_app)
    val noViewer = stringResource(R.string.profile_log_no_viewer)

    fun hand(log: LogFile, open: Boolean) {
        busy = true
        scope.launch {
            val copy = withContext(Dispatchers.IO) { LogShare.copy(context, folder, log.name) }
            busy = false
            when {
                copy == null -> Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                open -> LogShare.open(context, copy, log.mimeType, noViewer)
                else -> LogShare.share(context, copy, log.mimeType, title, noApp)
            }
        }
    }

    Column(modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.profile_logs_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.profile_logs_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        logs?.let { listed ->
            for (log in if (all) listed else listed.take(NEWEST_SHOWN)) {
                LogRow(log, enabled = !busy, onOpen = { hand(log, open = true) }, onShare = { hand(log, open = false) })
            }
            if (!all && listed.size > NEWEST_SHOWN) {
                TextButton({ all = true }, Modifier.heightIn(min = 48.dp)) {
                    Text(pluralStringResource(R.plurals.profile_logs_show_all, listed.size, listed.size))
                }
            }
        }
    }
}

// TalkBack reads the name with its date and size, then each button with the name it acts on
@Composable
private fun LogRow(log: LogFile, enabled: Boolean, onOpen: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    val facts = stringResource(R.string.profile_log_facts, DateUtils.formatDateTime(context, log.modified, DATE_FLAGS), Formatter.formatShortFileSize(context, log.size))
    Row(Modifier.fillMaxWidth().testTag(LOG_FILE_TAG), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onOpen, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_open_in_new), stringResource(R.string.profile_log_open, log.name))
        }
        IconButton(onShare, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_share), stringResource(R.string.profile_log_share, log.name))
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(log.name, style = MaterialTheme.typography.bodyLarge)
            Text(facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// A FileProvider of its own: the manifest cannot name androidx's class twice, and the map files have the other
class LogFileProvider : FileProvider(R.xml.log_files)

/** A log copied where [LogFileProvider] serves it, and handed to another app to open or to Android's share sheet. */
internal object LogShare {
    private const val TAG = "LogShare"
    private const val DIR = "logs"

    /** The log [name] in [folder], copied into the cache in place of earlier copies; null if it cannot be read. */
    fun copy(context: Context, folder: File, name: String): File? = try {
        LogFiles.copy(folder, name, File(context.cacheDir, DIR))
    } catch (e: IOException) {
        Log.w(TAG, "a log could not be copied to hand on: ${e.javaClass.simpleName}")
        null
    }

    fun share(context: Context, copy: File, mimeType: String, title: String, noApp: String) {
        val send = Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri(context, copy))
        start(context, Intent.createChooser(send, title), noApp)
    }

    fun open(context: Context, copy: File, mimeType: String, noViewer: String) {
        start(context, Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, copy), "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), noViewer)
    }

    private fun uri(context: Context, copy: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.$DIR", copy)

    private fun start(context: Context, intent: Intent, noApp: String) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, noApp, Toast.LENGTH_LONG).show()
        }
    }
}
