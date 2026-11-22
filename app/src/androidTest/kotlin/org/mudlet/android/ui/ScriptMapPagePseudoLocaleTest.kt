package org.mudlet.android.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.After
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
import org.mudlet.android.protocol.Profile
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The controls over a docked mapper's page (ScriptMapTest), in the debug build's pseudo-locales, as PseudoLocaleTest
 * checks the screens: the room search's label and its results whole, and each of the map's buttons beside or under it
 * touched within 48 dp, on the screen and over the map, clear of the field.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ScriptMapPagePseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake).around(AppLocaleRule(locale))
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(compose)

    private lateinit var session: FakeEngine.Session

    private val map: SemanticsMatcher get() = hasContentDescription(compose.string(R.string.map_description))
    private val field: SemanticsMatcher get() = hasSetTextAction() and hasText(compose.string(R.string.app_map_search))

    @Before
    fun openTheMapPage() {
        compose.enableAccessibilityChecksOfWhatShows()
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.STACKED }
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
        // The starter UI's mapper: docked, taller than wide
        session.windows(listOf(FakeEngine.window("mapper", kind = "mapper", w = MAP_W, h = MAP_H).put("inDock", true)), full = true)
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(MAP_W, MAP_H, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        session.send(
            JSONObject().put("t", "map").put("png", Base64.getEncoder().encodeToString(out.toByteArray())).put("w", MAP_W).put("h", MAP_H).put("viewOnly", true),
        )
        activate(hasText(compose.string(R.string.map_title)))
        waitFor(map)
    }

    @After
    fun automaticLayout() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
    }

    @Test
    fun theSearchAndTheButtonsOverTheMapFit() {
        waitFor(field)
        assertFits(compose.string(R.string.app_map_search))
        val dp = compose.density.density
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val drawn = compose.onNode(map).fetchSemanticsNode().boundsInRoot
        val search = compose.onNode(field).fetchSemanticsNode().boundsInRoot
        for (label in listOf(R.string.map_zoom_out, R.string.map_zoom_in, R.string.map_edit)) {
            val touch = compose.onNode(button(label)).fetchSemanticsNode().touchBoundsInRoot
            val name = compose.string(label)
            assertTrue("$locale: $name is touched within $touch", touch.width >= 48 * dp - 1 && touch.height >= 48 * dp - 1)
            assertTrue("$locale: $name, touched within $touch, is off the screen $screen", touch.left >= screen.left - 1 && touch.right <= screen.right + 1)
            assertTrue("$locale: $name, touched within $touch, is not over the map $drawn", drawn.contains(touch.center))
            assertFalse("$locale: $name, touched within $touch, covers the search $search", touch.overlaps(search))
        }

        compose.onNode(field).performTextInput("hall")
        compose.onNode(field).performImeAction()
        val find = session.receive("findRooms")
        session.send(EngineMessages.rooms(find, "hall", listOf(Triple(3, "hallway", "Castle"))))
        val place = compose.activity.getString(R.string.app_map_result_place, "Castle", 3)
        waitFor(hasText(place))
        assertFits(compose.activity.resources.getQuantityString(R.plurals.app_map_results_count, 1, 1))
        assertFits(place)
        val close = compose.onNode(button(R.string.app_map_results_close)).fetchSemanticsNode().touchBoundsInRoot
        assertTrue("$locale: the results' close button, touched within $close, is off the screen $screen", close.left >= screen.left - 1 && close.right <= screen.right + 1)
    }

    /** Every layout of the text is whole, after the accessibility checks of the screen. */
    private fun assertFits(text: String) {
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { compose.onAllNodes(isRoot())[it].tryPerformAccessibilityChecks() }
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
    private fun button(@StringRes label: Int) = hasContentDescription(compose.string(label)) and hasClickAction()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        private const val INSTANCE = "fake-map-page-pseudo"
        private const val MAP_W = 120
        private const val MAP_H = 200

        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
