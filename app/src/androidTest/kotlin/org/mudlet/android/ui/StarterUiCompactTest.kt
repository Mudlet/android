package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
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
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile

/**
 * The starter UI as it lays itself out in a compact main window (scripts/mudlet-patches/0050), replayed by a fake
 * engine on an upright phone: its bars read as progress bars by their names, its chat buttons as buttons named for
 * their tabs, after the console, its strips read as nothing, the app's own map a button away, and a button's tap reaching
 * the engine as `pointer` events, its tab then covering the console, which TalkBack then does not read, until the next
 * tap closes it.
 */
@RunWith(AndroidJUnit4::class)
class StarterUiCompactTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private lateinit var starterUi: StarterUiCompact

    private val console get() = hasScrollToIndexAction() and !hasAnyAncestor(hasContentDescription(string(R.string.window_description, ""), substring = true))

    @Before
    fun showTheStarterUi() {
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, CONSOLE_LINE)
        session.synced(1)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        waitFor(hasText(CONSOLE_LINE))
        // Automatic draws the starter UI fitted, as it lays out for any shape
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
        // With no main area yet, the console fills the game screen's area
        starterUi = StarterUiCompact.show(session, compose.onNode(console).fetchSemanticsNode().boundsInRoot, compose.density.density)
        waitFor(chatButton("Channels chat"))
    }

    @Test
    fun theBarsAreReadAsProgressByNameThenTheChatButtonsAsButtonsAfterTheConsoleAndTheStripsNot() {
        waitFor(gauge("Mana"))
        compose.onNode(chatButton("Tells chat, 2 new")).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val reading = TalkBackReading(compose)
        reading.assertInOrder(
            listOf(
                "the console" to console,
                "the health bar" to gauge("Health"),
                "the mana bar" to gauge("Mana"),
                "the All button" to chatButton("All chat"),
                "the Tells button" to chatButton("Tells chat, 2 new"),
                "the Channels button" to chatButton("Channels chat"),
            ),
        )
        assertTrue("the strips are not read: ${reading.read}", reading.read.none { "adjLabel" in it })
        assertTrue("the app's own map is a button away", reading.reads(hasContentDescription(string(R.string.app_map_show)) and hasClickAction()))
        reading.assertEveryControlRead()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aChatButtonsTapReachesTheEngineAndItsTabCoversTheConsoleWhichIsNotReadUntilTheNextTapClosesIt() {
        activate(chatButton("Tells chat, 2 new"))
        assertTappedOn(StarterUiCompact.button("tells"))
        // What the starter UI does with the tap: its tab over the console's text, between the bars and the buttons
        session.windows(listOf(starterUi.pane("tells", open = true)))
        session.windowText("BaseUI_chat_tells", listOf(TELL))
        StarterUiCompact.paintButton(session, "tells", "Tells", "Tells chat, open")
        waitFor(hasText(TELL))
        compose.onNode(hasText(TELL)).assertIsDisplayed()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(CONSOLE_LINE)).fetchSemanticsNodes().isEmpty() }
        val reading = TalkBackReading(compose)
        assertTrue("the open tab is read: ${reading.read}", reading.reads(hasContentDescription(string(R.string.window_description, "BaseUI_chat_tells"))))
        reading.assertEveryControlRead()
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(chatButton("Tells chat, open"))
        assertTappedOn(StarterUiCompact.button("tells"))
        session.windows(listOf(starterUi.pane("tells", open = false)))
        StarterUiCompact.paintButton(session, "tells", "Tells", "Tells chat")
        waitFor(hasText(CONSOLE_LINE))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(TELL)).fetchSemanticsNodes().isEmpty() }
        waitFor(chatButton("Tells chat"))
    }

    private fun chatButton(name: String) = hasContentDescription(name) and hasClickAction()

    private fun gauge(name: String) = hasContentDescription(name) and
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(50f, 0f..100f))

    // The pointer events of one tap on [window], up to its release
    private fun assertTappedOn(window: String) {
        val events = ArrayList<JSONObject>()
        do {
            events.add(session.receive("pointer"))
        } while (events.last().getString("type") != "release")
        assertEquals(listOf("press", "release"), events.map { it.getString("type") }.filter { it != "enter" })
        assertTrue("on $window: $events", events.all { it.getString("window") == window })
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        const val INSTANCE = "fake-starter-compact"
        const val CONSOLE_LINE = "the main console"
        const val TELL = "Ana tells you, 'meet me at the fountain'"

        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
