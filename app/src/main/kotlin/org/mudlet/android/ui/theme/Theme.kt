package org.mudlet.android.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.mudlet.android.data.Settings

/**
 * Material 3, light or dark as the system is set, in the wallpaper's colours (Android 12 and later) while
 * [wallpaperColours] is on, else in Mudlet's own. Material's type and shape scales; text sizes are in sp, so they
 * follow the system font scale. The game's own colours (the console, the windows, labels and bars the scripts draw)
 * do not come from here.
 */
@Composable
fun MudletTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    wallpaperColours: Boolean = Settings.wallpaperColours.value,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Android recreates the activity when the wallpaper's colours change, so these keys are all it depends on
    val colors = remember(context, darkTheme, wallpaperColours) { mudletColorScheme(context, darkTheme, wallpaperColours) }
    MaterialTheme(colorScheme = colors, content = content)
}

/** The wallpaper's colours where the system has them (Android 12 and later) and [wallpaper] asks for them, else Mudlet's. */
fun mudletColorScheme(context: Context, dark: Boolean, wallpaper: Boolean): ColorScheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> MudletDarkColors
    else -> MudletLightColors
}

/** Whether this device can theme the app from the wallpaper, and so whether the setting is offered. */
val wallpaperColoursAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
