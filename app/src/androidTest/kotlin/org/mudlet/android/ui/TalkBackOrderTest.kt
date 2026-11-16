package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.core.view.ViewCompat
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
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineMessages.appMap
import org.mudlet.android.ui.EngineMessages.bar
import org.mudlet.android.ui.EngineMessages.button

/**
 * The order TalkBack moves through the game screen in (docs/architecture.md, "On-device checklist"), against a fake
 * engine, over a screen with a mini console, a button bar and the map behind the text: the title bar, the console, the
 * input line and the screen's own buttons, then the script windows, then the button bars, and last the last
 * announcement; with the map in front, the title bar, the map and the connection bar, and nothing the map covers. The order is the one Compose hands TalkBack, each node's traversalBefore, not one worked out here.
 */
@RunWith(AndroidJUnit4::class)
class TalkBackOrderTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    @Before
    fun openTheGameScreen() {
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        compose.runOnIdle { Settings.mapBehindText.reset() }
        // A new engine instance, so no window or bar of an earlier test is left
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
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
    }

    @After
    fun defaults() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
    }

    @Test
    fun theConsoleInputAndButtonsThenTheWindowsThenTheBarsThenTheLastAnnouncement() {
        showWindowAndBar()
        // Any size: the map behind the text fits whatever frame comes
        session.send(appMap(100, 100))
        session.announce(ANNOUNCEMENT, assertive = false)
        waitFor(hasText(WINDOW_ROW))
        waitFor(hasText("North") and hasClickAction())
        waitFor(hasContentDescription(ANNOUNCEMENT))
        compose.waitUntil(WAIT_MS) { AppMapClient.map != null }
        compose.onRoot().tryPerformAccessibilityChecks()

        val reading = Reading()
        val window = hasContentDescription(string(R.string.window_description, "chat"))
        val expected = listOf(
            "the title bar's map button" to (hasContentDescription(string(R.string.app_map_show)) and hasClickAction()),
            "the console" to hasText(CONSOLE_LINE),
            "the input line" to hasSetTextAction(),
            "its Send button" to (hasContentDescription(string(R.string.input_send)) and hasClickAction()),
            "the quick bar" to (hasText(string(R.string.history_open)) and hasClickAction()),
            "the connection bar" to (hasText(string(R.string.connection_disconnect)) and hasClickAction()),
            "the mini console" to window,
            "the button bar" to hasContentDescription(string(R.string.bar_description, "Moves")),
            "the last announcement" to hasContentDescription(ANNOUNCEMENT),
        )
        val places = reading.assertInOrder(expected)

        // The map behind the text adds nothing between the console's lines and the input line
        val console = compose.onNode(hasScrollToIndexAction() and !hasAnyAncestor(window)).fetchSemanticsNode().id
        val beforeInput = reading.order.subList(places[1].second, places[2].second)
        assertTrue("only the console's lines before the input line: ${reading.read}", beforeInput.all { console in reading.nodes.getValue(it).ancestorIds() })

        reading.assertEveryControlRead()
    }

    @Test
    fun withTheMapInFrontTheTitleBarThenTheMapThenTheConnectionBar() {
        showWindowAndBar()
        waitFor(hasText(WINDOW_ROW))
        waitFor(hasText("North") and hasClickAction())
        activate(hasContentDescription(string(R.string.app_map_show)))
        val map = hasContentDescription(string(R.string.map_description)) or hasContentDescription(string(R.string.map_not_drawn))
        waitFor(map)
        compose.onRoot().tryPerformAccessibilityChecks()

        val reading = Reading()
        val expected = listOf(
            "the title bar's map button" to (hasContentDescription(string(R.string.app_map_show)) and hasClickAction()),
            "the room search" to (hasSetTextAction() and hasText(string(R.string.app_map_search))),
            "the map" to map,
            "the connection bar" to (hasText(string(R.string.connection_disconnect)) and hasClickAction()),
        )
        reading.assertInOrder(expected)

        // Nothing the map covers is read
        val covered = listOf(
            "the console" to hasText(CONSOLE_LINE),
            "the input line" to (hasSetTextAction() and hasText(string(R.string.input_label))),
            "the quick bar" to (hasText(string(R.string.history_open)) and hasClickAction()),
            "the mini console" to hasContentDescription(string(R.string.window_description, "chat")),
            "the button bar" to hasContentDescription(string(R.string.bar_description, "Moves")),
        )
        for ((name, matcher) in covered) {
            assertTrue("$name is not read: TalkBack reads ${reading.read}", compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty())
        }
        reading.assertEveryControlRead()
    }

    /** The screen's nodes as TalkBack moves through them now: see [traversalOrder]. */
    private inner class Reading {
        val nodes = compose.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes().associateBy { it.id }
        val order = traversalOrder(nodes.values.filter { it.parent != null }.map { it.id })
        val read = order.map { describe(nodes.getValue(it)) }

        /** Where each of [expected] is first read, which is in that order, each in its own place. */
        fun assertInOrder(expected: List<Pair<String, SemanticsMatcher>>): List<Pair<String, Int>> {
            val places = expected.map { (name, matcher) ->
                val target = compose.onNode(matcher).fetchSemanticsNode().id
                name to order.indexOfFirst { nodes.related(it, target) }
            }
            // Each in its own place, so no node they are all within stands in for them
            val at = places.map { it.second }
            assertTrue("each is read, in its own place: $places; TalkBack reads $read", at.all { it >= 0 } && at.distinct() == at)
            assertEquals("TalkBack reads $read", places, places.sortedBy { it.second })
            return places
        }

        // Every control is reached by swiping, none by touch alone
        fun assertEveryControlRead() {
            for (control in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
                assertTrue("${describe(control)} is read: TalkBack reads $read", order.any { nodes.related(it, control.id) })
            }
        }
    }

    // The chat is the top right quarter of Mudlet's 800 by 600 main window; a bar above the console
    private fun showWindowAndBar() {
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
        session.windows(listOf(FakeEngine.window("chat", x = 400, y = 0, w = 400, h = 300, columns = 50)), full = true)
        session.windowText("chat", listOf(WINDOW_ROW))
        session.buttonBars(bar(1, "Moves", "top", listOf(button(10, "North"))))
    }

    /**
     * The nodes [ids] as TalkBack moves through them: each node's traversalBefore, which Compose works out only while
     * an accessibility service is on, so its switch for its own tests turns that on here, and gives a test through an
     * extra of the node's AccessibilityNodeInfo. The switch is off again before the next frame, as Compose would
     * otherwise send events the system refuses with no service on.
     */
    private fun traversalOrder(ids: List<Int>): List<Int> {
        val next = compose.runOnUiThread {
            // setContent's ComposeView, and in it the view that holds the semantics
            val view = (compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0)
            val delegate = checkNotNull(ViewCompat.getAccessibilityDelegate(view)) { "no accessibility delegate on $view" }
            val forceEnabled = checkNotNull(delegate.javaClass.methods.firstOrNull { it.name.startsWith(FORCE_ENABLED_SETTER) }) {
                "Compose's ${delegate.javaClass.name} has no $FORCE_ENABLED_SETTER"
            }
            forceEnabled.invoke(delegate, true)
            try {
                val provider = checkNotNull(view.accessibilityNodeProvider) { "no accessibility node provider on $view" }
                ids.mapNotNull { id ->
                    val info = provider.createAccessibilityNodeInfo(id) ?: return@mapNotNull null
                    provider.addExtraDataToAccessibilityNodeInfo(id, info, TRAVERSAL_BEFORE, null)
                    if (info.extras.containsKey(TRAVERSAL_BEFORE)) id to info.extras.getInt(TRAVERSAL_BEFORE) else null
                }.toMap()
            } finally {
                forceEnabled.invoke(delegate, false)
            }
        }
        val first = next.keys - next.values.toSet()
        assertEquals("one node TalkBack starts at, of the order $next", 1, first.size)
        val order = generateSequence(first.single()) { next[it] }.take(next.size + 1).toList()
        assertEquals("one chain through every node, without a loop, of $next", next.size + 1, order.distinct().size)
        return order
    }

    // The same node, the one it is read within, or one read within it
    private fun Map<Int, SemanticsNode>.related(a: Int, b: Int): Boolean = a == b || b in getValue(a).ancestorIds() || a in getValue(b).ancestorIds()

    private fun SemanticsNode.ancestorIds(): List<Int> = generateSequence(parent) { it.parent }.map { it.id }.toList()

    private fun describe(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        return "#${node.id} ${text ?: node.config.getOrNull(SemanticsProperties.EditableText) ?: ""}"
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val INSTANCE = "fake-talkback"
        const val CONSOLE_LINE = "the main console"
        const val WINDOW_ROW = "hello from chat"
        const val ANNOUNCEMENT = "You are hungry."

        // Compose's switch for its tests (internal, so its setter's name carries the module's), and the extra it gives
        // a node's traversalBefore in for them
        const val FORCE_ENABLED_SETTER = "setAccessibilityForceEnabledForTesting"
        const val TRAVERSAL_BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"

        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
