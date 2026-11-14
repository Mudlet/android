package org.mudlet.android.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.engine.EngineNotifications

/**
 * The notification permission as a user meets it, on a fresh install (native/tests/android_instrumented.py installs
 * the app without granting it and runs this class first): on Android 13 and later MainActivity explains what the
 * notifications are for and, on Continue, asks; a "Don't allow" leaves the app usable and posts no tells, the next
 * start explains and asks again, and "Allow" lets tells through. Below 13 there is nothing to ask and tells are posted.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionTest {
    // The real engine is never started: the UI attaches to a fake one
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(FakeEngineRule())

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val startScreenTitle = context.getString(R.string.profiles_title)

    @Test
    fun theAppAsksForIt_deniedItStillWorks_allowedTellsArePosted() {
        if (Build.VERSION.SDK_INT < 33) {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertNotNull("the start screen", device.wait(Until.findObject(By.text(startScreenTitle)), UI_MS))
            }
            assertTellPosted("before 13")
            return
        }
        assertFalse(
            "POST_NOTIFICATIONS is already granted: install the app without -g, and run this class first",
            granted(),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            continuePastExplanation()
            click("permission_deny_button")
            assertFalse(granted())
            assertNotNull("the start screen after a denial", device.wait(Until.findObject(By.text(startScreenTitle)), UI_MS))
        }
        EngineNotifications.postTell(context, TELL, "denied")
        Thread.sleep(QUIET_MS)
        assertTrue("no tell is posted without the permission", notifications.activeNotifications.none { it.title() == TELL })

        // A first denial still lets the app ask again at its next start
        ActivityScenario.launch(MainActivity::class.java).use {
            continuePastExplanation()
            click("permission_allow_button")
            assertTrue(granted())
        }
        assertTellPosted("allowed")
    }

    private fun granted() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun continuePastExplanation() {
        val found = device.wait(Until.findObject(By.text(context.getString(R.string.notifications_explanation_continue))), UI_MS)
        assertNotNull("the explanation's Continue, before Android's own dialog", found)
        found.click()
    }

    private fun click(button: String) {
        val found = device.wait(Until.findObject(By.res(PERMISSION_CONTROLLER, button)), UI_MS)
        assertNotNull("the permission dialog's $button", found)
        found.click()
        device.wait(Until.gone(By.pkg(PERMISSION_CONTROLLER)), UI_MS)
    }

    private fun assertTellPosted(text: String) {
        EngineNotifications.ensureChannels(context)
        EngineNotifications.postTell(context, TELL, text)
        val deadline = System.currentTimeMillis() + UI_MS
        while (notifications.activeNotifications.none { it.title() == TELL }) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("the tell was not posted")
            Thread.sleep(100)
        }
        notifications.cancelAll()
    }

    private fun android.service.notification.StatusBarNotification.title() = notification.extras.getCharSequence("android.title")?.toString()

    private companion object {
        const val PERMISSION_CONTROLLER = "com.android.permissioncontroller"
        const val TELL = "Permission test tell"
        const val UI_MS = 15_000L
        const val QUIET_MS = 1_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
