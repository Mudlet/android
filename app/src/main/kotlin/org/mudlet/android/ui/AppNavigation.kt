package org.mudlet.android.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.mudlet.android.BuildConfig
import org.mudlet.android.ui.game.GameScreen
import org.mudlet.android.ui.profiles.ProfileListScreen
import org.mudlet.android.ui.selftest.SelfTestScreen

private object Routes {
    const val PROFILES = "profiles"
    const val GAME = "game"
    const val SELF_TEST = "selfTest"
}

/**
 * The app's screens: the profile list, where it starts, the game screen and, in debug builds only, the self-test.
 * A release build has no route to the self-test, so R8 drops it.
 */
@Composable
fun AppNavigation(onKillUi: () -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.PROFILES) {
        composable(Routes.PROFILES) {
            ProfileListScreen(
                onOpenGame = { nav.navigate(Routes.GAME) { launchSingleTop = true } },
                onOpenSelfTest = if (BuildConfig.DEBUG) ({ nav.navigate(Routes.SELF_TEST) { launchSingleTop = true } }) else null,
            )
        }
        composable(Routes.GAME) { GameScreen(onBack = { nav.navigateUp() }) }
        if (BuildConfig.DEBUG) {
            composable(Routes.SELF_TEST) { SelfTestScreen(onBack = { nav.navigateUp() }, onKillUi = onKillUi) }
        }
    }
}
