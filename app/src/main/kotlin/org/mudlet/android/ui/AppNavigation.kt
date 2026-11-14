package org.mudlet.android.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import org.mudlet.android.ui.selftest.SelfTestScreen
import org.mudlet.android.ui.settings.SettingsScreen

private object Routes {
    const val PROFILES = "profiles"
    const val GAME = "game"
    const val SETTINGS = "settings"
    const val SELF_TEST = "selfTest"
    const val PROFILE = "profile?name={name}"
    const val ABOUT = "about"
    const val LICENCE = "licence/{id}"

    fun profile(name: String?) = if (name == null) "profile" else "profile?name=${Uri.encode(name)}"

    fun licence(id: String) = "licence/${Uri.encode(id)}"
}

/**
 * The app's screens: the profile list, where it starts, a profile's editor, the game screen, the settings, About with a
 * licence screen for each component it lists, and, in debug builds only, the self-test.
 * A release build has no route to the self-test, so R8 drops it.
 *
 * On every screen, TalkBack says what the Announcer has to say; the first time TalkBack is on, the app asks whether
 * to tell games a screen reader is in use.
 */
@Composable
fun AppNavigation(onKillUi: () -> Unit) {
    val nav = rememberNavController()
    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = Routes.PROFILES) {
            composable(Routes.PROFILES) { entry ->
                ProfileListScreen(
                    onOpenGame = entry.whileShown { nav.navigate(Routes.GAME) { launchSingleTop = true } },
                    onEditProfile = { nav.navigate(Routes.profile(it)) { launchSingleTop = true } },
                    onOpenSelfTest = if (BuildConfig.DEBUG) ({ nav.navigate(Routes.SELF_TEST) { launchSingleTop = true } }) else null,
                    onOpenAbout = { nav.navigate(Routes.ABOUT) { launchSingleTop = true } },
                )
            }
            composable(Routes.ABOUT) {
                AboutScreen(onBack = { nav.navigateUp() }, onOpenComponent = { nav.navigate(Routes.licence(it)) { launchSingleTop = true } })
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
                GameScreen(onBack = { nav.navigateUp() }, onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } })
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.navigateUp() }) }
            if (BuildConfig.DEBUG) {
                composable(Routes.SELF_TEST) { SelfTestScreen(onBack = { nav.navigateUp() }, onKillUi = onKillUi) }
            }
        }
        // At the foot, so it comes last for TalkBack and is out of the way of a finger exploring the screen
        AnnouncementRegion(Modifier.align(Alignment.BottomStart))
    }
    if (Announcer.touchExploration && !Settings.screenReaderOffered.value) OfferScreenReaderSetting()
}

/** Asks once whether games are to be told a screen reader is in use; the answer is the setting, which can change later. */
@Composable
private fun OfferScreenReaderSetting() {
    fun answer(yes: Boolean) {
        EngineClient.setMudletSetting(Settings.advertiseScreenReader, yes)
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
