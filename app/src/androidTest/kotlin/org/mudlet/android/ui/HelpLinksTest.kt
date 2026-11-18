package org.mudlet.android.ui

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.about.ReleaseNotes
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.about.readAsset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Help as a TalkBack user reaches it against a fake engine: About's Help links and the script library's Help menu
 * each open in the browser the page desktop Mudlet's Help menu and script editor open, written out here so a changed
 * address fails, and "Show changelog" shows the app's release notes. The browser is not started: the test sees the
 * intent only.
 */
@RunWith(AndroidJUnit4::class)
class HelpLinksTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val browser = Browser()
    private lateinit var session: FakeEngine.Session
    private val game = Profile("help ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)

    /** Every web page the app asks to open, kept from opening */
    private class Browser : Instrumentation.ActivityMonitor() {
        val opened = CopyOnWriteArrayList<String>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_VIEW || intent.data?.scheme != "https") return null
            opened += intent.dataString
            return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        }
    }

    @Before
    fun setUp() {
        compose.enableAccessibilityChecksOfWhatShows()
        instrumentation.addMonitor(browser)
        session = fake.engine.nextSession()
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
    }

    @After
    fun removeBrowser() = instrumentation.removeMonitor(browser)

    @Test
    fun aboutsHelpLinksOpenDesktopsHelpPages() {
        activate(hasContentDescription(string(R.string.about_title)))
        val links = listOf(
            R.string.help_manual to "https://wiki.mudlet.org/w/Manual:Contents",
            R.string.help_lua_functions to "https://wiki.mudlet.org/w/Manual:Lua_Functions",
            R.string.help_videos to "https://www.mudlet.org/media/",
            R.string.help_discord to "https://www.mudlet.org/chat",
            R.string.help_live_chat to "https://web.libera.chat/?channel=#mudlet",
            R.string.help_forum to "https://forums.mudlet.org/",
        )
        for ((label, url) in links) {
            val link = hasText(string(label)) and hasClickAction()
            compose.onNode(hasScrollAction()).performScrollToNode(link)
            activate(link)
            opens(url)
        }
    }

    @Test
    fun aboutsShowChangelogShowsWhatEachReleaseSaidIsNew() {
        activate(hasContentDescription(string(R.string.about_title)))
        val link = hasText(string(R.string.help_changelog)) and hasClickAction()
        compose.onNode(hasScrollAction()).performScrollToNode(link)
        activate(link)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.changelog_title))).fetchSemanticsNodes().isNotEmpty() }
        // The notes the build copied from docs/release-notes.md: none before the first stable release
        val newest = ReleaseNotes.parse(readAsset(compose.activity, ReleaseNotes.ASSET)).firstOrNull()
        if (newest == null) {
            waitFor(hasText(string(R.string.changelog_none)))
        } else {
            waitFor(hasText(string(R.string.changelog_version, newest.version)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            waitFor(hasText(newest.lines.first()))
        }
        assertTrue("a page opened in the browser", browser.opened.isEmpty())
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.help_heading)))
    }

    @Test
    fun theScriptLibrarysHelpOpensTheIntroductionToTheKindShownTheLuaFunctionsAndTheManual() {
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.game_title))).fetchSemanticsNodes().isNotEmpty() }
        activate(hasContentDescription(string(R.string.scripts_open, game.name)))

        fromHelp(R.string.help_introduction_triggers, "https://wiki.mudlet.org/w/Manual:Introduction#Triggers")
        activate(hasText(string(R.string.scripts_keys)))
        fromHelp(R.string.help_introduction_keys, "https://wiki.mudlet.org/w/Manual:Introduction#Keybindings")
        fromHelp(R.string.help_lua_functions, "https://wiki.mudlet.org/w/Manual:Lua_Functions")
        fromHelp(R.string.help_manual, "https://wiki.mudlet.org/w/Manual:Contents")
    }

    private fun fromHelp(@StringRes item: Int, url: String) {
        activate(hasContentDescription(string(R.string.scripts_help)))
        activate(hasText(string(item)))
        opens(url)
    }

    private fun opens(url: String) {
        compose.waitUntil(WAIT_MS) { browser.opened.isNotEmpty() }
        assertEquals(listOf(url), browser.opened.toList())
        browser.opened.clear()
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on every window (a menu is one of its own). */
    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(node).fetchSemanticsNodes().isNotEmpty() }
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
