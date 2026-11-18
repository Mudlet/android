package org.mudlet.android.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.data.MapBackups
import org.mudlet.android.protocol.Profile
import java.io.File

/**
 * The full-screen map's search, its map file menu and the bar that tells how a map file went, in the debug build's
 * pseudo-locales, as PseudoLocaleTest checks the screens: none of their text is cut short.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class MapFilesPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake).around(AppLocaleRule(locale))
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(compose)

    private lateinit var session: FakeEngine.Session

    @Before
    fun openTheMap() {
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
        activate(hasContentDescription(compose.string(R.string.app_map_show)))
        waitFor(hasContentDescription(compose.string(R.string.map_file_menu)))
    }

    @Test
    fun theTextFits() {
        assertFits(compose.string(R.string.app_map_search))

        activate(hasContentDescription(compose.string(R.string.map_file_menu)))
        waitFor(hasText(compose.string(R.string.map_file_share)))
        for (id in listOf(R.string.map_file_load, R.string.map_file_backups, R.string.map_file_save, R.string.map_file_save_json, R.string.map_file_share)) {
            assertFits(compose.string(id))
        }

        val folder = compose.runOnIdle { MapBackups.folder(compose.activity.filesDir, EngineClient.front.name) }
        folder.deleteRecursively()
        activate(hasText(compose.string(R.string.map_file_backups)))
        waitFor(hasText(compose.string(R.string.map_backups_none)))
        for (id in listOf(R.string.map_backups_title, R.string.map_backups_none, R.string.map_backups_close)) {
            assertFits(compose.string(id))
        }
        activate(hasText(compose.string(R.string.map_backups_close)))
        folder.mkdirs()
        File(folder, "autosave.dat").writeText("dat")
        File(folder, "18-11-2026#10-20-30.dat").writeText("dat")
        File(folder, "18-11-2026#10-20-31.json").writeText("{}")
        File(folder, "18-11-2026#10-20-32.xml").writeText("<map/>")
        activate(hasContentDescription(compose.string(R.string.map_file_menu)))
        activate(hasText(compose.string(R.string.map_file_backups)))
        waitFor(hasText(compose.string(R.string.map_backups_xml)))
        for (id in listOf(R.string.map_backups_autosave, R.string.map_backups_dat, R.string.map_backups_json, R.string.map_backups_xml)) {
            assertFits(compose.string(id))
        }
        activate(hasText(compose.string(R.string.map_backups_close)))
        folder.deleteRecursively()

        // The picker is Android's own, so the file comes straight to the client
        val picked = File(compose.activity.cacheDir, "picked.json").apply { writeText("{}") }
        compose.runOnIdle { EngineClient.front.appMap.files.load(Uri.fromFile(picked)) }
        session.receive("loadMapFile")
        session.send(JSONObject().put("t", "mapFile").put("op", "load").put("state", "running").put("cancelable", true))
        waitFor(hasText(compose.string(R.string.map_file_cancel)))
        assertFits(compose.string(R.string.map_file_loading))
        assertFits(compose.string(R.string.map_file_cancel))
        session.send(JSONObject().put("t", "mapFile").put("op", "load").put("state", "failed").put("error", REASON))
        val failed = compose.activity.getString(R.string.map_file_load_failed, REASON)
        waitFor(hasText(failed))
        assertFits(failed)
    }

    /** Every layout of the text is whole, after the accessibility checks of each window, as a menu is one of its own. */
    private fun assertFits(text: String) {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots.fetchSemanticsNodes().indices.forEach { roots[it].tryPerformAccessibilityChecks() }
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
        private const val INSTANCE = "fake-map-files-pseudo"
        private const val REASON = "Could not load map from map.json."
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
