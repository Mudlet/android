package org.mudlet.android.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.mudlet.android.BuildConfig
import org.mudlet.android.ui.about.AboutScreen
import org.mudlet.android.ui.about.LicenceScreen
import org.mudlet.android.ui.game.GameScreen
import org.mudlet.android.ui.profiles.ProfileListScreen
import org.mudlet.android.ui.selftest.SelfTestScreen
import org.mudlet.android.ui.settings.SettingsScreen

private object Routes {
    const val PROFILES = "profiles"
    const val GAME = "game"
    const val SETTINGS = "settings"
    const val SELF_TEST = "selfTest"
    const val ABOUT = "about"
    const val LICENCE = "licence/{id}"

    fun licence(id: String) = "licence/${Uri.encode(id)}"
}

/**
 * The app's screens: the profile list, where it starts, the game screen, the settings, About with a licence screen for
 * each component it lists, and, in debug builds only, the self-test.
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
                onOpenAbout = { nav.navigate(Routes.ABOUT) { launchSingleTop = true } },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.navigateUp() }, onOpenComponent = { nav.navigate(Routes.licence(it)) { launchSingleTop = true } })
        }
        composable(Routes.LICENCE, listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            LicenceScreen(componentId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.navigateUp() })
        }
        composable(Routes.GAME) {
            GameScreen(onBack = { nav.navigateUp() }, onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } })
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.navigateUp() }) }
        if (BuildConfig.DEBUG) {
            composable(Routes.SELF_TEST) { SelfTestScreen(onBack = { nav.navigateUp() }, onKillUi = onKillUi) }
        }
    }
}
