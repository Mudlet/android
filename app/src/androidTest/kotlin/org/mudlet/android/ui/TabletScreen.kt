package org.mudlet.android.ui

import android.app.UiAutomation
import android.content.res.Configuration
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.FileInputStream

/**
 * A 10-inch tablet on its side, 1280 by 640 dp at the screen's own density for the accessibility checks, set before the
 * Activity starts, for the tests [applies] picks. CI's emulator has a 320 by 640 px display, which Android forces to at
 * most twice that, so it is 640 by 1280 px at 160 dpi turned a quarter. Set once the Activity is up, each of the three
 * changes would remake it, and each remade Activity attaches to the engine again: the test's session would be one of
 * theirs. [fold] and [unfold] change the screen's size alone, which remakes the Activity once.
 */
internal class TabletScreen(private val applies: (Description) -> Boolean = { true }) : TestRule {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val configuration get() = instrumentation.targetContext.applicationContext.resources.configuration

    override fun apply(base: Statement, description: Description): Statement = if (!applies(description)) {
        base
    } else {
        object : Statement() {
            override fun evaluate() {
                val before = Configuration(configuration)
                val autoRotate = shell("settings get system accelerometer_rotation").trim() == "1"
                val rotation = shell("settings get system user_rotation").trim().toIntOrNull() ?: UiAutomation.ROTATION_FREEZE_0
                try {
                    shell("wm size $TABLET")
                    shell("wm density $DENSITY")
                    instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
                    await("a tablet's screen") { configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && unfolded(configuration.screenWidthDp.toFloat()) }
                    base.evaluate()
                } finally {
                    shell("wm density reset")
                    shell("wm size reset")
                    instrumentation.uiAutomation.setRotation(if (autoRotate) UiAutomation.ROTATION_UNFREEZE else rotation)
                    // Or the next test's Activity would start on the tablet and be remade
                    await("the screen as it was") { configuration.screenWidthDp == before.screenWidthDp && configuration.densityDpi == before.densityDpi }
                }
            }
        }
    }

    /** A phone folded, 540 dp wide. */
    fun fold() {
        shell("wm size $FOLDED")
        await("a folded phone's screen") { folded(configuration.screenWidthDp.toFloat()) }
    }

    fun unfold() {
        shell("wm size $TABLET")
        await("a tablet's screen") { unfolded(configuration.screenWidthDp.toFloat()) }
    }

    // Whether a window [widthDp] wide is the folded phone's or the tablet's, the system bars at its side or not
    fun folded(widthDp: Float) = widthDp <= FOLDED_DP
    fun unfolded(widthDp: Float) = widthDp > TABLET_DP - BARS_DP

    // The shell's output must be read for the command to finish
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
        String(FileInputStream(pfd.fileDescriptor).readBytes())
    }

    private fun await(what: String, done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (!done()) {
            check(SystemClock.uptimeMillis() < deadline) { "waited $WAIT_MS ms for $what: $configuration" }
            Thread.sleep(50)
        }
    }

    private companion object {
        // The display's own sizes, turned a quarter: 1280 by 640 dp, and 540 by 640 dp
        const val TABLET = "640x1280"
        const val FOLDED = "640x540"
        const val DENSITY = 160
        const val TABLET_DP = 1280
        const val FOLDED_DP = 540
        const val BARS_DP = 96
        const val WAIT_MS = 10_000L
    }
}
