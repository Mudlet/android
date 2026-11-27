package org.mudlet.android.ui

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Android's floating text toolbar over the game text's selection, as UiAutomator sees it: a window of the platform's
 * own, which Compose's test rule does not reach. Its items are found by the label they show. Compose's test clock
 * advances only while a test waits on Compose, never in a wait here, so a test lets Compose catch up
 * (`waitForIdle`) after a change to the selection before it waits here for the toolbar to show or go.
 */
internal object FloatingToolbar {
    private const val WAIT_MS = 10_000L
    private const val SETTLE_MS = 150L
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    private fun item(label: String): BySelector = By.pkg(app).text(label)

    // The toolbar's own overflow button: no Compose node shows to UiAutomator as an ImageButton, so the app has no other
    private val overflow get() = By.pkg(app).clazz("android.widget.ImageButton")

    /** Waits for the item [label] to show, in the toolbar or, opened, its overflow. */
    fun waitFor(label: String): UiObject2 {
        device.wait(Until.hasObject(item(label).enabled(true)), WAIT_MS)
        return checkNotNull(device.findObject(item(label))) { "no item \"$label\" in the floating toolbar; ${describe()}" }
    }

    /** What UiAutomator sees of the app now, its windows and the text shown in them, for a failure's message. */
    fun describe(): String {
        val windows = InstrumentationRegistry.getInstrumentation().uiAutomation.windows.joinToString { window ->
            "${window.type}:${window.title}@${Rect().also(window::getBoundsInScreen).toShortString()}"
        }
        val texts = device.findObjects(By.pkg(app).text(Pattern.compile(".+"))).take(30).joinToString { "${it.className?.substringAfterLast('.')} \"${it.text?.take(30)}\" ${it.visibleBounds.toShortString()}" }
        return "windows [$windows], text [$texts]"
    }

    // The toolbar's overflow opens and closes with an animation, through which an item's place changes under a click
    private fun settle() {
        device.waitForIdle()
        SystemClock.sleep(SETTLE_MS)
    }

    /** Those of [labels] the toolbar shows now, without opening its overflow, from left to right. */
    fun shown(vararg labels: String): List<String> {
        waitFor(labels.first())
        return labels.mapNotNull { label -> device.findObject(item(label))?.let { label to it.visibleBounds.left } }.sortedBy { it.second }.map { it.first }
    }

    /** Whether the item [label] is in the toolbar or its overflow, opening the overflow to look. */
    fun offers(label: String): Boolean {
        if (device.wait(Until.hasObject(item(label)), WAIT_MS / 5)) return true
        val more = overflowButton() ?: return false
        if (device.hasObject(item(label))) return true
        more.click()
        val found = device.wait(Until.hasObject(item(label)), WAIT_MS / 2)
        overflowButton()?.click()
        settle()
        return found
    }

    // Android hides its toolbar for a while as the selection moves or the window loses focus: a look then sees no toolbar,
    // not one without the item, so this waits for the toolbar to be back and at rest
    private fun overflowButton(): UiObject2? = device.wait(Until.findObject(overflow.enabled(true)), WAIT_MS)

    /** Taps the item [label], in the overflow if it is not in the toolbar. */
    fun tap(label: String) {
        if (!device.wait(Until.hasObject(item(label)), WAIT_MS / 2)) {
            checkNotNull(overflowButton()) { "no item \"$label\" and no overflow in the floating toolbar; ${describe()}" }.click()
            waitFor(label)
            settle()
        }
        waitFor(label).click()
    }

    /** Where the item [label] is on the screen. */
    fun bounds(label: String): Rect = waitFor(label).visibleBounds

    /** Fails with [what] and what is shown instead unless the toolbar goes, by its item [label]. */
    fun assertGone(label: String, what: String) {
        if (!device.wait(Until.gone(item(label)), WAIT_MS)) throw AssertionError("$what; ${describe()}")
    }
}
