package org.mudlet.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.mudlet.android.BuildConfig
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.game.AnnouncementRegion
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.GameScreen
import org.mudlet.android.ui.profiles.ProfileListScreen
import org.mudlet.android.ui.selftest.SelfTestScreen
import org.mudlet.android.ui.settings.SettingsScreen

private object Routes {
    const val PROFILES = "profiles"
    const val GAME = "game"
    const val SETTINGS = "settings"
    const val SELF_TEST = "selfTest"
}

/**
 * The app's screens: the profile list, where it starts, the game screen, the settings and, in debug builds only, the
 * self-test.
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
            composable(Routes.PROFILES) {
                ProfileListScreen(
                    onOpenGame = { nav.navigate(Routes.GAME) { launchSingleTop = true } },
                    onOpenSelfTest = if (BuildConfig.DEBUG) ({ nav.navigate(Routes.SELF_TEST) { launchSingleTop = true } }) else null,
                )
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
