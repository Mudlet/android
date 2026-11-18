package org.mudlet.android.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MudletScaffold

/**
 * Adds a profile ([name] null) or edits the one named [name], whose name cannot change, as the engine keeps the name a
 * profile was created with. A stored password is never shown again: a new one replaces it, and "Forget password"
 * removes it. [onClose] leaves, as Back does, once the engine has saved or deleted the profile. A new profile can start
 * [fromLink], a telnet:// link's, which the editor says to check. A saved one can be opened without connecting, as
 * desktop's Offline, to work on its scripts: [onOpenGame] then shows it.
 */
@Composable
fun ProfileEditor(name: String?, onClose: () -> Unit, fromLink: Profile? = null, onOpenGame: () -> Unit = {}) {
    val context = LocalContext.current
    val passwords = remember { PasswordStore(context) }
    val existing = remember(name) { name?.let { n -> EngineClient.profiles?.firstOrNull { it.name.equals(n, ignoreCase = true) } } }
    val start = existing ?: fromLink
    var newName by rememberSaveable { mutableStateOf(fromLink?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(start?.host ?: "") }
    var port by rememberSaveable { mutableStateOf(start?.port?.toString() ?: "23") }
    var tls by rememberSaveable { mutableStateOf(start?.tls ?: false) }
    var login by rememberSaveable { mutableStateOf(start?.login ?: "") }
    // Not saveable: the saved state is kept outside the app, and in the clear
    var password by remember { mutableStateOf("") }
    var autoReconnect by rememberSaveable { mutableStateOf(existing?.autoReconnect ?: false) }
    var passwordStored by remember(name) { mutableStateOf(name != null && passwords.has(name)) }
    var error by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val passwordNotStored = stringResource(R.string.profile_password_not_stored)
    val passwordNotForgotten = stringResource(R.string.profile_password_not_forgotten)
    val notAttached = stringResource(R.string.profiles_error_not_attached)

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val profileName = name ?: newName.trim()
    val canSave = !waiting && profileName.isNotEmpty() && host.isNotBlank() && portNumber != null
    val edited = portNumber?.let { Profile(profileName, host.trim(), it, tls, login.trim(), autoReconnect) }

    fun save(then: () -> Unit) {
        error = null
        waiting = true
        val profile = edited!!
        EngineClient.saveProfile(profile) { refused ->
            waiting = false
            when {
                refused != null -> error = refused

                password.isEmpty() -> then()

                !passwords.set(profile.name, password) -> error = passwordNotStored

                else -> {
                    EngineClient.givePassword(profile.name, password)
                    then()
                }
            }
        }
    }

    fun openOffline(name: String) {
        error = null
        // One open already only comes to the front, as it is, connected or not
        if (EngineClient.session(name) != null) {
            if (EngineClient.setFront(name)) onOpenGame() else error = notAttached
            return
        }
        waiting = true
        EngineClient.openProfileOffline(name, passwords.get(name)) { refused ->
            waiting = false
            if (refused == null) onOpenGame() else error = refused
        }
    }

    MudletScaffold(name ?: stringResource(R.string.profile_new_title), onBack = onClose) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (fromLink != null) {
                Text(stringResource(R.string.game_link_check), style = MaterialTheme.typography.bodyMedium)
            }
            if (name == null) {
                OutlinedTextField(
                    newName,
                    { newName = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.profile_name)) },
                    singleLine = true,
                )
            }
            OutlinedTextField(
                host,
                { host = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.profile_host)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                port,
                { port = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.profile_port)) },
                singleLine = true,
                isError = portNumber == null,
                supportingText = if (portNumber == null) ({ Text(stringResource(R.string.profile_port_invalid)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            SwitchRow(stringResource(R.string.profile_tls), tls) { tls = it }
            OutlinedTextField(
                login,
                { login = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.profile_login)) },
                singleLine = true,
            )
            OutlinedTextField(
                password,
                { password = it },
                // PasswordVisualTransformation marks it too; said here as well, as TalkBack must not read it out
                Modifier.fillMaxWidth().semantics { password() },
                label = { Text(stringResource(R.string.profile_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = if (passwordStored) ({ Text(stringResource(R.string.profile_password_stored)) }) else null,
            )
            if (passwordStored && name != null) {
                TextButton({
                    if (passwords.forget(name)) {
                        passwordStored = false
                        EngineClient.givePassword(name, null)
                    } else {
                        error = passwordNotForgotten
                    }
                }) { Text(stringResource(R.string.profile_password_forget)) }
            }
            SwitchRow(stringResource(R.string.profile_auto_reconnect), autoReconnect) { autoReconnect = it }
            error?.let {
                Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ save(onClose) }, enabled = canSave) { Text(stringResource(R.string.profile_save)) }
                if (name != null) {
                    OutlinedButton({ confirmDelete = true }, enabled = !waiting) { Text(stringResource(R.string.profile_delete)) }
                }
            }
            if (name != null) {
                // What is typed is saved first, as desktop's dialog keeps each change, or leaving would lose it
                val unsaved = edited != existing || password.isNotEmpty()
                OutlinedButton(
                    { if (unsaved) save { openOffline(name) } else openOffline(name) },
                    enabled = !waiting && (!unsaved || canSave),
                ) { Text(stringResource(R.string.profile_open_offline)) }
                ProfileExport(name, open = EngineClient.session(name) != null)
            }
        }
    }

    if (confirmDelete && name != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.profile_delete_title, name)) },
            text = { Text(stringResource(R.string.profile_delete_text)) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    error = null
                    waiting = true
                    EngineClient.deleteProfile(name) { refused ->
                        waiting = false
                        when {
                            refused != null -> error = refused

                            // Or a new profile of this name would log in with it
                            !passwords.forget(name) -> error = passwordNotForgotten

                            else -> onClose()
                        }
                    }
                }) { Text(stringResource(R.string.profile_delete)) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.profile_delete_cancel)) } },
        )
    }
}

// One control with its label, so TalkBack reads the label with the switch and a tap on the words toggles it
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().minimumInteractiveComponentSize().toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, null)
    }
}
