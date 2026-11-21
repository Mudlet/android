package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * "Undo the game's own wrapping" on a game's Display page against a fake engine: last on the page and marked
 * experimental, with "The game wraps at" under it only while it is on, the width kept while hidden, and a search that
 * never lists the hidden width.
 */
@RunWith(AndroidJUnit4::class)
class UndoWrapSettingsTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val switch get() = hasText(string(R.string.settings_undo_wrap)) and hasClickAction()
    private val width get() = hasContentDescription(string(R.string.settings_undo_wrap_width)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    @Before
    fun openTheGameScreen() {
        compose.enableAccessibilityChecksOfWhatShows()
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_TITLE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        // As the engine does as the profile loads: EngineClient outlives a test, so an earlier one's would be there
        session.status("profile", "${GAME.name} loaded")
        session.settings(MUDLETS, session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable } }
    }

    @Test
    fun theWidthShowsOnlyWhileTheSwitchIsOn_belowTheSwitchLastOnThePage() {
        compose.pickFromGameMenu(string(R.string.settings_title))
        val page = hasText(string(R.string.settings_display)) and hasClickAction()
        compose.scrollSettingsHubTo(page)
        activate(page)
        session.settings(MUDLETS, session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.setting(Settings.undoWrapWidth) == 100 } }

        // TalkBack reads "Experimental" as part of the switch's row
        compose.onNode(switch).performScrollTo().assertIsDisplayed().assert(hasText(string(R.string.settings_experimental)))
        assertEquals(Role.Switch, compose.onNode(switch).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        assertTrue("no width while the switch is off", compose.onAllNodes(width).fetchSemanticsNodes().isEmpty())
        val links = hasText(string(R.string.settings_game_links)) and hasClickAction()
        assertTrue("after the game's links", top(links) < top(switch))
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(switch, scroll = true)
        assertEquals(mapOf("undoServerWrap" to true), values(session.receive("setSettings")))
        waitFor(width)
        val shown = compose.activity.resources.getQuantityString(R.plurals.settings_undo_wrap_width_value, 100, 100)
        compose.onNode(width).performScrollTo().assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, shown))
        assertTrue("the width straight under the switch", top(switch) < top(width))
        compose.onRoot().tryPerformAccessibilityChecks()

        // Hidden, the width is neither sent nor lost
        activate(switch, scroll = true)
        assertEquals(mapOf("undoServerWrap" to false), values(session.receive("setSettings")))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(width).fetchSemanticsNodes().isEmpty() }
        activate(switch, scroll = true)
        assertEquals(mapOf("undoServerWrap" to true), values(session.receive("setSettings")))
        waitFor(width)
        compose.onNode(width).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, shown))
        session.assertNoRequest("setSettings", 500)
        compose.runOnIdle { assertEquals("new games keep Mudlet's", false, Settings.undoWrap.value) }
    }

    @Test
    fun aSearchListsTheWidthOnlyWithTheSwitchOn_andOpensItsPageAtIt() {
        compose.pickFromGameMenu(string(R.string.settings_title))
        compose.onNode(hasSetTextAction()).performTextInput("width")
        waitFor(switch)
        val widthResult = hasText(string(R.string.settings_undo_wrap_width)) and hasClickAction()
        assertTrue("no hidden width in the results", compose.onAllNodes(widthResult).fetchSemanticsNodes().isEmpty())
        compose.onNode(switch).assert(hasText(string(R.string.settings_experimental)))

        activate(switch, scroll = true)
        assertEquals(mapOf("undoServerWrap" to true), values(session.receive("setSettings")))
        waitFor(widthResult)
        compose.onRoot().tryPerformAccessibilityChecks()
        activate(widthResult, scroll = true)
        session.settings(MUDLETS + ("undoServerWrap" to true), session.receive("getSettings").getLong("id"))
        waitFor(width)
        compose.onNode(width).assertIsDisplayed()
    }

    private fun top(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.top

    private fun values(request: JSONObject): Map<String, Any> = request.getJSONObject("values").let { v -> v.keys().asSequence().associateWith { v.get(it) } }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, on a page scrolled to as TalkBack does. */
    private fun activate(label: SemanticsMatcher, scroll: Boolean = false) {
        waitFor(label and hasClickAction())
        val node = compose.onNode(label and hasClickAction())
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private companion object {
        val GAME = Profile("Wrapping", "127.0.0.1", 4000, false, "", false)

        // The game's values: Mudlet's, but for the width it wraps at
        val MUDLETS = mapOf("undoServerWrap" to false, "undoServerWrapWidth" to 100)
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
