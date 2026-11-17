package org.mudlet.android.ui

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which of the reasons Android records for a process's exit are Android stopping the engine in the background. */
class EngineStoppedTest {
    @Test
    fun aKillInTheBackgroundIsAndroidsAndACrashAStopOrAnUpdateIsNot() {
        val byAndroid = mapOf(
            ApplicationExitInfo.REASON_LOW_MEMORY to true,
            ApplicationExitInfo.REASON_SIGNALED to true,
            ApplicationExitInfo.REASON_OTHER to true,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to true,
            ApplicationExitInfo.REASON_FREEZER to true,
            ApplicationExitInfo.REASON_UNKNOWN to true,
            ApplicationExitInfo.REASON_CRASH to false,
            ApplicationExitInfo.REASON_CRASH_NATIVE to false,
            ApplicationExitInfo.REASON_ANR to false,
            ApplicationExitInfo.REASON_EXIT_SELF to false,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to false,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE to false,
            ApplicationExitInfo.REASON_USER_REQUESTED to false,
            ApplicationExitInfo.REASON_USER_STOPPED to false,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED to false,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE to false,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED to false,
        )
        assertEquals(byAndroid, byAndroid.mapValues { (reason, _) -> EngineStopped.byAndroid(reason) })
    }
}
