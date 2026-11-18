package org.mudlet.android.ui

import android.Manifest
import android.content.ClipboardManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.RUN_RGB
import org.mudlet.android.protocol.RUN_UNDERLINE
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * A script changing a line already shown (`lineUpdated`), against a fake engine: the console draws, reads, copies and
 * finds the line as it now is, draws nothing in a deleted line's place, repaints a link a script restyled, leaves the
 * view and what TalkBack reads alone when the line changed is not on screen, and takes a new engine instance's seqs as
 * naming only its own lines.
 */
@RunWith(AndroidJUnit4::class)
class ConsoleLineUpdatesTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("line updates ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
    private val said = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    @Before
    fun openTheGame() {
        compose.enableAccessibilityChecksOfWhatShows()
        // A new engine instance for each test, so no line of an earlier test is kept
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        for (seq in 1L..FILLER) session.line(seq, filler(seq))
        session.line(FILLER + 1, BEFORE)
        session.line(FILLER + 2, LINE)
        session.line(FILLER + 3, AFTER)
        session.synced(FILLER + 3)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
        waitFor(hasText(AFTER))
    }

    @Test
    fun anEditedLineIsDrawnReadCopiedAndFoundAsItNowIs() {
        session.lineUpdated(FILLER + 2, EDITED)
        waitFor(hasText(EDITED))
        compose.onNode(hasText(LINE)).assertDoesNotExist()
        checkAccessibility()

        val label = string(R.string.console_copy_line)
        val action = compose.onNode(hasText(EDITED)).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
        compose.waitUntil(WAIT_MS) { clipboard() == EDITED }

        compose.pickFromGameMenu(string(R.string.console_search))
        val field = hasSetTextAction() and hasText(string(R.string.console_search_field))
        waitFor(field)
        compose.onNode(field).performTextInput("hard")
        waitFor(said and hasText(compose.activity.resources.getQuantityString(R.plurals.console_search_count, 1, 1)))
        activate(hasContentDescription(string(R.string.console_search_older)))
        waitFor(said and hasText(EDITED))
    }

    @Test
    fun aDeletedLineIsDrawnAsNothingAndTheLinesAfterItStay() {
        session.lineUpdated(FILLER + 2, deleted = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(LINE)).fetchSemanticsNodes().isEmpty() }
        checkAccessibility()
        // No empty row is left between the lines either side of it: they are as far apart as any two lines
        val above = compose.onNode(hasText(filler(FILLER))).fetchSemanticsNode().boundsInRoot
        val before = compose.onNode(hasText(BEFORE)).fetchSemanticsNode().boundsInRoot
        val after = compose.onNode(hasText(AFTER)).fetchSemanticsNode().boundsInRoot
        assertEquals(before.top - above.bottom, after.top - before.bottom, 1f)
        // The lines after it, and a new one, keep coming
        session.line(FILLER + 4, NEXT)
        waitFor(hasText(NEXT))
        compose.onNode(hasText(AFTER)).assertIsDisplayed()
    }

    @Test
    fun aLinkAScriptRestyledIsDrawnInItsNewColourAndStillFollowed() {
        val link = listOf(4, 5, -1, -1, RUN_UNDERLINE, LINK)
        session.line(FILLER + 4, LINKED, listOf(listOf(0, 4, -1, -1, 0, 0), link))
        waitFor(hasText(LINKED))
        val asked = session.receive("getLink")
        session.send(linkMessage("walk north", asked.get("id")))
        val follow = string(R.string.console_link_follow, "walk north")
        compose.waitUntil(WAIT_MS) { actions(LINKED).any { it.label == follow } }

        session.lineUpdated(FILLER + 4, LINKED, listOf(listOf(0, 4, -1, -1, 0, 0), listOf(4, 5, PURPLE, -1, RUN_UNDERLINE, LINK)))
        compose.waitUntil(WAIT_MS) { colourAt(LINKED, 6) == Color(0xFF800080) }
        compose.waitUntil(WAIT_MS) { actions(LINKED).any { it.label == follow } }
        val action = actions(LINKED).single { it.label == follow }
        compose.runOnIdle { action.action() }
        assertEquals(LINK, session.receive("followLink").getInt("link"))
    }

    @Test
    fun anUpdateToALineOutOfViewLeavesTheViewAndWhatTalkBackReadsAsTheyWere() {
        val list = hasScrollToIndexAction() and hasAnyDescendant(hasText(AFTER))
        compose.onNode(list).performScrollToIndex(FILLER.toInt() / 2)
        waitFor(hasText(filler(FILLER / 2 + 1)))
        compose.waitForIdle()
        val reading = TalkBackReading(compose).read

        // Above the view, and below it
        session.lineUpdated(1, CHANGED)
        session.lineUpdated(2, deleted = true)
        session.lineUpdated(FILLER + 2, EDITED)
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.lines.any { it.text == EDITED } } }
        compose.waitForIdle()

        assertEquals(reading, TalkBackReading(compose).read)
        compose.onNode(hasText(EDITED)).assertDoesNotExist()
        compose.onNode(hasText(CHANGED)).assertDoesNotExist()
    }

    @Test
    fun aNewEngineInstancesUpdateChangesOnlyItsOwnLinesThoughTheirSeqsRepeat() {
        val instance = "$INSTANCE-new-${System.nanoTime()}"
        fake.engine.instance = instance
        session.close()
        session = fake.engine.nextSession()
        waitFor(hasText(string(R.string.marker_new_engine, instance)))
        session.line(FILLER + 2, FRESH)
        session.synced(FILLER + 2)
        // The new instance's line before it was skipped, as a gap skips lines, so only the old instance has that seq
        session.lineUpdated(FILLER + 1, deleted = true)
        session.lineUpdated(FILLER + 2, EDITED)
        waitFor(hasText(EDITED))
        compose.onNode(hasText(FRESH)).assertDoesNotExist()
        compose.onNode(hasText(BEFORE)).assertIsDisplayed()
        compose.onNode(hasText(LINE)).assertIsDisplayed()
    }

    private fun filler(seq: Long) = "Filler line $seq."

    private fun actions(text: String) = compose.onNode(hasText(text)).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    // The colour the character [index] of the line [text] is drawn in
    private fun colourAt(text: String, index: Int): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.text.spanStyles.last { index >= it.start && index < it.end }.item.color
    }

    private fun linkMessage(tooltip: String, id: Any) = JSONObject().put("t", "link").put("link", LINK)
        .put("tooltip", tooltip).put("menu", false).put("menuTitle", "").put("items", JSONArray())
        .put("spoiler", false).put("revealed", false).put("disabled", false).put("concealed", false).put("visited", false).put("selected", false)
        .put("re", id)

    private fun clipboard(): String? {
        var text: String? = null
        compose.runOnUiThread {
            text = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        return text
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    // Each window's: a menu is a window of its own over the activity's
    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    }

    /** Activates the control TalkBack reads as [label], through its accessibility action, after the checks. */
    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        checkAccessibility()
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val INSTANCE = "fake-line-updates"
        const val FILLER = 80L
        const val BEFORE = "You see a rat."
        const val LINE = "A rat bites you."
        const val EDITED = "A rat bites you hard."
        const val AFTER = "You hit the rat."
        const val NEXT = "The rat flees."
        const val FRESH = "A new engine's line."
        const val CHANGED = "A line far above, changed."
        const val LINKED = "go: north"
        const val LINK = 7
        const val PURPLE = RUN_RGB or 0x800080
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
