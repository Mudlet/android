package org.mudlet.android.ui.profiles

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mudlet.android.R
import org.mudlet.android.data.ProfileArchive
import java.io.IOException
import java.time.LocalDate

/**
 * The profile editor's Export: the profile [name] as a `.zip` desktop Mudlet can open, written where the player picks
 * through Android's own file picker, so the app needs no storage permission. An [open] game is still writing its
 * profile, which is whole only once it has closed and saved.
 */
@Composable
fun ProfileExport(name: String, open: Boolean) {
    val context = LocalContext.current.applicationContext
    val state = Exports[name]
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ProfileArchive.MIME_TYPE)) { uri ->
        if (uri != null) Exports.start(context, name, uri)
    }
    // A finished export's outcome is shown until the editor goes, by Back or a rotation; a running one outlives it
    DisposableEffect(name) {
        onDispose { Exports.forgetEnded(name) }
    }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.profile_export_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.profile_export_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (open) {
            Text(stringResource(R.string.profile_export_close_first), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(
            { launcher.launch(ProfileArchive.fileName(name, LocalDate.now())) },
            enabled = state !is ExportState.Running,
        ) { Text(stringResource(R.string.profile_export)) }
        val status = when (state) {
            null -> null
            ExportState.Running -> stringResource(R.string.profile_exporting)
            ExportState.Done -> null
            is ExportState.Failed -> stringResource(R.string.profile_export_failed, state.why)
        }
        status?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium) }
    }
}

internal sealed interface ExportState {
    data object Running : ExportState

    data object Done : ExportState

    data class Failed(val why: String) : ExportState
}

/**
 * The exports under way and just ended, by profile, in the app's process rather than the editor's composition: a
 * rotation recreates the activity, and Back can leave the editor, while a large profile is still being written.
 */
internal object Exports {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val states = mutableStateMapOf<String, ExportState>()

    operator fun get(name: String): ExportState? = states[name]

    /** Forgets the outcome of [name]'s export once it has ended. */
    fun forgetEnded(name: String) {
        if (states[name] !is ExportState.Running) states.remove(name)
    }

    fun start(context: Context, name: String, uri: Uri) {
        states[name] = ExportState.Running
        scope.launch {
            val problem = withContext(Dispatchers.IO) { exportProfile(context, name, uri) }
            states[name] = if (problem == null) ExportState.Done else ExportState.Failed(problem)
        }
    }
}

/** Writes the profile [name] to [uri]; why it could not, null once done. A file left half written is deleted. */
internal fun exportProfile(context: Context, name: String, uri: Uri): String? = try {
    val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("the file could not be opened")
    out.use { ProfileArchive.export(ProfileArchive.folder(context.filesDir, name), it) }
    null
} catch (e: IOException) {
    deleteHalfWritten(context, uri)
    e.message ?: e.toString()
} catch (e: SecurityException) {
    deleteHalfWritten(context, uri)
    e.message ?: e.toString()
}

private fun deleteHalfWritten(context: Context, uri: Uri) {
    if (!DocumentsContract.isDocumentUri(context, uri)) return
    try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (e: Exception) {
        // The picker's provider may not delete; the player sees the export failed and can remove the file
        Log.w("ProfileExport", "could not delete the half-written export: $e")
    }
}
