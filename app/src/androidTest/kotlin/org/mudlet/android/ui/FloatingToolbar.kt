package org.mudlet.android.ui

import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Android's floating text toolbar over the game text's selection, as UiAutomator sees it: a window of the platform's
 * own, which Compose's test rule does not reach. Its items are found by the label they show.
 */
internal object FloatingToolbar {
    private const val WAIT_MS = 10_000L
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    private fun item(label: String): BySelector = By.pkg(app).text(label)

    // The toolbar's own overflow button: no Compose node shows to UiAutomator as an ImageButton, so the app has no other
    private val overflow get() = By.pkg(app).clazz("android.widget.ImageButton")

    /** Waits for the item [label] to show, in the toolbar or, opened, its overflow. */
    fun waitFor(label: String): UiObject2 {
        device.wait(Until.hasObject(item(label).enabled(true)), WAIT_MS)
        return checkNotNull(device.findObject(item(label))) { "no item \"$label\" in the floating toolbar" }
    }

    /** Those of [labels] the toolbar shows now, without opening its overflow, from left to right. */
    fun shown(vararg labels: String): List<String> {
        waitFor(labels.first())
        return labels.mapNotNull { label -> device.findObject(item(label))?.let { label to it.visibleBounds.left } }.sortedBy { it.second }.map { it.first }
    }

    /** Whether the item [label] is in the toolbar or its overflow, opening the overflow to look. */
    fun offers(label: String): Boolean {
        if (device.wait(Until.hasObject(item(label)), WAIT_MS / 5)) return true
        val more = device.findObject(overflow) ?: return false
        more.click()
        val found = device.wait(Until.hasObject(item(label)), WAIT_MS / 2)
        device.findObject(overflow)?.click()
        return found
    }

    /** Taps the item [label], in the overflow if it is not in the toolbar. */
    fun tap(label: String) {
        if (!device.wait(Until.hasObject(item(label)), WAIT_MS / 2)) {
            checkNotNull(device.findObject(overflow)) { "no item \"$label\" and no overflow in the floating toolbar" }.click()
        }
        waitFor(label).click()
    }

    /** Where the item [label] is on the screen. */
    fun bounds(label: String): Rect = waitFor(label).visibleBounds

    /** Waits until the toolbar has gone, by its item [label]; false if it is still there. */
    fun waitUntilGone(label: String): Boolean = device.wait(Until.gone(item(label)), WAIT_MS)
}
