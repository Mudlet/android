package org.mudlet.android.ui

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.mudlet.android.BuildConfig
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.about.AboutScreen
import org.mudlet.android.ui.about.LicenceScreen
import org.mudlet.android.ui.game.AnnouncementRegion
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.GameScreen
import org.mudlet.android.ui.profiles.ProfileEditor
import org.mudlet.android.ui.profiles.ProfileListScreen
import org.mudlet.android.ui.scripts.ScriptItemKey
import org.mudlet.android.ui.scripts.ScriptItemScreen
import org.mudlet.android.ui.scripts.ScriptLibraryScreen
import org.mudlet.android.ui.selftest.SelfTestScreen
import org.mudlet.android.ui.settings.SettingsPage
import org.mudlet.android.ui.settings.SettingsPageScreen
import org.mudlet.android.ui.settings.SettingsScreen
import org.mudlet.android.ui.theme.MudletMotion

// A profile's name is never empty, so the editor of a new one is open beside the list as this
private const val NEW_PROFILE = ""

// How far a screen slides as it comes and goes, as a fraction of the width
private const val SLIDE_FRACTION = 8

private object Routes {
    const val PROFILES = "profiles"
    const val GAME = "game"
    const val SETTINGS = "settings?game={game}"
    const val SETTINGS_PAGE = "settingsPage/{page}"
    const val SELF_TEST = "selfTest"
    const val PROFILE = "profile?name={name}"
    const val ABOUT = "about"
    const val LICENCE = "licence/{id}"
    const val SCRIPTS = "scripts"
    const val SCRIPT_ITEM = "scriptItem/{key}"

    fun profile(name: String?) = if (name == null) "profile" else "profile?name=${Uri.encode(name)}"

    fun licence(id: String) = "licence/${Uri.encode(id)}"

    fun settings(game: String?) = if (game == null) "settings" else "settings?game=${Uri.encode(game)}"

    fun settingsPage(page: SettingsPage) = "settingsPage/${Uri.encode(page.encode())}"

    fun scriptItem(key: ScriptItemKey) = "scriptItem/${Uri.encode(key.encode())}"
}

/**
 * The app's screens: the profile list, where it starts, a profile's editor, the game screen, the front profile's script
 * library with a screen for each item, the settings with a screen for each of their pages, About with a licence screen
 * for each component it lists, and, in debug builds only, the self-test. On an expanded screen, a profile's editor, a
 * script item, a settings page and a component's licence open beside their list ([ListDetail]).
 * A release build has no route to the self-test, so R8 drops it.
 *
 * On every screen, TalkBack says what the Announcer has to say; the first time TalkBack is on, the app asks whether
 * to tell games a screen reader is in use.
 */
@Composable
fun AppNavigation(onKillUi: () -> Unit, gamesShown: Int = 0) {
    val nav = rememberNavController()
    // Each time a notification's tap brings its game to the front; at 0, none has
    LaunchedEffect(gamesShown) {
        if (gamesShown > 0) {
            nav.navigate(Routes.GAME) {
                popUpTo(Routes.PROFILES)
                launchSingleTop = true
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Material's shared axis: forward slides in from the end, Back (and its predictive gesture) the other way
        val end = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
        NavHost(
            nav,
            startDestination = Routes.PROFILES,
            enterTransition = { slideInFrom(end) },
            exitTransition = { slideOutTo(-end) },
            popEnterTransition = { slideInFrom(-end) },
            popExitTransition = { slideOutTo(end) },
        ) {
            composable(Routes.PROFILES) { entry ->
                // The profile whose editor is open beside the list: its name, or NEW_PROFILE
                var editing by rememberSaveable { mutableStateOf<String?>(null) }
                ListDetail(
                    editing,
                    onCloseDetail = { editing = null },
                    onNarrowed = {
                        editing = null
                        nav.navigate(Routes.profile(it.ifEmpty { null })) { launchSingleTop = true }
                    },
                    list = { twoPane ->
                        ProfileListScreen(
                            onOpenGame = entry.whileShown { nav.navigate(Routes.GAME) { launchSingleTop = true } },
                            onEditProfile = { name ->
                                if (twoPane) editing = name ?: NEW_PROFILE else nav.navigate(Routes.profile(name)) { launchSingleTop = true }
                            },
                            onOpenSelfTest = if (BuildConfig.DEBUG) ({ nav.navigate(Routes.SELF_TEST) { launchSingleTop = true } }) else null,
                            onOpenSettings = { nav.navigate(Routes.settings(null)) { launchSingleTop = true } },
                            onOpenAbout = { nav.navigate(Routes.ABOUT) { launchSingleTop = true } },
                            selected = editing,
                        )
                    },
                    detailContent = { key ->
                        // The engine's answer can come once another profile's editor has taken this one's place
                        ProfileEditor(key.ifEmpty { null }, onClose = { if (editing == key) editing = null })
                    },
                    empty = { DetailPlaceholder(stringResource(R.string.profiles_pick)) },
                )
            }
            composable(Routes.ABOUT) {
                var open by rememberSaveable { mutableStateOf<String?>(null) }
                ListDetail(
                    open,
                    onCloseDetail = { open = null },
                    onNarrowed = {
                        open = null
                        nav.navigate(Routes.licence(it)) { launchSingleTop = true }
                    },
                    list = { twoPane ->
                        AboutScreen(
                            onBack = { nav.navigateUp() },
                            onOpenComponent = { if (twoPane) open = it else nav.navigate(Routes.licence(it)) { launchSingleTop = true } },
                            selected = open,
                        )
                    },
                    detailContent = { LicenceScreen(it, onBack = { open = null }) },
                    empty = { DetailPlaceholder(stringResource(R.string.about_pick)) },
                )
            }
            composable(Routes.LICENCE, listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                LicenceScreen(componentId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.navigateUp() })
            }
            val name = navArgument("name") {
                type = NavType.StringType
                nullable = true
            }
            composable(Routes.PROFILE, listOf(name)) { entry ->
                ProfileEditor(entry.arguments?.getString("name"), onClose = entry.whileShown { nav.navigateUp() })
            }
            composable(Routes.GAME) {
                GameScreen(
                    onBack = { nav.navigateUp() },
                    onOpenSettings = { nav.navigate(Routes.settings(EngineClient.front.name.ifEmpty { null })) { launchSingleTop = true } },
                    onOpenScripts = { nav.navigate(Routes.SCRIPTS) { launchSingleTop = true } },
                )
            }
            composable(Routes.SCRIPTS) { entry ->
                val front = EngineClient.front
                val activity = LocalActivity.current
                // A delete whose Undo is still offered is done as the library goes, but not for a rotation, which brings it back
                DisposableEffect(front) { onDispose { if (activity?.isChangingConfigurations != true) front.scripts.commitDeletes() } }
                // The item open beside the library, encoded
                var open by rememberSaveable { mutableStateOf<String?>(null) }
                val toGame = entry.whileShown { nav.popBackStack(Routes.GAME, inclusive = false) }
                ListDetail(
                    open,
                    onCloseDetail = { open = null },
                    onNarrowed = {
                        open = null
                        ScriptItemKey.decode(it)?.let { key -> nav.navigate(Routes.scriptItem(key)) { launchSingleTop = true } }
                    },
                    list = { twoPane ->
                        ScriptLibraryScreen(
                            front.scripts,
                            front.name,
                            onBack = { nav.navigateUp() },
                            onOpen = { if (twoPane) open = it.encode() else nav.navigate(Routes.scriptItem(it)) { launchSingleTop = true } },
                            selected = open?.let(ScriptItemKey::decode),
                            console = { front.lines },
                            colors = ConsoleColors.of(front.status["consoleColors"]),
                        )
                    },
                    detailContent = { key ->
                        ScriptItemKey.decode(key)?.let { ScriptItemScreen(front.scripts, it, onBack = { if (open == key) open = null }, onGame = toGame) }
                    },
                    empty = { DetailPlaceholder(stringResource(R.string.scripts_pick)) },
                )
            }
            composable(Routes.SCRIPT_ITEM, listOf(navArgument("key") { type = NavType.StringType })) { entry ->
                val key = entry.arguments?.getString("key")?.let(ScriptItemKey::decode)
                val back = entry.whileShown { nav.navigateUp() }
                if (key != null) {
                    ScriptItemScreen(EngineClient.front.scripts, key, onBack = back, onGame = entry.whileShown { nav.popBackStack(Routes.GAME, inclusive = false) })
                } else {
                    LaunchedEffect(Unit) { back() }
                }
            }
            val game = navArgument("game") {
                type = NavType.StringType
                nullable = true
            }
            composable(Routes.SETTINGS, listOf(game)) { entry ->
                // The page open beside the hub, encoded
                var open by rememberSaveable { mutableStateOf<String?>(null) }
                ListDetail(
                    open,
                    onCloseDetail = { open = null },
                    onNarrowed = {
                        open = null
                        SettingsPage.decode(it)?.let { page -> nav.navigate(Routes.settingsPage(page)) { launchSingleTop = true } }
                    },
                    list = { twoPane ->
                        SettingsScreen(
                            game = entry.arguments?.getString("game"),
                            onBack = { nav.navigateUp() },
                            onOpen = { if (twoPane) open = it.encode() else nav.navigate(Routes.settingsPage(it)) { launchSingleTop = true } },
                            selected = open?.let(SettingsPage::decode),
                        )
                    },
                    detailContent = { key ->
                        SettingsPage.decode(key)?.let { SettingsPageScreen(it, onBack = { if (open == key) open = null }) }
                    },
                    empty = { DetailPlaceholder(stringResource(R.string.settings_pick)) },
                )
            }
            composable(Routes.SETTINGS_PAGE, listOf(navArgument("page") { type = NavType.StringType })) { entry ->
                val page = entry.arguments?.getString("page")?.let(SettingsPage::decode)
                val back = entry.whileShown { nav.navigateUp() }
                if (page != null) SettingsPageScreen(page, onBack = back) else LaunchedEffect(Unit) { back() }
            }
            if (BuildConfig.DEBUG) {
                composable(Routes.SELF_TEST) { SelfTestScreen(onBack = { nav.navigateUp() }, onKillUi = onKillUi) }
            }
        }
        // At the foot, so it comes last for TalkBack and is out of the way of a finger exploring the screen
        AnnouncementRegion(Modifier.align(Alignment.BottomStart))
    }
    if (Announcer.touchExploration && !Settings.screenReaderOffered.value) OfferScreenReaderSetting()
}

// [side] is 1 for the right, -1 for the left
private fun slideInFrom(side: Int) = slideInHorizontally(MudletMotion.slide) { side * it / SLIDE_FRACTION } + fadeIn(MudletMotion.effects())

private fun slideOutTo(side: Int) = slideOutHorizontally(MudletMotion.slide) { side * it / SLIDE_FRACTION } + fadeOut(MudletMotion.effects())

/** Asks once whether games are to be told a screen reader is in use; the answer is the setting, which can change later. */
@Composable
private fun OfferScreenReaderSetting() {
    fun answer(yes: Boolean) {
        EngineClient.setForEveryGame(Settings.advertiseScreenReader, yes)
        Settings.screenReaderOffered.set(true)
    }
    AlertDialog(
        onDismissRequest = { Settings.screenReaderOffered.set(true) },
        confirmButton = { TextButton({ answer(true) }) { Text(stringResource(R.string.screen_reader_offer_yes)) } },
        dismissButton = { TextButton({ answer(false) }) { Text(stringResource(R.string.screen_reader_offer_no)) } },
        title = { Text(stringResource(R.string.screen_reader_offer_title)) },
        text = { Text(stringResource(R.string.screen_reader_offer_text)) },
    )
}

// The engine's answer can come once the user has left the screen that asked, and must not move them on from elsewhere
private fun NavBackStackEntry.whileShown(action: () -> Unit): () -> Unit = {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) action()
}
