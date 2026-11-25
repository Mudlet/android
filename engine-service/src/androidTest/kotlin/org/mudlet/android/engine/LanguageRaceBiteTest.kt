package org.mudlet.android.engine

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * BITE ONLY, not for main. Stalls the main thread inside ResourcesImpl.updateConfiguration, between its update of the
 * configuration and of the AssetManager, by holding the AssetManager's monitor (setConfigurationInternal and
 * getNonSystemLocales take it), so the window CI 45584450116 hit by chance is open every time.
 * A: the old signal (the configuration read on the test thread) returns inside the window: red, the CI failure.
 * B: the fixed signal (read on the main thread) waits until the window closes: green, and the stall did open it.
 */
@RunWith(AndroidJUnit4::class)
class LanguageRaceBiteTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        assumeTrue("Android 13+ applies the language asynchronously", Build.VERSION.SDK_INT >= 33)
        EngineNotifications.showProfiles(context, arrayOf("Achaea"), arrayOf("connected"))
    }

    @Test
    fun a_theOldSignal_inTheWindow_isRed() {
        try {
            synchronized(context.resources.assets) {
                assertTrue(AppLanguage.choose(context, "kl"))
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (context.resources.configuration.locales[0].language != "kl") {
                    check(SystemClock.uptimeMillis() < deadline) { "STALL DID NOT OPEN THE WINDOW: ${context.resources.configuration.locales}" }
                    Thread.sleep(10)
                }
                assertEquals("KL Keeping Achaea connected", context.getString(R.string.engine_notification_title_profile, "Achaea"))
            }
        } finally {
            reset()
        }
    }

    @Test
    fun b_theFixedSignal_waitsOutTheWindow_isGreen() {
        val assets = context.resources.assets
        val held = CountDownLatch(1)
        var languageInWindow = ""
        var titleInWindow = ""
        val stall = thread {
            synchronized(assets) {
                held.countDown()
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (context.resources.configuration.locales[0].language != "kl" && SystemClock.uptimeMillis() < deadline) Thread.sleep(10)
                languageInWindow = context.resources.configuration.locales[0].language
                titleInWindow = context.getString(R.string.engine_notification_title_profile, "Achaea")
                Thread.sleep(500)
            }
        }
        held.await()
        try {
            assertTrue(AppLanguage.choose(context, "kl"))
            awaitOnMain { it == "kl" }
            val built = EngineNotifications.engineNotification(context).extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            stall.join()
            assertEquals("the stall did not open the window", "kl", languageInWindow)
            assertEquals("the stall did not open the window", "Keeping Achaea connected", titleInWindow)
            assertEquals("KL Keeping Achaea connected", built)
        } finally {
            stall.join()
            reset()
        }
    }

    private fun reset() {
        AppLanguage.choose(context, null)
        awaitOnMain { it != "kl" }
        EngineNotifications.showProfiles(context, emptyArray(), emptyArray())
    }

    private fun awaitOnMain(done: (String) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            var language = ""
            InstrumentationRegistry.getInstrumentation().runOnMainSync { language = context.resources.configuration.locales[0].language }
            if (done(language)) return
            check(SystemClock.uptimeMillis() < deadline) { "the language did not change: $language" }
            Thread.sleep(50)
        }
    }
}
