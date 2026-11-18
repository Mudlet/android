package org.mudlet.android.ui

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A game script's openUrl() against a fake engine: while the game's screen is in front an http, https or mailto page
 * opens in the app the system picks and the engine is told so, once until the app comes back to the front; in the
 * background, before the game's screen shows, or for any other scheme, it is refused. The link never reaches the log.
 * The browser is not started: the test sees the intent only.
 */
@RunWith(AndroidJUnit4::class)
class ScriptWebPagesTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val browser = Browser()

    // A result from a monitor is delivered to the activity, which pauses and resumes it as coming back from the browser
    // would; a blocking one gives a plain startActivity none
    private val blocker = Instrumentation.ActivityMonitor(
        IntentFilter().apply {
            addAction(Intent.ACTION_VIEW)
            addAction(Intent.ACTION_SENDTO)
            addCategory(Intent.CATEGORY_BROWSABLE)
            listOf("http", "https", "mailto").forEach(::addDataScheme)
        },
        null,
        true,
    )
    private lateinit var session: FakeEngine.Session
    private var id = 0L

    /** Every intent with a link the app starts; [blocker] keeps it from starting */
    private class Browser : Instrumentation.ActivityMonitor() {
        val started = CopyOnWriteArrayList<Intent>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.data != null) started += intent
            return null
        }
    }

    @Before
    fun listTheProfile() {
        instrumentation.addMonitor(browser)
        instrumentation.addMonitor(blocker)
        session = fake.engine.nextSession()
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
    }

    @After
    fun removeBrowser() {
        instrumentation.removeMonitor(browser)
        instrumentation.removeMonitor(blocker)
    }

    @Test
    fun aPageOpensWhileTheGameIsInFrontOnceUntilTheAppComesBack() {
        assertFalse("a page opened before the game's screen showed", open(PAGE))
        showTheGame()

        assertTrue("the page did not open", open(PAGE))
        val viewed = browser.started.single()
        assertEquals(Intent.ACTION_VIEW, viewed.action)
        assertEquals(PAGE, viewed.dataString)
        assertTrue("a page goes to a browser", viewed.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertFalse("a second page opened before the app came back to the front", open("https://example.org/second"))

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertTrue("a mail did not open once the app came back", open(MAIL))
        assertEquals(Intent.ACTION_SENDTO, browser.started.last().action)
        assertEquals(MAIL, browser.started.last().dataString)
        assertEquals(2, browser.started.size)

        Log.w(TAG, SENTINEL)
        val log = readLog()
        assertTrue("the log was not read", log.any { it.contains(SENTINEL) })
        // Other tests' links on example.org may be in the log already
        val found = log.filter { line -> listOf("example.org/a", "example.org/second", "someone@example.org").any { it in line } }
        assertTrue("a script's link is in the log:\n${found.joinToString("\n")}", found.isEmpty())
    }

    @Test
    fun aPageIsRefusedInTheBackground() {
        showTheGame()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        assertFalse("a page opened while the app was not in front", open(PAGE))
        assertTrue("a page started an app: ${browser.started}", browser.started.isEmpty())
    }

    @Test
    fun aLinkOfAnotherSchemeIsRefused() {
        showTheGame()
        for (url in listOf("file:///sdcard/x", "content://org.mudlet.mudlet/x", "intent://x#Intent;scheme=https;end", "game://open", "javascript:alert(1)", "example.org")) {
            assertFalse("$url opened", open(url))
        }
        assertTrue("a link started an app: ${browser.started}", browser.started.isEmpty())
        assertTrue("an upper-case scheme did not open", open("HTTPS://example.org/upper"))
        assertEquals("https", browser.started.single().data?.scheme)
    }

    private fun open(url: String): Boolean {
        session.send(JSONObject().put("t", "openUrl").put("id", ++id).put("url", url))
        val answer = session.receive("answerOpenUrl")
        assertEquals(id, answer.getLong("id"))
        return answer.getBoolean("opened")
    }

    private fun showTheGame() {
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        compose.waitUntil(WAIT_MS) { displayed("the main console") }
    }

    private fun displayed(text: String) = try {
        compose.onNodeWithText(text).assertIsDisplayed()
        true
    } catch (_: AssertionError) {
        false
    }

    private fun activate(label: SemanticsMatcher) {
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    // As About > Report a problem reads it: this app's own lines, whose uid the instrumentation shares
    private fun readLog(): List<String> {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(10, TimeUnit.SECONDS)
        return lines
    }

    private companion object {
        const val TAG = "ScriptWebPages"
        const val PAGE = "https://example.org/a%20b?c=1"
        const val MAIL = "mailto:someone@example.org"
        const val SENTINEL = "log-Wp5-sentinel"
        val GAME = Profile("Web Page Game", "w.example", 4000, false, "", false)
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
