package org.mudlet.android.ui

import android.Manifest
import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.engine.AppLanguage
import org.mudlet.android.ui.settings.appLanguageChoices
import java.util.Locale

/**
 * The app's language, against the debug build's fake translation (src/debug/res/values-kl): the folder is in the
 * settings' list and in Android's per-app list, and both the setting and Android's own per-app setting switch the
 * app's screens to it and back.
 */
@RunWith(AndroidJUnit4::class)
class AppLanguageTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(FollowThePhoneAfter())
        .around(compose)
    private val kalaallisut get() = appLanguageChoices.single { it.tag == "kl" }

    // The English the test starts in, as the fake translation leaves the page's other strings in it
    private lateinit var english: String

    @Before
    fun openTheProfiles() {
        compose.enableAccessibilityChecksOfWhatShows()
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(emptyList(), session.receive("listProfiles"))
        english = string(R.string.settings_language)
        waitFor(hasText(string(R.string.profiles_empty)))
    }

    @Test
    fun aTranslationsFolderIsInTheList() {
        assertTrue("values-kl is offered: $appLanguageChoices", appLanguageChoices.any { it.tag == "kl" })
        assertTrue("the English is offered: $appLanguageChoices", appLanguageChoices.any { it.tag == "en" })
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun androidsPerAppListIsTheSettingsOwn() {
        val config = LocaleConfig(context)
        assertEquals("the manifest's localeConfig reads", LocaleConfig.STATUS_SUCCESS, config.status)
        val androids = config.supportedLocales!!.let { list -> (0 until list.size()).map { list[it].toLanguageTag() } }.toSet()
        val settings = appLanguageChoices.map { Locale.forLanguageTag(it.tag).toLanguageTag() }.toSet()
        assertEquals(settings, androids)
    }

    @Test
    fun theSettingSwitchesTheAppToALanguage_andBackToThePhones() {
        openTheLanguageList()
        activate(hasText(kalaallisut.name) and hasAnyAncestor(isDialog()))
        attachedAgain()
        // The page again, its row in the language and naming it
        waitFor(hasText("KL Language") and hasText(kalaallisut.name))
        assertEquals("kl", AppLanguage.chosen(context))
        assertEquals("the activity's own resources are in it", "KL Settings", compose.activity.getString(R.string.settings_title))

        activate(hasText("KL Language"))
        activate(hasText("KL System default") and hasAnyAncestor(isDialog()))
        attachedAgain()
        waitFor(hasText(english))
        assertNull(AppLanguage.chosen(context))
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun androidsPerAppSettingSwitchesTheApp_andTheSettingShowsIt() {
        // What the phone's settings, Apps, Mudlet, Language do
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("kl")
        awaitLanguage("kl") { it == "kl" }
        attachedAgain()
        activate(hasContentDescription("KL Settings"))
        compose.scrollSettingsHubTo(hasText("KL Appearance"))
        activate(hasText("KL Appearance"))
        waitFor(hasText("KL Language") and hasText(kalaallisut.name))
    }

    private fun openTheLanguageList() {
        activate(hasContentDescription(string(R.string.settings_title)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_appearance)))
        activate(hasText(string(R.string.settings_appearance)))
        activate(hasText(english))
        waitFor(hasText(string(R.string.settings_language_system)) and hasAnyAncestor(isDialog()))
    }

    // A new language makes the Activity again, which attaches to the engine anew
    private fun attachedAgain() {
        val again = fake.engine.nextSession()
        again.profiles(emptyList(), again.receive("listProfiles"))
    }

    // The choice is kept, so it would outlast the test; reset once the Activity has gone, as on Android 13 and later the
    // change would make it again
    private class FollowThePhoneAfter : ExternalResource() {
        override fun after() {
            AppLanguage.choose(context, null)
            awaitLanguage("the phone's") { it != "kl" }
        }
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    // The merged tree, where a row's click action and its text are on one node
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

        // The system hands the process a change of the app's language asynchronously
        fun awaitLanguage(what: String, done: (String) -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + WAIT_MS
            while (!done(context.resources.configuration.locales[0].language)) {
                check(SystemClock.uptimeMillis() < deadline) { "waited $WAIT_MS ms for $what language: ${context.resources.configuration.locales}" }
                Thread.sleep(50)
            }
        }

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
        const val WAIT_MS = 10_000L
    }
}
