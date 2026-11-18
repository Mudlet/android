package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import org.hamcrest.BaseMatcher
import org.hamcrest.CoreMatchers.anyOf
import org.hamcrest.Description
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.CONNECTION_BANNER_TAG
import org.mudlet.android.ui.game.GAME_SHEET_TAG
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import androidx.compose.ui.R as ComposeUiR

/**
 * Two games open against a fake engine (docs/multi-profile.md, "The switcher"): the profile list's open games, the game
 * title with what TalkBack reads and its actions, the title bar's Connect, the menu's Stop and the banner under the
 * title bar for each state of the connection, and the game sheet's Reconnect, Disconnect, switch, close and "Open
 * another game". Controls are found by the labels TalkBack reads
 * and activated through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class GameSwitcherTest {
    private val fake = FakeEngineRule(open = listOf(ALPHA.name, BETA.name))
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var alpha: FakeEngine.Session
    private lateinit var beta: FakeEngine.Session

    private val gameTitle = hasTestTag(GAME_TITLE_TAG)
    private val banner = hasTestTag(CONNECTION_BANNER_TAG)
    private val sheet = hasTestTag(GAME_SHEET_TAG)
    private val input = hasSetTextAction()

    @Before
    fun attach() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) {
            compose.enableAccessibilityChecks(AccessibilityValidator().setRunChecksFromRootView(true).setSuppressingResultMatcher(anyOf(UnderTheAddButton, ScrimBelowTheSheet(string(ComposeUiR.string.close_sheet)))))
        }
        // The one opened last is the front
        beta = fake.engine.nextSession()
        beta.profiles(PROFILES, beta.receive("listProfiles"))
        alpha = beta.forProfile(ALPHA.name)
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.size == 2 && EngineClient.sessions.all { it.linkState.startsWith("attached") } }
        for (session in listOf(alpha, beta)) {
            session.clear()
            session.synced(0)
            session.status("connection", "connected")
        }
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.all { it.status["connection"] == "connected" } }
    }

    /**
     * TouchTargetSizeCheck on a profile's button the Add button floats over: TalkBack is given only the part left
     * uncovered, and the list scrolls the row out from under it. With games open above the profiles, the CI emulator's
     * 640 px screen puts one there.
     */
    private object UnderTheAddButton : BaseMatcher<Any>() {
        override fun matches(item: Any?): Boolean {
            val result = item as? AccessibilityViewCheckResult ?: return false
            if (result.sourceCheckClass != TouchTargetSizeCheck::class.java) return false
            val element = result.element ?: return false
            val list = ancestors(element).firstOrNull { it.isScrollable == true } ?: return false
            val bounds = element.boundsInScreen
            return element.window.allViews.any { view ->
                val over = view.boundsInScreen
                view.isClickable && list !in ancestors(view) && over.top == bounds.bottom && over.left < bounds.right && over.right > bounds.left
            }
        }

        private fun ancestors(element: ViewHierarchyElement) = generateSequence(element.parentView) { it.parentView }

        override fun describeTo(description: Description) {
            description.appendText("a small touch target where the Add button covers a profile's button")
        }
    }

    @Test
    fun theProfileListsOpenGamesBringOneToTheFront_andTheTitleReadsItAndSwitchesWithItsActions() {
        showFromTheList(ALPHA.name)
        assertEquals(ALPHA.name, beta.receive("setFront").getString("name"))
        waitFor(titleReading(ALPHA.name, R.string.connection_connected, 1, 2))
        assertEquals(listOf(reconnect(ALPHA.name), disconnect(ALPHA.name), next(BETA.name)), actionsOf(gameTitle))

        runAction(gameTitle, next(BETA.name))
        assertEquals(BETA.name, beta.receive("setFront").getString("name"))
        waitFor(titleReading(BETA.name, R.string.connection_connected, 2, 2))
        assertEquals(listOf(reconnect(BETA.name), disconnect(BETA.name), previous(ALPHA.name)), actionsOf(gameTitle))
        // As a switch says, and then the focus is on the new game's line
        compose.runOnIdle { assertTrue("TalkBack is told: ${Announcer.current?.text}", Announcer.current!!.text.endsWith(gameState(BETA.name, R.string.connection_connected))) }
        compose.waitUntil(WAIT_MS) { focused(input) }
        compose.onNode(input).assertIsFocused()

        runAction(gameTitle, previous(ALPHA.name))
        assertEquals(ALPHA.name, beta.receive("setFront").getString("name"))
        waitFor(titleReading(ALPHA.name, R.string.connection_connected, 1, 2))
        checkAccessibility()

        // The map in front stays there for the game switched to
        val mapButton = hasContentDescription(string(R.string.app_map_show)) and isToggleable()
        activate(mapButton)
        runAction(gameTitle, next(BETA.name))
        assertEquals(BETA.name, beta.receive("setFront").getString("name"))
        waitFor(titleReading(BETA.name, R.string.connection_connected, 2, 2))
        compose.onNode(mapButton).assertIsOn()
    }

    @Test
    fun theBarAndTheBannerOfferWhatTheConnectionNeeds_andADropIsToldOnce() {
        showFromTheList(BETA.name)
        beta.receive("setFront")
        waitFor(titleReading(BETA.name, R.string.connection_connected, 2, 2))
        assertNothingToDo()

        // Dropped without the player's Disconnect, and the profile does not dial again by itself
        beta.status("connection", "disconnected")
        waitFor(titleReading(BETA.name, R.string.connection_lost, 2, 2))
        compose.runOnIdle { assertTrue("TalkBack is told: ${Announcer.current?.text}", Announcer.current!!.text.endsWith(announced(BETA.name, R.string.connection_lost))) }
        compose.onNode(banner and hasAnyDescendant(hasText(compose.activity.getString(R.string.connection_banner_lost, BETA.name)))).assertIsDisplayed()
        activate(hasContentDescription(string(R.string.connection_reconnect_to, BETA.name)) and hasAnyAncestor(banner))
        val connect = beta.receive("connect")
        assertEquals(BETA.host, connect.getString("host"))
        assertEquals(BETA.port, connect.getInt("port"))

        beta.status("connection", "connecting ${BETA.host}:${BETA.port}")
        waitFor(titleReading(BETA.name, compose.activity.getString(R.string.connection_connecting_to, "${BETA.host}:${BETA.port}"), 2, 2))
        compose.pickFromGameMenu(compose.activity.getString(R.string.connection_stop_to, BETA.name))
        beta.receive("disconnect")
        beta.status("connection", "disconnected")
        waitFor(titleReading(BETA.name, R.string.connection_disconnected, 2, 2))
        compose.onNode(banner).assertDoesNotExist()
        activate(hasContentDescription(string(R.string.connection_connect_to, BETA.name)))
        beta.receive("connect")

        beta.status("connection", "error: host not found")
        waitFor(titleReading(BETA.name, compose.activity.getString(R.string.connection_error, "host not found"), 2, 2))
        compose.onNode(banner and hasAnyDescendant(hasText(compose.activity.getString(R.string.connection_banner_failed, BETA.name, "host not found")))).assertIsDisplayed()
        activate(hasContentDescription(string(R.string.connection_try_again_to, BETA.name)))
        beta.receive("connect")
        beta.status("connection", "connected")
        waitFor(titleReading(BETA.name, R.string.connection_connected, 2, 2))
        assertNothingToDo()

        // The sheet's Disconnect is the player's, so what follows is no drop
        openTheSheet()
        activate(hasContentDescription(disconnect(BETA.name)) and hasText(string(R.string.connection_disconnect)))
        beta.receive("disconnect")
        waitUntilGone(sheet)
        beta.status("connection", "disconnected")
        waitFor(titleReading(BETA.name, R.string.connection_disconnected, 2, 2))
        compose.onNode(banner).assertDoesNotExist()
        compose.onNode(hasContentDescription(string(R.string.connection_connect_to, BETA.name))).assertIsDisplayed()

        openTheSheet()
        activate(hasContentDescription(reconnect(BETA.name)) and hasText(string(R.string.connection_reconnect)))
        assertEquals(BETA.host, beta.receive("connect").getString("host"))
        waitUntilGone(sheet)
    }

    @Test
    fun aProfileThatReconnectsByItselfIsReconnectingThroughItsRetriesUntilConnected() {
        showFromTheList(ALPHA.name)
        beta.receive("setFront")
        waitFor(titleReading(ALPHA.name, R.string.connection_connected, 1, 2))

        alpha.status("connection", "disconnected")
        val reconnecting = titleReading(ALPHA.name, R.string.connection_reconnecting, 1, 2)
        waitFor(reconnecting)
        val told = compose.runOnIdle {
            assertTrue("TalkBack is told: ${Announcer.current?.text}", Announcer.current!!.text.endsWith(announced(ALPHA.name, R.string.connection_reconnecting)))
            Announcer.current!!.serial
        }
        // Mudlet's own retries
        alpha.status("connection", "connecting ${ALPHA.host}:${ALPHA.port}")
        alpha.status("connection", "error: connection refused")
        alpha.status("connection", "disconnected")
        alpha.status("connection", "connecting ${ALPHA.host}:${ALPHA.port}")
        compose.waitUntil(WAIT_MS) { EngineClient.session(ALPHA.name)?.status?.get("connection")?.startsWith("connecting") == true }
        compose.onNode(reconnecting).assertIsDisplayed()
        compose.runOnIdle { assertEquals("the retries are not each told", told, Announcer.current!!.serial) }
        compose.onNode(banner and hasAnyDescendant(hasText(compose.activity.getString(R.string.connection_banner_reconnecting, ALPHA.name)))).assertIsDisplayed()
        compose.onNode(hasContentDescription(string(R.string.connection_stop_to, ALPHA.name)) and hasAnyAncestor(banner)).assertIsDisplayed()

        activate(hasContentDescription(string(R.string.connection_reconnect_now_to, ALPHA.name)))
        alpha.receive("connect")
        alpha.status("connection", "connected")
        waitFor(titleReading(ALPHA.name, R.string.connection_connected, 1, 2))
        assertNothingToDo()
    }

    @Test
    fun theSheetSwitchesAndClosesAfterAsking_andTheLastGameClosedShowsTheProfileList() {
        showFromTheList(BETA.name)
        beta.receive("setFront")
        waitFor(titleReading(BETA.name, R.string.connection_connected, 2, 2))

        openTheSheet()
        compose.onNode(gameRow(BETA.name)).assertIsSelected()
        activate(gameRow(ALPHA.name))
        assertEquals(ALPHA.name, beta.receive("setFront").getString("name"))
        waitUntilGone(sheet)
        waitFor(titleReading(ALPHA.name, R.string.connection_connected, 1, 2))

        runAction(gameTitle, next(BETA.name))
        assertEquals(BETA.name, beta.receive("setFront").getString("name"))
        waitFor(titleReading(BETA.name, R.string.connection_connected, 2, 2))
        // The game the sheet switched away from comes back without it
        compose.onNode(sheet).assertDoesNotExist()

        openTheSheet()
        activate(hasContentDescription(string(R.string.game_close, ALPHA.name)))
        // Connected, so it asks first; Cancel keeps it
        waitFor(hasText(string(R.string.game_close_title, ALPHA.name)))
        activate(hasText(string(R.string.game_close_cancel)))
        beta.assertNoRequest("closeProfile", QUIET_MS)
        activate(hasContentDescription(string(R.string.game_close, ALPHA.name)))
        activate(hasText(string(R.string.game_close_confirm)))
        closeInTheEngine(ALPHA.name)
        waitFor(titleReading(BETA.name, R.string.connection_connected, 1, 1))
        compose.onNode(sheet).assertIsDisplayed()
        compose.onNode(gameRow(ALPHA.name)).assertDoesNotExist()

        // A game not connected closes without asking
        beta.status("connection", "disconnected")
        waitFor(gameRow(BETA.name, R.string.connection_lost))
        activate(hasContentDescription(string(R.string.game_close, BETA.name)))
        closeInTheEngine(BETA.name)
        waitFor(hasText(string(R.string.profiles_title)))
        compose.onNode(hasText(string(R.string.games_title))).assertDoesNotExist()
    }

    @Test
    fun openAnotherGameOpensOneBesideThoseOpen_untilAsManyAsCanBeAre() {
        showFromTheList(ALPHA.name)
        beta.receive("setFront")
        waitFor(titleReading(ALPHA.name, R.string.connection_connected, 1, 2))

        openTheSheet()
        activate(hasText(string(R.string.game_open_another)))
        activate(hasContentDescription(string(R.string.profile_connect_to, GAMMA.name)))
        val open = beta.receive("openProfile")
        assertEquals(GAMMA.name, open.getString("name"))
        assertTrue("beside those open", open.getBoolean("alongside"))
        beta.profiles(PROFILES, open)
        assertEquals(GAMMA.name, beta.receive("setFront").getString("name"))
        waitFor(titleReading(GAMMA.name, R.string.connection_disconnected, 3, 3))
        compose.runOnIdle { assertEquals(listOf(ALPHA.name, BETA.name, GAMMA.name), EngineClient.sessions.map { it.name }) }

        fake.engine.openAlongside(DELTA.name)
        beta.openProfiles()
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.size == EngineClient.MAX_OPEN_GAMES }
        openTheSheet()
        val full = compose.activity.resources.getQuantityString(R.plurals.game_open_another_full, EngineClient.MAX_OPEN_GAMES, EngineClient.MAX_OPEN_GAMES)
        compose.onNode(hasText(full)).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.game_open_another), substring = true) and hasClickAction()).assertIsNotEnabled()
        checkAccessibility()
    }

    private fun showFromTheList(name: String) {
        waitFor(hasText(string(R.string.games_title)))
        activate(hasContentDescription(gameState(name, R.string.connection_connected)))
        waitFor(gameTitle)
    }

    // The engine closes the profile [name] the UI asked it to, and answers
    private fun closeInTheEngine(name: String) {
        val close = beta.receive("closeProfile")
        assertEquals(name, close.getString("name"))
        fake.engine.closeProfile(name)
        beta.send(fake.engine.openProfilesMessage(close.opt("id")))
    }

    private fun openTheSheet() {
        activate(gameTitle)
        waitFor(sheet)
    }

    // Neither the title bar's Connect nor the banner and its buttons
    private fun assertNothingToDo() {
        compose.onNode(banner).assertDoesNotExist()
        val buttons = listOf(R.string.connection_connect_to, R.string.connection_reconnect_now_to, R.string.connection_try_again_to, R.string.connection_stop_to)
        for (button in buttons) compose.onNode(hasContentDescription(compose.activity.getString(button, EngineClient.front.name))).assertDoesNotExist()
        // Reconnect is also the title's action, which is no node of its own
        compose.onNode(hasContentDescription(reconnect(EngineClient.front.name)) and hasClickAction()).assertDoesNotExist()
    }

    private fun gameRow(name: String, @StringRes state: Int = R.string.connection_connected) = hasContentDescription(gameState(name, state)) and hasClickAction()

    private fun titleReading(name: String, @StringRes state: Int, index: Int, count: Int) = titleReading(name, string(state), index, count)

    private fun titleReading(name: String, state: String, index: Int, count: Int) = gameTitle and hasContentDescription(compose.activity.getString(R.string.game_title_description, name, state, index, count))

    private fun gameState(name: String, @StringRes state: Int) = compose.activity.getString(R.string.game_state, name, string(state))

    private fun announced(name: String, @StringRes state: Int) = compose.activity.getString(R.string.connection_announce, name, string(state))

    private fun next(name: String) = compose.activity.getString(R.string.game_next, name)

    private fun previous(name: String) = compose.activity.getString(R.string.game_previous, name)

    private fun reconnect(name: String) = compose.activity.getString(R.string.connection_reconnect_to, name)

    private fun disconnect(name: String) = compose.activity.getString(R.string.connection_disconnect_from, name)

    private fun actionsOf(matcher: SemanticsMatcher): List<String> = compose.onNode(matcher).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    /** What TalkBack's actions menu does with the custom action [label]. */
    private fun runAction(matcher: SemanticsMatcher, label: String) {
        val action = compose.onNode(matcher).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().single { it.label == label }
        compose.runOnIdle { assertTrue(action.action()) }
    }

    private fun focused(matcher: SemanticsMatcher) = try {
        compose.onNode(matcher).assertIsFocused()
        true
    } catch (_: AssertionError) {
        false
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilGone(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    // Each window's: the sheet and its question are windows of their own over the activity's
    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) {
            try {
                roots[i].tryPerformAccessibilityChecks()
            } catch (e: AccessibilityViewCheckException) {
                val flagged = e.results.joinToString("\n") { "${it.element?.className} '${it.element?.contentDescription}' '${it.element?.text}'" }
                throw AssertionError("${e.message}\n$flagged", e)
            }
        }
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on every window. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        checkAccessibility()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val ALPHA = Profile("Alpha", "127.0.0.1", 4001, false, "", true)
        val BETA = Profile("Beta", "127.0.0.1", 4002, false, "", false)
        val GAMMA = Profile("Gamma", "127.0.0.1", 4003, false, "", false)
        val DELTA = Profile("Delta", "127.0.0.1", 4004, false, "", false)
        val PROFILES = listOf(ALPHA, BETA, GAMMA, DELTA)

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
        const val WAIT_MS = 10_000L

        // Long enough for a request that went astray to have arrived
        const val QUIET_MS = 500
    }
}

/**
 * TouchTargetSizeCheck on Material's scrim, whose tap target is labelled [closeSheet]: the strip of it left below a
 * bottom sheet, over the navigation bar, is 24 dp tall. The sheet's close button and Back close it too.
 */
internal class ScrimBelowTheSheet(private val closeSheet: String) : BaseMatcher<Any>() {
    override fun matches(item: Any?): Boolean {
        val result = item as? AccessibilityViewCheckResult ?: return false
        return result.sourceCheckClass == TouchTargetSizeCheck::class.java && result.element?.contentDescription?.toString() == closeSheet
    }

    override fun describeTo(description: Description) {
        description.appendText("a small touch target on the scrim below a bottom sheet")
    }
}
