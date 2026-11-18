package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
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
import org.mudlet.android.protocol.Profile
import org.mudlet.android.suggest.SuggestFile
import org.mudlet.android.ui.game.CONNECTION_BANNER_TAG
import org.mudlet.android.ui.game.CommandSuggestions
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.game.HISTORY_SHEET_TAG

/**
 * The game screen's connection controls against a fake engine (docs/multi-profile.md, "The switcher"): the title bar
 * menu's Reconnect and Disconnect, and the title bar's Connect once the player has disconnected, with no banner; the
 * banner a drop brings, read after the title bar and before the console, and above the map's room search with the map
 * in front; and the row below the input line, which takes no room with nothing in it.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionControlsTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("controls ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
    private val banner = hasTestTag(CONNECTION_BANNER_TAG)
    private val input get() = hasSetTextAction() and hasText(string(R.string.input_label))

    @Before
    fun openTheGameConnected() {
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, CONSOLE_LINE)
        session.synced(1)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasText(CONSOLE_LINE))
        session.status("profile", "${game.name} loaded")
        session.status("connection", "connected")
        compose.waitUntil(WAIT_MS) { EngineClient.front.status["connection"] == "connected" }
    }

    @After
    fun forgetThePins() {
        SuggestFile.pinsForProfile(CommandSuggestions.dir(compose.activity), game.name).file.delete()
    }

    @Test
    fun theMenuDisconnectsAndReconnects_andAfterTheGamesOwnDisconnectTheBarOffersConnectAndNoBanner() {
        compose.pickFromGameMenu(string(R.string.connection_disconnect_from, game.name))
        session.receive("disconnect")
        session.status("connection", "disconnected")
        val connect = hasContentDescription(string(R.string.connection_connect_to, game.name)) and hasClickAction()
        waitFor(connect)
        compose.onNode(banner).assertDoesNotExist()

        compose.pickFromGameMenu(string(R.string.connection_reconnect_to, game.name))
        assertEquals(game.host, session.receive("connect").getString("host"))
        session.status("connection", "connected")
        waitUntilGone(connect)
    }

    @Test
    fun aDropsBannerIsReadAfterTheTitleBarAndBeforeTheConsole_andAboveTheMapsRoomSearch() {
        session.status("connection", "disconnected")
        val message = hasText(string(R.string.connection_banner_lost, game.name))
        val reconnect = hasContentDescription(string(R.string.connection_reconnect_to, game.name)) and hasClickAction() and hasAnyAncestor(banner)
        waitFor(message)
        waitFor(reconnect)
        compose.onNode(isRoot()).tryPerformAccessibilityChecks()

        val reading = TalkBackReading(compose)
        reading.assertInOrder(
            listOf(
                "the game title" to hasTestTag(GAME_TITLE_TAG),
                "the title bar's menu" to (hasContentDescription(string(R.string.game_menu)) and hasClickAction()),
                "the banner's message" to message,
                "its Reconnect button" to reconnect,
                "the console" to hasText(CONSOLE_LINE),
                "the input line" to input,
            ),
        )
        reading.assertEveryControlRead()

        activate(hasContentDescription(string(R.string.app_map_show)))
        val search = hasSetTextAction() and hasText(string(R.string.app_map_search))
        waitFor(search)
        compose.onNode(hasTestTag(GAME_TITLE_TAG)).assertIsDisplayed()
        compose.onNode(message).assertIsDisplayed()
        val title = bounds(hasTestTag(GAME_TITLE_TAG))
        val shown = bounds(banner)
        val room = bounds(search)
        assertTrue("the banner, at $shown, is below the title bar's game title, at $title", shown.top >= title.bottom)
        assertTrue("the room search, at $room, is below the banner, at $shown", room.top >= shown.bottom)
    }

    @Test
    fun theRowBelowTheInputLineTakesNoRoomWithNothingInIt() {
        compose.onNode(hasTestTag(GAME_TITLE_TAG)).assertIsDisplayed()
        val alone = bounds(input).bottom

        compose.onNode(input).performTextInput(PINNED)
        compose.onNode(input).performImeAction()
        assertEquals(PINNED, session.receive("send").getString("text"))
        activate(hasContentDescription(string(R.string.history_open)))
        activate(hasContentDescription(string(R.string.history_pin, PINNED)))
        activate(hasContentDescription(string(R.string.history_close)))
        waitUntilGone(hasTestTag(HISTORY_SHEET_TAG))
        val pinned = hasContentDescription(string(R.string.pinned_command, PINNED)) and hasClickAction()
        waitFor(pinned)
        compose.waitForIdle()

        // The pinned command's row pushes the input line up by its own height, which it would not if the empty row had taken that room already
        val raised = alone - bounds(input).bottom
        val row = bounds(pinned).height
        val minimum = with(compose.density) { 48.dp.toPx() }
        assertTrue("the input line rose $raised px for a row of $row px, at least 48 dp ($minimum px)", row >= minimum && raised >= row - SLACK_PX)
    }

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilGone(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on every window. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val INSTANCE = "fake-controls"
        const val CONSOLE_LINE = "the main console"
        const val PINNED = "north"
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L

        // Rounding of the bounds to pixels
        const val SLACK_PX = 1f
    }
}
