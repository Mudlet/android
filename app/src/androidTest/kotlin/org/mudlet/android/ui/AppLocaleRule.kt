package org.mudlet.android.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * The app's language set to [tag], as the system's per-app language setting does, before the Activity starts, and the
 * system's language again afterwards. The system hands the process each change asynchronously, so both wait for it:
 * an Activity started before it arrives would be recreated in the middle of a test.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class AppLocaleRule(private val tag: String) : ExternalResource() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val manager = context.getSystemService(LocaleManager::class.java)
    private lateinit var before: LocaleList

    override fun before() {
        before = context.resources.configuration.locales
        manager.applicationLocales = LocaleList.forLanguageTags(tag)
        await("the app's language to become $tag") { context.resources.configuration.locales[0].toLanguageTag() == tag }
    }

    override fun after() {
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        await("the app's language to be ${before.toLanguageTags()} again") { context.resources.configuration.locales == before }
    }

    private fun await(what: String, done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (!done()) {
            check(SystemClock.uptimeMillis() < deadline) { "waited $WAIT_MS ms for $what: ${context.resources.configuration.locales}" }
            Thread.sleep(50)
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
