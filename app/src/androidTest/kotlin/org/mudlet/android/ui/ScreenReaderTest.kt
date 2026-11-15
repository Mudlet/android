package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.RUN_BOLD
import org.mudlet.android.ui.game.Announcer

/**
 * What TalkBack is given, against a fake engine, as the semantics tree shows it: new lines read as one announcement
 * while touch exploration is on (the Announcer's switch stands in for the system's), a script's announce(), the
 * console's lines as plain text, and the screen reader settings, which go to the profile with runLua.
 */
@RunWith(AndroidJUnit4::class)
class ScreenReaderTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private var nextSeq = 1L

    @Before
    fun openTheGameScreen() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        reset()
        compose.runOnIdle { Settings.screenReaderOffered.set(true) }
        session = fake.engine.nextSession()
        // EngineClient lives as long as the process, so an earlier test's lines would still be there
        session.clear()
        session.synced(0)
        activate(hasText(string(R.string.profiles_open_game)))
        compose.onNodeWithText(string(R.string.game_title)).assertIsDisplayed()
    }

    // The settings are stored and the Announcer lives as long as the process, so both would outlast the test
    @After
    fun reset() = compose.runOnIdle {
        Announcer.touchExplorationOverride = null
        for (setting in Settings.mudletSettings + Settings.screenReaderOffered) setting.reset()
    }

    @Test
    fun newLinesAreReadTogetherWhileTouchExplorationIsOn() {
        val before = serial()
        line("not read: touch exploration is off")
        SystemClock.sleep(QUIET_MS)
        assertEquals("no announcement while touch exploration is off", before, serial())

        compose.runOnIdle { Announcer.touchExplorationOverride = true }
        line("not read: replayed", replay = true)
        SystemClock.sleep(QUIET_MS)
        assertEquals("no announcement of a replayed line", before, serial())

        // A few lines are read as they are, without the blank one
        for (text in listOf("first of two", "", "second of two")) session.line(nextSeq++, text)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(announcement("first of two\nsecond of two", LiveRegionMode.Polite)).fetchSemanticsNodes().size == 1 }
        SystemClock.sleep(QUIET_MS)
        assertEquals("the lines are one announcement", before + 1, serial())

        val burst = (1..8).map { "burst line $it" }
        burst.forEach { session.line(nextSeq++, it) }
        val expected = compose.activity.resources.getQuantityString(R.plurals.announce_burst, 8, 8, burst.takeLast(3).joinToString("\n"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(announcement(expected, LiveRegionMode.Polite)).fetchSemanticsNodes().size == 1 }
        SystemClock.sleep(QUIET_MS)
        assertEquals("the burst is one announcement", before + 2, serial())

        compose.runOnIdle { Settings.readNewText.set(false) }
        line("not read: the setting is off")
        SystemClock.sleep(QUIET_MS)
        assertEquals("no announcement with the setting off", before + 2, serial())
    }

    @Test
    fun announceIsReadAssertiveOrPolite() {
        session.announce("You are hungry.", assertive = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(announcement("You are hungry.", LiveRegionMode.Assertive)).fetchSemanticsNodes().size == 1 }
        val first = serial()
        // The same words again are a second announcement, in the other live region
        SystemClock.sleep(QUIET_MS)
        session.announce("You are hungry.", assertive = false)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(announcement("You are hungry.", LiveRegionMode.Polite)).fetchSemanticsNodes().size == 1 }
        assertEquals(first + 1, serial())
        assertEquals("the first one's live region is empty", 0, compose.onAllNodes(announcement("You are hungry.", LiveRegionMode.Assertive)).fetchSemanticsNodes().size)
    }

    @Test
    fun eachConsoleLineIsANodeWithItsPlainText() {
        val styled = "Red and bold, then plain"
        line(styled, listOf(listOf(0, 12, 1, -1, RUN_BOLD)))
        line("a second line")
        for (text in listOf(styled, "a second line")) {
            val node = compose.onNodeWithText(text).fetchSemanticsNode()
            val shown = node.config[SemanticsProperties.Text].single()
            assertEquals(text, shown.text)
            assertTrue("'$text' is read without its styles: ${shown.spanStyles}", shown.spanStyles.isEmpty() && shown.paragraphStyles.isEmpty())
        }
    }

    @Test
    fun theSettingsGoToTheProfileWithRunLua() {
        session.status("luaResult", "a script's")
        session.status("profile", "Test loaded")
        val all = session.receive("runLua")
        assertEquals(
            "setConfig(\"announceIncomingText\", true)\nsetConfig(\"advertiseScreenReader\", false)\nsetConfig(\"blankLinesBehaviour\", \"show\")",
            all.getString("code"),
        )
        // The answer to the app's own runLua is no script's result
        session.reply("luaResult", "ran", all.getLong("id"))
        line("after the answer")
        compose.runOnIdle { assertEquals("a script's", EngineClient.lastLuaResult) }

        activate(hasContentDescription(string(R.string.settings_title)))
        activate(hasText(string(R.string.settings_read_new_text)), scroll = true)
        assertEquals("setConfig(\"announceIncomingText\", false)", session.receive("runLua").getString("code"))
        activate(hasText(string(R.string.settings_advertise_screen_reader)), scroll = true)
        assertEquals("setConfig(\"advertiseScreenReader\", true)", session.receive("runLua").getString("code"))
        activate(hasText(string(R.string.settings_blank_lines_hide)), scroll = true)
        assertEquals("setConfig(\"blankLinesBehaviour\", \"hide\")", session.receive("runLua").getString("code"))
        compose.runOnIdle {
            assertEquals(false, Settings.readNewText.value)
            assertEquals(true, Settings.advertiseScreenReader.value)
            assertEquals(Settings.BlankLines.HIDE, Settings.blankLines.value)
        }
    }

    @Test
    fun theFirstRunWithTouchExplorationOffersToTellGames() {
        session.status("profile", "Test loaded")
        session.receive("runLua")
        compose.runOnIdle {
            Settings.screenReaderOffered.set(false)
            Announcer.touchExplorationOverride = true
        }
        val title = hasText(string(R.string.screen_reader_offer_title))
        compose.onNode(title).assertIsDisplayed()
        // The dialog is a window of its own, with a root of its own
        compose.onNode(isRoot() and hasAnyDescendant(title)).tryPerformAccessibilityChecks()
        compose.onNode(hasText(string(R.string.screen_reader_offer_yes)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("setConfig(\"advertiseScreenReader\", true)", session.receive("runLua").getString("code"))
        compose.runOnIdle {
            assertEquals(true, Settings.advertiseScreenReader.value)
            assertEquals(true, Settings.screenReaderOffered.value)
        }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(title).fetchSemanticsNodes().isEmpty() }
    }

    private fun serial() = compose.runOnIdle { Announcer.current?.serial ?: 0L }

    private fun line(text: String, runs: List<List<Int>> = emptyList(), replay: Boolean = false) {
        session.line(nextSeq++, text, runs, replay = replay)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** A live region of [mode] that TalkBack would read as [text]. */
    private fun announcement(text: String, mode: LiveRegionMode) = SemanticsMatcher("live region $mode reading '$text'") {
        it.config.getOrNull(SemanticsProperties.LiveRegion) == mode && it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(text)
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher, scroll: Boolean = false) {
        compose.onRoot().tryPerformAccessibilityChecks()
        val node = compose.onNode(label and hasClickAction())
        // The settings screen scrolls, and TalkBack scrolls to what it moves to
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val WAIT_MS = 10_000L

        // Well past the Announcer's wait for more lines
        const val QUIET_MS = 1_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
