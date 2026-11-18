package org.mudlet.android.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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
import org.mudlet.android.ui.EngineMessages.mapView
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The panel beside a script's small mapper (ScriptMapCornerTest), as narrow as four buttons, in the debug build's
 * pseudo-locales, as PseudoLocaleTest checks the screens: none of its text is cut short.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class MapCornerPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake).around(AppLocaleRule(locale))
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(compose)

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
        waitFor(hasContentDescription(compose.string(R.string.map_follow)) and hasClickAction())
    }

    @After
    fun automaticLayout() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
    }

    @Test
    fun theTextFits() {
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(compose.activity.getString(R.string.map_area, AREA))
        assertFits(compose.activity.getString(R.string.map_level, 0))
    }

    /** Every layout of the text is whole, once the panel has scrolled to it. */
    private fun assertFits(text: String) {
        compose.onNode(hasText(text)).performScrollTo()
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

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        waitFor(label)
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        private const val INSTANCE = "fake-map-corner-pseudo"
        private const val MAP_W = 100
        private const val MAP_H = 100

        // As EngineMessages.mapView names it
        private const val AREA = "Castle"

        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
