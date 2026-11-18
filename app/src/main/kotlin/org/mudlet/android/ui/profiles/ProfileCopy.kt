package org.mudlet.android.ui.profiles

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.data.ProfileArchive
import org.mudlet.android.data.ProfileFolderCopy
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.EngineClient
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.util.UUID

/**
 * The profile editor's Copy: a new profile made from the profile [name], as the two buttons of desktop Mudlet's
 * connection dialog make one, "Copy" of all it holds and "Copy settings only". Desktop names the copy itself and lets
 * the player rename it; a profile's name cannot change here, so a dialog asks for it first, offering desktop's. An
 * [open] game is still writing its profile, which is whole only once it has closed and saved. The password stored for
 * the profile ([passwordStored]) goes to the copy only if the player turns that on in the dialog.
 */
@Composable
fun ProfileCopy(name: String, open: Boolean, passwordStored: Boolean) {
    val context = LocalContext.current.applicationContext
    val state = Copies[name]
    var asking by rememberSaveable { mutableStateOf<CopyKind?>(null) }
    // A finished copy's outcome is shown until the editor goes, by Back or a rotation; a running one outlives it
    DisposableEffect(name) {
        onDispose { Copies.forgetEnded(name) }
    }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.profile_copy_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.profile_copy_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (open) {
            Text(stringResource(R.string.profile_copy_close_first), style = MaterialTheme.typography.bodyMedium)
        }
        val enabled = !open && !Copies.busy
        OutlinedButton({ asking = CopyKind.WHOLE }, enabled = enabled) { Text(stringResource(R.string.profile_copy)) }
        OutlinedButton({ asking = CopyKind.SETTINGS }, enabled = enabled) { Text(stringResource(R.string.profile_copy_settings)) }
        val status = when (state) {
            null -> null

            CopyState.Running -> stringResource(R.string.profile_copying)

            is CopyState.Done -> listOfNotNull(
                stringResource(R.string.profile_copied, state.name),
                if (state.settingsUnreadable) stringResource(R.string.profile_copy_settings_unreadable) else null,
                state.passwordTrouble?.let { stringResource(it.message) },
            ).joinToString("\n")

            is CopyState.Failed -> stringResource(R.string.profile_copy_failed, state.why)
        }
        status?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium) }
    }
    asking?.let { kind ->
        CopyDialog(
            name,
            kind,
            passwordStored,
            onCopy = { copy, withPassword ->
                asking = null
                Copies.start(context, name, copy, kind, withPassword)
            },
            onCancel = { asking = null },
        )
    }
}

@Composable
private fun CopyDialog(source: String, kind: CopyKind, passwordStored: Boolean, onCopy: (name: String, withPassword: Boolean) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    // The folders too: the engine lists one it did not make only when asked again
    val folders = remember { ProfileArchive.profilesDir(context.filesDir).list().orEmpty().asList() }
    val names = folders + EngineClient.profiles.orEmpty().map { it.name }
    var name by rememberSaveable { mutableStateOf(ProfileArchive.freeName(names, source)) }
    var withPassword by rememberSaveable { mutableStateOf(false) }
    val taken = ProfileArchive.taken(names, name)
    val usable = ProfileArchive.usableName(name)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(kind.title, source)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(kind.text))
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(stringResource(R.string.profile_name)) },
                    singleLine = true,
                    isError = taken != null || !usable,
                    supportingText = when {
                        taken != null -> ({ Text(stringResource(R.string.profile_copy_taken, taken)) })
                        !usable -> ({ Text(stringResource(R.string.profile_import_name_rules)) })
                        else -> null
                    },
                )
                if (passwordStored) {
                    SwitchRow(stringResource(R.string.profile_copy_password), withPassword) { withPassword = it }
                }
            }
        },
        confirmButton = {
            TextButton({ onCopy(name, passwordStored && withPassword) }, enabled = taken == null && usable) { Text(stringResource(R.string.profile_copy)) }
        },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.profile_copy_cancel)) } },
    )
}

/** Desktop's two copies, each with its dialog's title, which takes the profile's name, and text. */
internal enum class CopyKind(@param:StringRes val title: Int, @param:StringRes val text: Int) {
    WHOLE(R.string.profile_copy_whole_title, R.string.profile_copy_whole_text),
    SETTINGS(R.string.profile_copy_settings_title, R.string.profile_copy_settings_text),
}

/** How the password stored for a copy is not what the player chose in its dialog, with what the editor says of it. */
internal enum class PasswordTrouble(@param:StringRes val message: Int) {
    /** The password asked for is not stored for the copy. */
    NOT_COPIED(R.string.profile_copy_password_not_copied),

    /** One a profile of the copy's name left behind could not be removed. */
    NOT_CLEARED(R.string.profile_copy_password_not_cleared),
}

internal sealed interface CopyState {
    data object Running : CopyState

    /**
     * Copied as the profile [name]. [settingsUnreadable]: of a copy of the settings, the profile's save could not be
     * read, so the copy has its connection details alone.
     */
    data class Done(val name: String, val settingsUnreadable: Boolean = false, val passwordTrouble: PasswordTrouble? = null) : CopyState

    data class Failed(val why: String) : CopyState
}

/**
 * The copy under way and those just ended, by the profile copied, in the app's process rather than the editor's
 * composition: a rotation recreates the activity, and Back can leave the editor, while a large profile is still being
 * copied.
 */
internal object Copies {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val states = mutableStateMapOf<String, CopyState>()

    operator fun get(source: String): CopyState? = states[source]

    /** Whether a copy is under way. Only one is at a time, as each begins by clearing what a copy cut short left behind. */
    val busy get() = states.values.any { it is CopyState.Running }

    /** Forgets the outcome of [source]'s copy once it has ended. */
    fun forgetEnded(source: String) {
        if (states[source] !is CopyState.Running) states.remove(source)
    }

    /** Copies the profile [source] as [name]; with [withPassword], the password stored for it too. */
    fun start(context: Context, source: String, name: String, kind: CopyKind, withPassword: Boolean) {
        if (busy || EngineClient.session(source) != null) return
        states[source] = CopyState.Running
        scope.launch {
            val copied = withContext(Dispatchers.IO) { copyProfile(context, source, name, kind) }
            if (copied !is CopyState.Done) {
                states[source] = copied
                return@launch
            }
            val passwordTrouble = copyAppState(context, source, name, withPassword)
            // The engine lists a profile it did not make only when asked again; detached, it lists them all as it attaches
            EngineClient.refreshProfiles { }
            states[source] = copied.copy(passwordTrouble = passwordTrouble)
        }
    }
}

private const val TAG = "ProfileCopy"

/**
 * Copies the folder of the profile [source] as the profile [name], which no profile may have, by way of a staging
 * folder in the cache, so that the engine's profiles gain a whole copy or none.
 */
internal fun copyProfile(context: Context, source: String, name: String, kind: CopyKind): CopyState {
    // The dialog lets no other name through; one with a path in it would be written outside the profiles
    if (!ProfileArchive.usableName(name)) return CopyState.Failed(context.getString(R.string.profile_import_name_rules))
    val root = File(context.cacheDir, "profile-copy")
    return try {
        root.deleteRecursively()
        val from = ProfileArchive.folder(context.filesDir, source)
        val copy = File(File(root, UUID.randomUUID().toString()), name)
        val unreadable = when (kind) {
            CopyKind.WHOLE -> {
                ProfileFolderCopy.whole(from, copy)
                null
            }

            CopyKind.SETTINGS -> ProfileFolderCopy.settingsOnly(from, copy) as? ProfileFolderCopy.SettingsCopy.Unreadable
        }
        unreadable?.let { Log.w(TAG, "could not read the settings in the profile's newest save, ${it.why}") }
        ProfileArchive.install(copy, ProfileArchive.profilesDir(context.filesDir), name)
        CopyState.Done(name, settingsUnreadable = unreadable != null)
    } catch (e: FileAlreadyExistsException) {
        CopyState.Failed(context.getString(R.string.profile_copy_taken, e.file))
    } catch (e: IOException) {
        Log.w(TAG, "could not copy a profile", e)
        CopyState.Failed(e.message ?: e.toString())
    } finally {
        root.deleteRecursively()
    }
}

/**
 * Gives the copy [name] what the app keeps for the profile [source] beside its folder, on the main thread, as their
 * other writers are: with [withPassword] the stored password, else none, and never a proxy's password.
 */
private fun copyAppState(context: Context, source: String, name: String, withPassword: Boolean): PasswordTrouble? {
    // The copy has its source's settings, or like it none yet, so it is given what its source is still to be given
    Settings.setAwaitsDefaults(name, Settings.awaitsDefaults(source))
    Settings.setAwaitsInputClear(name, Settings.awaitsInputClear(source))
    val passwords = PasswordStore(context)
    val trouble = if (withPassword) {
        if (passwords.get(source)?.let { passwords.set(name, it) } == true) null else PasswordTrouble.NOT_COPIED
    } else {
        // Or the copy would log in with one a profile of its name left behind
        if (passwords.forget(name)) null else PasswordTrouble.NOT_CLEARED
    }
    // A proxy's password never goes to a copy, as on desktop, nor stays from a profile of the copy's name
    val proxyCleared = passwords.forget(name, PasswordStore.Kind.PROXY)
    return trouble ?: if (proxyCleared) null else PasswordTrouble.NOT_CLEARED
}
