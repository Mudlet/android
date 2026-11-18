package org.mudlet.android.ui

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which of the engine's recorded exits are a crash the app offers to report. */
class EngineCrashTest {
    @Test
    fun aCrashANativeCrashAndAnAnrAreCrashesAndAKillOrAStopIsNot() {
        val crashes = mapOf(
            ApplicationExitInfo.REASON_CRASH to true,
            ApplicationExitInfo.REASON_CRASH_NATIVE to true,
            ApplicationExitInfo.REASON_ANR to true,
            ApplicationExitInfo.REASON_LOW_MEMORY to false,
            ApplicationExitInfo.REASON_SIGNALED to false,
            ApplicationExitInfo.REASON_OTHER to false,
            ApplicationExitInfo.REASON_UNKNOWN to false,
            ApplicationExitInfo.REASON_EXIT_SELF to false,
            ApplicationExitInfo.REASON_USER_REQUESTED to false,
            ApplicationExitInfo.REASON_USER_STOPPED to false,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED to false,
        )
        assertEquals(crashes, crashes.mapValues { (reason, _) -> EngineCrash.crash(reason) })
    }

    @Test
    fun theEnginesNewestExitSinceTheBootDecides() {
        val crash = ApplicationExitInfo.REASON_CRASH_NATIVE
        val signaled = ApplicationExitInfo.REASON_SIGNALED
        fun decide(vararg exits: EngineStopped.Exit) = EngineCrash.lastEngineCrashMs(exits.toList(), ENGINE, BOOT_MS)

        assertEquals("crashed after the boot", BOOT_MS + 1, decide(EngineStopped.Exit(ENGINE, crash, BOOT_MS + 1)))
        assertEquals("an ANR", BOOT_MS + 1, decide(EngineStopped.Exit(ENGINE, ApplicationExitInfo.REASON_ANR, BOOT_MS + 1)))
        assertNull("the UI's crash is not the engine's", decide(EngineStopped.Exit(UI, crash, BOOT_MS + 1)))
        assertNull("none at all", decide())
        assertEquals("only the newest of the engine's counts", BOOT_MS + 2, decide(EngineStopped.Exit(UI, signaled, BOOT_MS + 3), EngineStopped.Exit(ENGINE, crash, BOOT_MS + 2), EngineStopped.Exit(ENGINE, crash, BOOT_MS + 1)))
        assertNull("a kill after an older crash", decide(EngineStopped.Exit(ENGINE, signaled, BOOT_MS + 2), EngineStopped.Exit(ENGINE, crash, BOOT_MS + 1)))
        assertNull("crashed before a reboot", decide(EngineStopped.Exit(ENGINE, crash, BOOT_MS - 1)))
    }

    private companion object {
        const val ENGINE = "org.mudlet.mudlet:engine"
        const val UI = "org.mudlet.mudlet"
        const val BOOT_MS = 1_700_000_000_000L
    }
}
