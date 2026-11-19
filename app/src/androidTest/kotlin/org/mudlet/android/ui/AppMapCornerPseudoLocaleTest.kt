package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineMessages.appMap
import org.mudlet.android.ui.EngineMessages.mapView

/**
 * The app's map in the console's corner (AppMapCornerTest) in the debug build's pseudo-locales, as PseudoLocaleTest
 * checks the screens: at the console's top end, the right in en-XA and the left in ar-XB, and none of the text of its
 * panel or of the message that it is hidden cut short.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class AppMapCornerPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake).around(AppLocaleRule(locale))
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(compose)

    private val corner: SemanticsMatcher get() = hasContentDescription(compose.activity.getString(R.string.app_map_corner_room, AREA, ROOM)) and hasClickAction()

    @Before
    fun showTheMap() {
        compose.enableAccessibilityChecksOfWhatShows()
        compose.runOnIdle {
            Settings.mapBehindText.reset()
            Settings.mapInCorner.reset()
        }
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        val session = fake.engine.nextSession()
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
        // Off the player, so the button that centres the map on them shows too
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H, following = false)))
        waitFor(corner)
    }

    @After
    fun defaults() {
        compose.runOnIdle {
            ScriptWindowsClient.layoutMode = LayoutMode.AUTO
            Settings.mapInCorner.reset()
        }
    }

    @Test
    fun theMapIsAtTheConsolesTopEndAndTheTextFits() {
        compose.onRoot().tryPerformAccessibilityChecks()
        val console = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        val drawn = compose.onNode(corner).fetchSemanticsNode().boundsInRoot
        assertEquals("$locale: the map $drawn is at the top of $console", console.top, drawn.top, 1f)
        if (locale == "ar-XB") {
            assertEquals("$locale: the map $drawn is at the left of $console", console.left, drawn.left, 1f)
        } else {
            assertEquals("$locale: the map $drawn is at the right of $console", console.right, drawn.right, 1f)
        }
        activate(hasContentDescription(compose.activity.getString(R.string.map_controls_show)))
        waitFor(hasText(compose.activity.getString(R.string.map_area, AREA)))
        assertFits(compose.activity.getString(R.string.map_area, AREA))

        activate(hasContentDescription(compose.activity.getString(R.string.app_map_corner_hide)))
        val hidden = compose.activity.getString(R.string.app_map_corner_hidden, compose.activity.getString(R.string.settings_map_in_corner))
        waitFor(hasText(hidden))
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(hidden, scrolls = false)
        assertFits(compose.activity.getString(R.string.app_map_corner_undo), scrolls = false)
    }

    /** Every layout of the text is whole, once what holds it, if it [scrolls], has scrolled to it. */
    private fun assertFits(text: String, scrolls: Boolean = true) {
        if (scrolls) compose.onNode(hasText(text)).performScrollTo()
        val layouts = ArrayList<TextLayoutResult>()
        for (node in compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()) {
            if (node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()) continue
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        }
        assertTrue("$locale: no layout for \"$text\"", layouts.isNotEmpty())
        for (layout in layouts) {
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
        }
    }

    // In the merged tree, where a button's click and its icon's label are on one node
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        waitFor(label)
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        private const val INSTANCE = "fake-app-map-corner-pseudo"
        private const val FRAME_W = 400
        private const val FRAME_H = 700

        // As EngineMessages.mapView names them
        private const val AREA = "Castle"
        private const val ROOM = "Room 1"

        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
