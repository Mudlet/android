package org.mudlet.android.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.Feature
import org.mudlet.android.ui.FeatureCallouts
import org.mudlet.android.ui.ListSegment
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.ProfileSession
import org.mudlet.android.ui.SectionHeader
import org.mudlet.android.ui.game.ConnectionIcon
import org.mudlet.android.ui.game.ConnectionState
import org.mudlet.android.ui.game.GameBadges
import org.mudlet.android.ui.game.gameDescription
import org.mudlet.android.ui.game.text
import org.mudlet.android.update.PtbUpdates

/**
 * The start screen: the games open, if any, then the engine's profiles, the last used first, each with Connect and
 * Edit, and Add; below them the games Mudlet offers that the player has no profile for, as its connection dialog does
 * ([DefaultGames]). Connect opens the profile with its stored password and goes to the game screen once the engine has
 * begun to open it; while games are open, it opens one beside them, and one already open just comes to the front, as
 * a tap on an open game does (docs/multi-profile.md, "The switcher"). A tap on one of Mudlet's games adds it as a
 * profile of its name, then connects the same way. Its top bar opens the settings and the About screen. [selected] is
 * the profile whose editor is open beside the list, if one is. A PTB from GitHub tells of a newer one above the list
 * ([PtbUpdateNotice]).
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
    val games = EngineClient.sessions
    val defaultGames = rememberDefaultGames()
    // The game whose details are shown, by name; whether one is being added, until the engine answers
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    // The list keeps its first row where it is when rows come above it, as the games do when the engine tells of them
    // after its profiles are shown: bring them into view
    val hasGames = games.isNotEmpty()
    var hadGames by rememberSaveable { mutableStateOf(hasGames) }
    LaunchedEffect(hasGames) {
        if (hasGames && !hadGames) list.scrollToItem(0)
        hadGames = hasGames
    }
    // The error shows at the top, out of view after a tap on a game far down the list
    LaunchedEffect(error) {
        if (error != null) list.animateScrollToItem(0)
    }
    fun connect(name: String) {
        error = null
        // Without alongside, the engine would close the games open
        val open = if (games.isEmpty()) EngineClient::openProfile else EngineClient::openProfileAlongside
        open(name, passwords.get(name)) { refused ->
            if (refused == null) onOpenGame() else error = refused
        }
    }

    fun connectGame(game: DefaultGame) {
        if (adding) return
        info = null
        val existing = DefaultGames.profileFor(game, profiles.orEmpty())
        if (existing != null) return connect(existing.name)
        error = null
        adding = true
        EngineClient.saveProfile(game.newProfile()) { refused ->
            adding = false
            if (refused == null) connect(game.name) else error = refused
        }
    }

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Above the list, not in it: a lazy list keeps its first visible item in place when an item is put before
            // it, so a notice that comes while the list shows would be out of sight
            if (PtbUpdates.notice != null) PtbUpdateNotice(Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                list,
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
                if (hasGames) {
                    item { SectionHeader(stringResource(R.string.games_title)) }
                    itemsIndexed(games, key = { _, game -> "game:" + game.name }) { i, game ->
                        OpenGameItem(game, i, games.size) {
                            val other = game.name != EngineClient.front.name
                            if (EngineClient.setFront(game.name) && other && false) FeatureCallouts.dismiss(context, Feature.GAME_SWITCHING) // BITE b5
                            onOpenGame()
                        }
                    }
                    item { SectionHeader(stringResource(R.string.profiles_all)) }
                }
                when {
                    profiles == null -> item { Text(stringResource(R.string.profiles_waiting), style = MaterialTheme.typography.bodyLarge) }

                    profiles.isEmpty() -> item { Text(stringResource(R.string.profiles_empty), style = MaterialTheme.typography.bodyLarge) }

                    else -> itemsIndexed(profiles, key = { _, profile -> "profile:" + profile.name }) { i, profile ->
                        ProfileItem(
                            profile,
                            i,
                            profiles.size,
                            selected = profile.name == selected,
                            onConnect = { connect(profile.name) },
                            onEdit = { onEditProfile(profile.name) },
                        )
                    }
                }
                item(key = "import") { ProfileImport(Modifier.padding(top = 12.dp)) }
                if (onOpenSelfTest != null) {
                    item { OutlinedButton(onOpenSelfTest, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.selftest_title)) } }
                }
                // Only once the profiles are known, as those already added are left out
                val offered = profiles?.let { DefaultGames.offered(defaultGames, it) }.orEmpty()
                if (offered.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.default_games_title)) }
                    itemsIndexed(offered, key = { _, game -> "default:" + game.name }) { i, game ->
                        DefaultGameItem(game, i, offered.size, enabled = !adding, onConnect = { connectGame(game) }, onInfo = { info = game.name })
                    }
                }
            }
        }
    }
    ProfileImportDialog()
    defaultGames.firstOrNull { it.name == info }?.let { game ->
        DefaultGameInfo(game, onConnect = { connectGame(game) }, onDismiss = { info = null })
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
            // 48 dp laid out, not 40 with its touch area grown to 48: at a density such as a tablet's 0.625 that growth
            // splits into half pixels, and the button can come out a pixel short of 48 dp
            IconButton(onEdit, Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.profile_edit, profile.name))
            }
            Button(onConnect, Modifier.semantics { contentDescription = connectLabel }) { Text(stringResource(R.string.profile_connect)) }
        }
    }
}

/** An open game, its name, connection state and badges, which a tap brings to the front. */
@Composable
private fun OpenGameItem(game: ProfileSession, index: Int, count: Int, onShow: () -> Unit) {
    val state = ConnectionState.of(game)
    val stateText = state.text()
    val description = gameDescription(game.name, stateText, game.unread, game.attention)
    ListSegment(index, count) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(onClickLabel = stringResource(R.string.game_show), role = Role.Button, onClick = onShow)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionIcon(state, size = 20.dp)
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = description }) {
                Text(game.name, style = MaterialTheme.typography.titleMedium)
                Text(stateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GameBadges(game.unread, game.attention)
        }
    }
}
