package org.mudlet.android.ui.profiles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ListSegment
import org.mudlet.android.ui.MudletScaffold

/**
 * The start screen: the engine's profiles, the last used first, each with Connect and Edit, and Add. Connect opens the
 * profile with its stored password and goes to the game screen once the engine has begun to open it. Its top bar opens
 * the settings and the About screen. [selected] is the profile whose editor is open beside the list, if one is.
 */
@Composable
fun ProfileListScreen(
    onOpenGame: () -> Unit,
    onEditProfile: (name: String?) -> Unit,
    onOpenSelfTest: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    selected: String? = null,
) {
    val context = LocalContext.current
    val passwords = remember { PasswordStore(context) }
    val profiles = EngineClient.profiles
    var error by remember { mutableStateOf<String?>(null) }
    MudletScaffold(
        stringResource(R.string.profiles_title),
        actions = {
            IconButton(onOpenSettings) { Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title)) }
            IconButton(onOpenAbout) { Icon(painterResource(R.drawable.ic_info), stringResource(R.string.about_title)) }
        },
        floatingActionButton = {
            val add = stringResource(R.string.profiles_add)
            ExtendedFloatingActionButton(
                onClick = { onEditProfile(null) },
                // Material clears the text's semantics, so the icon names the button for TalkBack
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = add) },
                text = { Text(add) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            // Room below the last item for the Add button, which floats over the list
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
        ) {
            error?.let {
                item {
                    Text(
                        it,
                        Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            when {
                profiles == null -> item { Text(stringResource(R.string.profiles_waiting), style = MaterialTheme.typography.bodyLarge) }

                profiles.isEmpty() -> item { Text(stringResource(R.string.profiles_empty), style = MaterialTheme.typography.bodyLarge) }

                else -> itemsIndexed(profiles, key = { _, profile -> profile.name }) { i, profile ->
                    ProfileItem(
                        profile,
                        i,
                        profiles.size,
                        selected = profile.name == selected,
                        onConnect = {
                            error = null
                            EngineClient.openProfile(profile.name, passwords.get(profile.name)) { refused ->
                                if (refused == null) onOpenGame() else error = refused
                            }
                        },
                        onEdit = { onEditProfile(profile.name) },
                    )
                }
            }
            if (onOpenSelfTest != null) {
                item { OutlinedButton(onOpenSelfTest, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.selftest_title)) } }
            }
        }
    }
}

@Composable
private fun ProfileItem(profile: Profile, index: Int, count: Int, selected: Boolean, onConnect: () -> Unit, onEdit: () -> Unit) {
    val description = stringResource(R.string.profile_description, profile.name, profile.host, profile.port)
    val connectLabel = stringResource(R.string.profile_connect_to, profile.name)
    ListSegment(index, count, selected = selected) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = description }) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.profile_address, profile.host, profile.port),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onEdit) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.profile_edit, profile.name))
            }
            Button(onConnect, Modifier.semantics { contentDescription = connectLabel }) { Text(stringResource(R.string.profile_connect)) }
        }
    }
}
