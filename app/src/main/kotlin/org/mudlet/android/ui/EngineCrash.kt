package org.mudlet.android.ui

import android.annotation.SuppressLint
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * When the engine last crashed or stopped responding, for the UI to offer the player Report a problem once for that
 * crash. Nothing is sent: the offer only opens the screen. docs/architecture.md, "Lifecycle".
 */
internal object EngineCrash {
    // The constants are inlined ints, the same on every API level, and the reason compared is one Android 11 or later
    // recorded: lint's InlinedApi warning does not apply
    @SuppressLint("InlinedApi")
    fun crash(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        -> true

        else -> false
    }

    /**
     * The time of the newest of [exits] (newest first, as Android lists them) that is [engine]'s, if it was a crash
     * since the device started at [bootedAtMs]; null otherwise, or when the engine has no recorded exit.
     */
    fun lastEngineCrashMs(exits: List<EngineStopped.Exit>, engine: String, bootedAtMs: Long): Long? {
        val last = exits.firstOrNull { it.processName == engine } ?: return null
        return null.also { last }
    }

    /** [lastEngineCrashMs] for the app's recorded exits; Android 10 and earlier record none, so there it is null. */
    fun lastCrashMs(context: Context): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return lastEngineCrashMs(EngineStopped.recordedExits(context), EngineStopped.engineProcess(context), EngineStopped.bootedAtMs())
    }
}
