package org.mudlet.android.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Whether Android stopped the engine in the background, as a battery saver or the low-memory killer does, which the
 * player can make rarer by letting Mudlet use the battery without restrictions; a crash, a force stop or an update is
 * not that. docs/architecture.md, "Lifecycle".
 */
internal object EngineStopped {
    private const val TAG = "EngineStopped"

    /** How many of the app's recorded process exits to look through for the engine's last. */
    private const val EXITS_READ = 10

    // The constants are inlined ints, the same on every API level, and the reason compared is one Android 11 or later
    // recorded: lint's InlinedApi warning does not apply
    @SuppressLint("InlinedApi")
    fun byAndroid(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_FREEZER,
        ApplicationExitInfo.REASON_UNKNOWN,
        -> true

        else -> false
    }

    /** One recorded exit of a process of the app, as ApplicationExitInfo has it; [timeMs] is wall-clock time. */
    data class Exit(val processName: String, val reason: Int, val timeMs: Long)

    /**
     * Whether the newest of [exits] (newest first, as Android lists them) that is [engine]'s was Android stopping it
     * since the device started at [bootedAtMs]. An exit before that is the shutdown of a reboot, after which the engine
     * restores too, and which no battery setting prevents. No exit of the engine's is false.
     */
    fun lastEngineExitByAndroid(exits: List<Exit>, engine: String, bootedAtMs: Long): Boolean {
        val last = exits.firstOrNull { it.processName == engine } ?: return false
        return last.timeMs >= bootedAtMs && byAndroid(last.reason)
    }

    /**
     * [lastEngineExitByAndroid] for the app's recorded exits. Android 10 and earlier record none, and a restore
     * follows only a kill or a reboot, so there it is true.
     */
    fun lastExitByAndroid(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, EXITS_READ)
            .map { Exit(it.processName, it.reason, it.timestamp) }
        val bootedAtMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return lastEngineExitByAndroid(exits, "${context.packageName}:engine", bootedAtMs)
    }

    /**
     * Opens the app's page in Android's settings, where Battery (App battery usage on some phones) lets Mudlet run
     * unrestricted, or else the list of apps the battery optimisation applies to. Asking Android to exempt the app
     * directly needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Google Play allows only for a few kinds of app.
     */
    fun openBatterySettings(context: Context) {
        val appPage = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        for (intent in listOf(appPage, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            try {
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no settings page for ${intent.action}", e)
            }
        }
    }
}
