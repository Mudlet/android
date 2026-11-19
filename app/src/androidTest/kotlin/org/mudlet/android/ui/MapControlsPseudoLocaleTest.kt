package org.mudlet.android.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.filters.SdkSuppress
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
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.MapCorner
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineMessages.mapView
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The button over a script's small mapper that shows its panel (ScriptMapCornerTest), in the debug build's
 * pseudo-locales, as PseudoLocaleTest checks the screens: at the map's top corner on the screen's side, the right in
 * en-XA and the left in ar-XB, and the panel it shows little wider than the map, each of its buttons on the screen and
 * off the map, until the button puts it away again.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class MapControlsPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake).around(AppLocaleRule(locale))
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(compose)

    private val map: SemanticsMatcher get() = hasContentDescription(compose.string(R.string.map_description))

    @Before
    fun showTheMap() {
        compose.enableAccessibilityChecksOfWhatShows()
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
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.STACKED }
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
        // A few dp across at the top right, drawn larger with its panel under it; off the player, on a level of two
        session.windows(listOf(FakeEngine.window("mapper", kind = "mapper", x = 700, y = 0, w = MAP_W, h = MAP_H)), full = true)
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(MAP_W, MAP_H, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        session.send(
            JSONObject().put("t", "map").put("png", Base64.getEncoder().encodeToString(out.toByteArray())).put("w", MAP_W).put("h", MAP_H)
                .put("viewOnly", true).put("view", mapView(MAP_W, MAP_H, following = false, levels = listOf(0, 1))),
        )
        waitFor(map)
    }

    @After
    fun automaticLayout() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
    }

    @Test
    fun theButtonIsAtTheMapsOuterTopCornerAndThePanelItShowsFits() {
        val show = button(R.string.map_controls_show)
        waitFor(show)
        compose.onRoot().tryPerformAccessibilityChecks()
        val dp = compose.density.density
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val drawn = compose.onNode(map).fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNode(show).fetchSemanticsNode().touchBoundsInRoot
        assertTrue("$locale: the button is touched within $toggle", toggle.width >= 48 * dp - 1 && toggle.height >= 48 * dp - 1)
        assertEquals("$locale: the button $toggle is not at the top of the map $drawn", drawn.top, toggle.top, 1f)
        if (locale == "ar-XB") {
            assertTrue("$locale: the map $drawn is not at the screen's left", drawn.center.x < screen.center.x)
            assertEquals("$locale: the button $toggle is not at the left of the map $drawn", drawn.left, toggle.left, 1f)
        } else {
            assertTrue("$locale: the map $drawn is not at the screen's right", drawn.center.x > screen.center.x)
            assertEquals("$locale: the button $toggle is not at the right of the map $drawn", drawn.right, toggle.right, 1f)
        }

        activate(show)
        val hide = button(R.string.map_controls_hide)
        waitFor(hide)
        compose.onRoot().tryPerformAccessibilityChecks()
        val panel = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(button(R.string.map_follow))).fetchSemanticsNodes().minBy { it.size.width * it.size.height }.boundsInRoot
        assertTrue("$locale: the panel $panel is wider than the map $drawn", panel.width <= maxOf(drawn.width, MapCorner.PANEL_WIDTH_DP * dp) + 1)
        for (label in listOf(R.string.map_zoom_out, R.string.map_zoom_in, R.string.map_edit, R.string.map_follow, R.string.map_level_down, R.string.map_level_up)) {
            compose.onNode(button(label)).performScrollTo()
            val touch = compose.onNode(button(label)).fetchSemanticsNode().touchBoundsInRoot
            assertTrue("$locale: ${compose.string(label)}, touched within $touch, is off the screen $screen", touch.left >= screen.left - 1 && touch.right <= screen.right + 1)
            assertFalse("$locale: ${compose.string(label)}, touched within $touch, is over the map $drawn", touch.overlaps(drawn))
        }

        activate(hide)
        compose.waitForIdle()
        assertTrue("$locale: the panel still shows once put away", compose.onAllNodes(button(R.string.map_zoom_in)).fetchSemanticsNodes().isEmpty())
        compose.onNode(show).assertExists()
    }

    // In the merged tree, where a button's click and its icon's label are on one node
    private fun button(@StringRes label: Int) = hasContentDescription(compose.string(label)) and hasClickAction()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        waitFor(label)
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        private const val INSTANCE = "fake-map-controls-pseudo"
        private const val MAP_W = 100
        private const val MAP_H = 100

        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
