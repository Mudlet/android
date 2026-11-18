package org.mudlet.android.ui.profiles

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import org.mudlet.android.ui.EngineClient
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.util.UUID

/**
 * The profile list's Import: a `.zip` of one profile's folder, as Export writes it or as a player zips desktop's, picked
 * through Android's own file picker, so the app needs no storage permission. It is unpacked and checked away from the
 * engine's profiles, then added under a name the player confirms, which is never one a profile already has.
 * [ProfileImportDialog] goes outside the list, which disposes this row whenever it scrolls away or moves.
 */
@Composable
fun ProfileImport(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val state = Imports.state
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Imports.start(context, uri)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            // Some providers call a .zip only a file of bytes; a file that is not a zip is refused as one
            { launcher.launch(arrayOf(ProfileArchive.MIME_TYPE, "application/x-zip-compressed", "application/octet-stream")) },
            enabled = state == null || state.ended,
        ) { Text(stringResource(R.string.profiles_import)) }
        val status = when (state) {
            null, is ImportState.Ready -> null
            ImportState.Unpacking -> stringResource(R.string.profile_import_unpacking)
            ImportState.Installing -> stringResource(R.string.profile_importing)
            is ImportState.Done -> stringResource(R.string.profile_imported, state.name)
            is ImportState.Refused -> stringResource(state.refusal.text())
            is ImportState.Failed -> stringResource(R.string.profile_import_failed, state.why)
        }
        status?.let {
            val color = if (state is ImportState.Refused || state is ImportState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The naming dialog of an import; leaving the profile list forgets an import that has ended. */
@Composable
fun ProfileImportDialog() {
    val context = LocalContext.current.applicationContext
    val state = Imports.state
    DisposableEffect(Unit) {
        onDispose { Imports.forgetEnded() }
    }
    if (state is ImportState.Ready) ImportDialog(state, onImport = { Imports.install(context, state, it) }, onCancel = { Imports.cancel(state) })
}

@Composable
private fun ImportDialog(state: ImportState.Ready, onImport: (name: String) -> Unit, onCancel: () -> Unit) {
    val names = state.folders + EngineClient.profiles.orEmpty().map { it.name }
    val archived = state.folder.name
    var name by rememberSaveable(state.folder.path) {
        mutableStateOf(
            when {
                !ProfileArchive.usableName(archived) -> ""
                ProfileArchive.taken(names, archived) == null -> archived
                else -> ProfileArchive.freeName(names, archived)
            },
        )
    }
    val taken = ProfileArchive.taken(names, name)
    val usable = ProfileArchive.usableName(name)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.profile_import_title, archived)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.profile_import_text))
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(stringResource(R.string.profile_name)) },
                    singleLine = true,
                    isError = taken != null || !usable,
                    supportingText = when {
                        taken != null -> ({ Text(stringResource(R.string.profile_import_taken, taken)) })
                        !usable -> ({ Text(stringResource(R.string.profile_import_name_rules)) })
                        else -> null
                    },
                )
            }
        },
        confirmButton = { TextButton({ onImport(name) }, enabled = taken == null && usable) { Text(stringResource(R.string.profile_import)) } },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.profile_import_cancel)) } },
    )
}

private fun ProfileArchive.Refusal.text() = when (this) {
    ProfileArchive.Refusal.NOT_A_ZIP -> R.string.profile_import_not_a_zip
    ProfileArchive.Refusal.UNREADABLE_NAME -> R.string.profile_import_unreadable_name
    ProfileArchive.Refusal.NOT_ONE_FOLDER -> R.string.profile_import_not_one_folder
    ProfileArchive.Refusal.UNSAFE_PATH -> R.string.profile_import_unsafe_path
    ProfileArchive.Refusal.NO_SAVE -> R.string.profile_import_no_save
    ProfileArchive.Refusal.TOO_LARGE -> R.string.profile_import_too_large
}

internal sealed interface ImportState {
    /** Whether the player can start another import. */
    val ended get() = this is Done || this is Refused || this is Failed

    data object Unpacking : ImportState

    /**
     * Unpacked into [folder], under the name the archive gave it, for the player to name; [folders] are the profile
     * folders there were then, which the engine has not listed while it is not attached.
     */
    data class Ready(val folder: File, val folders: List<String>) : ImportState

    data object Installing : ImportState

    data class Done(val name: String) : ImportState

    data class Refused(val refusal: ProfileArchive.Refusal) : ImportState

    data class Failed(val why: String) : ImportState
}

/**
 * The import under way or just ended, in the app's process rather than the list's composition: a rotation recreates
 * the activity while a large profile is unpacked, and the unpacked folder waits there for its name.
 */
internal object Imports {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var state by mutableStateOf<ImportState?>(null)
        private set

    fun forgetEnded() {
        if (state?.ended == true) state = null
    }

    fun start(context: Context, uri: Uri) {
        state = ImportState.Unpacking
        scope.launch { state = withContext(Dispatchers.IO) { unpackProfile(context, uri) } }
    }

    fun install(context: Context, ready: ImportState.Ready, name: String) {
        state = ImportState.Installing
        scope.launch {
            val problem = withContext(Dispatchers.IO) { installProfile(context, ready.folder, name) }
            if (problem != null) {
                state = ImportState.Failed(problem)
                return@launch
            }
            // The engine lists a profile it did not make only when asked again; detached, it lists them all as it attaches
            EngineClient.refreshProfiles { }
            state = ImportState.Done(name)
        }
    }

    fun cancel(ready: ImportState.Ready) {
        state = null
        scope.launch { withContext(Dispatchers.IO) { staging(ready.folder).deleteRecursively() } }
    }
}

private fun stagingRoot(context: Context) = File(context.cacheDir, "profile-import")

// The unpacked folder sits in a staging folder of its own under the staging root
private fun staging(folder: File) = folder.parentFile!!

/** Unpacks the archive at [uri] into a new staging folder, after clearing any an earlier import left behind. */
internal fun unpackProfile(context: Context, uri: Uri): ImportState = try {
    val root = stagingRoot(context)
    root.deleteRecursively()
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("the file could not be opened")
    when (val unpacked = input.use { ProfileArchive.unpack(it, File(root, UUID.randomUUID().toString())) }) {
        is ProfileArchive.Unpacked.Folder -> ImportState.Ready(unpacked.folder, ProfileArchive.profilesDir(context.filesDir).list().orEmpty().asList())

        is ProfileArchive.Unpacked.Refused -> {
            Log.i("ProfileImport", "refused the archive, ${unpacked.refusal}: ${unpacked.detail}")
            ImportState.Refused(unpacked.refusal)
        }
    }
} catch (e: IOException) {
    ImportState.Failed(e.message ?: e.toString())
} catch (e: SecurityException) {
    ImportState.Failed(e.message ?: e.toString())
}

/** Adds the unpacked [folder] as the profile [name]; why it could not, null once done. The staging folder goes either way. */
internal fun installProfile(context: Context, folder: File, name: String): String? = try {
    ProfileArchive.install(folder, ProfileArchive.profilesDir(context.filesDir), name)
    null
} catch (e: FileAlreadyExistsException) {
    context.getString(R.string.profile_import_taken, e.file)
} catch (e: IOException) {
    e.message ?: e.toString()
} finally {
    staging(folder).deleteRecursively()
}
