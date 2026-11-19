package org.mudlet.android.ui.profiles

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mudlet.android.R
import org.mudlet.android.data.ProfileArchive
import org.mudlet.android.ui.EngineClient
import java.io.File
import java.io.IOException
import java.io.InputStream
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
            { launcher.launch(ProfileArchive.MIME_TYPES.toTypedArray()) },
            enabled = state == null || state.ended,
        ) { Text(stringResource(R.string.profiles_import)) }
        // A file opened from another app says the same in its own dialog, and TalkBack is to read it once
        if (!Imports.fromOutside) state?.let { ImportStatus(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

// Says how the import is going, or how it ended, as it changes
@Composable
private fun ImportStatus(state: ImportState, style: TextStyle = LocalTextStyle.current) {
    val status = when (state) {
        is ImportState.Ready, is ImportState.Offered -> return
        ImportState.Checking, ImportState.Unpacking -> stringResource(R.string.profile_import_unpacking)
        ImportState.Installing -> stringResource(R.string.profile_importing)
        is ImportState.Done -> stringResource(R.string.profile_imported, state.name)
        is ImportState.Refused -> stringResource(state.refusal.text())
        is ImportState.Failed -> stringResource(R.string.profile_import_failed, state.why)
    }
    val color = if (state is ImportState.Refused || state is ImportState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = color, style = style)
}

/** The naming dialog of an import; leaving the profile list forgets an import that has ended. */
@Composable
fun ProfileImportDialog() {
    val context = LocalContext.current.applicationContext
    val state = Imports.state
    DisposableEffect(Unit) {
        // A file opened from another app shows how its import ended in a dialog of its own, until the player closes it
        onDispose { if (!Imports.fromOutside) Imports.forgetEnded() }
    }
    if (state is ImportState.Ready) {
        ImportDialog(state.folder.name, state.folders, state.folder.path, null, onImport = { Imports.install(context, state, it) }, onCancel = { Imports.cancel(state) })
    }
}

/**
 * The dialogs of a `.zip` another app opened with Mudlet or shared with it, over whichever screen shows, as the profile
 * list's Import row may be nowhere in view: its check, what it holds and the name to import it under, and how the
 * import ended. Nothing of the file is written until the player confirms.
 */
@Composable
fun OpenedProfileFile() {
    val state = Imports.state
    if (!Imports.fromOutside || state == null || state is ImportState.Ready) return
    val context = LocalContext.current.applicationContext
    if (state is ImportState.Offered) {
        val size = Formatter.formatShortFileSize(LocalContext.current, state.contents.bytes)
        val details = pluralStringResource(R.plurals.profile_file_contents, state.contents.files, state.contents.files, size)
        ImportDialog(state.contents.name, state.folders, state, details, onImport = { Imports.importOffered(context, state, it) }, onCancel = Imports::dismiss)
        return
    }
    AlertDialog(
        // Not while it unpacks or installs, which cannot be stopped part way: the player is to see how that ended
        onDismissRequest = {
            if (state.ended) {
                Imports.forgetEnded()
            } else if (state == ImportState.Checking) {
                Imports.dismiss()
            }
        },
        title = { Text(stringResource(R.string.profile_file_title)) },
        text = { ImportStatus(state) },
        confirmButton = {
            if (state.ended) {
                TextButton(Imports::forgetEnded) { Text(stringResource(R.string.profile_file_ok)) }
            } else if (state == ImportState.Checking) {
                TextButton(Imports::dismiss) { Text(stringResource(R.string.profile_import_cancel)) }
            }
        },
    )
}

@Composable
private fun ImportDialog(archived: String, folders: List<String>, key: Any, details: String?, onImport: (name: String) -> Unit, onCancel: () -> Unit) {
    val names = folders + EngineClient.profiles.orEmpty().map { it.name }
    var name by rememberSaveable(key) {
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
                details?.let { Text(it) }
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

    /** Reading through a file another app opened with Mudlet, making the import's checks, writing nothing. */
    data object Checking : ImportState

    /**
     * A file another app opened with Mudlet, at [uri], that passed the checks, for the player to name and confirm;
     * nothing of it is written yet. [folders] as in [Ready]. Not a data class, whose text would hold the player's URI.
     */
    class Offered(val uri: Uri, val contents: ProfileArchive.Contents, val folders: List<String>) : ImportState

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
    private var checking: Job? = null
    var state by mutableStateOf<ImportState?>(null)
        private set

    /** Whether [state] is of a file another app opened with Mudlet, whose dialogs [OpenedProfileFile] shows. */
    var fromOutside by mutableStateOf(false)
        private set

    fun forgetEnded() {
        if (state?.ended == true) state = null
    }

    fun start(context: Context, uri: Uri) {
        fromOutside = false
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

    /**
     * Checks the file at [uri] that another app opened with Mudlet, writing nothing, to offer it to the player; false,
     * doing nothing, while another import is being read or written. A picked file waiting for its name is dropped.
     */
    fun open(context: Context, uri: Uri): Boolean {
        when (val current = state) {
            null, is ImportState.Offered -> Unit
            is ImportState.Ready -> cancel(current)
            else -> if (!current.ended) return false
        }
        fromOutside = true
        state = ImportState.Checking
        checking = scope.launch { state = withContext(Dispatchers.IO) { checkProfile(context, uri) } }
        return true
    }

    /** Imports the file [offered] as the profile [name], unpacking it with the same checks again, as it may have changed. */
    fun importOffered(context: Context, offered: ImportState.Offered, name: String) {
        state = ImportState.Unpacking
        scope.launch {
            when (val unpacked = withContext(Dispatchers.IO) { unpackProfile(context, offered.uri) }) {
                is ImportState.Ready -> install(context, unpacked, name)
                else -> state = unpacked
            }
        }
    }

    /** Drops a file opened from another app while it is checked or offered, before anything of it is written. */
    fun dismiss() {
        checking?.cancel()
        if (state == ImportState.Checking || state is ImportState.Offered) state = null
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
    val input = openFile(context, uri)
    when (val unpacked = input.use { ProfileArchive.unpack(it, File(root, UUID.randomUUID().toString())) }) {
        is ProfileArchive.Unpacked.Folder -> ImportState.Ready(unpacked.folder, ProfileArchive.profilesDir(context.filesDir).list().orEmpty().asList())
        is ProfileArchive.Unpacked.Refused -> refused(unpacked)
    }
} catch (e: IOException) {
    ImportState.Failed(e.message ?: e.toString())
} catch (_: SecurityException) {
    ImportState.Failed(context.getString(R.string.profile_import_no_access))
}

/** Reads the file at [uri] through, making the import's checks, and offers what it holds; nothing is written. */
internal fun checkProfile(context: Context, uri: Uri): ImportState = try {
    val folders = ProfileArchive.profilesDir(context.filesDir).list().orEmpty().asList()
    val input = File(stagingRoot(context).apply { mkdirs() }, "opened.zip").also { copy -> openFile(context, uri).use { from -> copy.outputStream().use { from.copyTo(it) } } }.inputStream()
    when (val checked = input.use { ProfileArchive.check(it) }) {
        is ProfileArchive.Checked.Profile -> ImportState.Offered(uri, checked.contents, folders)
        is ProfileArchive.Unpacked.Refused -> refused(checked)
    }
} catch (e: IOException) {
    ImportState.Failed(e.message ?: e.toString())
} catch (_: SecurityException) {
    ImportState.Failed(context.getString(R.string.profile_import_no_access))
}

private fun openFile(context: Context, uri: Uri): InputStream = try {
    context.contentResolver.openInputStream(uri) ?: throw IOException("the file could not be opened")
} catch (e: SecurityException) {
    throw e
} catch (e: RuntimeException) {
    // Another app's provider answers across Binder, which rethrows whatever RuntimeException it threw here
    throw IOException(e.message ?: e.toString(), e)
}

// The refusal alone goes to the log: its detail is a path in the player's file
private fun refused(refused: ProfileArchive.Unpacked.Refused): ImportState {
    Log.i("ProfileImport", "refused the archive: ${refused.refusal}")
    return ImportState.Refused(refused.refusal)
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
