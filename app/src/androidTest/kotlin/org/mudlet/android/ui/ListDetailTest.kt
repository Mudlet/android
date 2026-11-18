package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.scripts.SCRIPT_LIBRARY_TAG
import org.mudlet.android.ui.settings.SETTINGS_HUB_TAG
import org.mudlet.android.ui.theme.MudletTheme

/**
 * On an expanded screen, a profile's editor opens beside the profile list, and Back closes it there, a back gesture
 * that is let go of keeping it open; when the screen narrows, the open editor becomes a screen of its own. A script
 * item opens beside the script library the same way, and another opened in its place leaves its change a draft. A
 * settings page beside the hub leaves it to the hub to say that a change waits for the next connection. The phone
 * the tests run on is made expanded by laying the app out at a smaller density, so these do not run the accessibility
 * checks: at that density, the touch targets are smaller in pixels than the checks allow.
 */
@RunWith(AndroidJUnit4::class)
class ListDetailTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private var scale = 1f
    private var narrow by mutableStateOf(false)
    private lateinit var session: FakeEngine.Session

    @Before
    fun expand() {
        val metrics = compose.activity.resources.displayMetrics
        // Wide enough to be expanded, from 840 dp, with room to spare
        scale = minOf(1f, metrics.widthPixels / metrics.density / EXPANDED_DP)
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                    // Narrowed as a window resized is: by its width, not its density. The outer Box loosens the
                    // window's exact width, which a width of its own would otherwise be held to
                    Box(Modifier.fillMaxSize()) {
                        Box(if (narrow) Modifier.fillMaxHeight().width(COMPACT_DP.dp) else Modifier.fillMaxSize()) {
                            MudletTheme { AppNavigation(onKillUi = {}) }
                        }
                    }
                }
            }
        }
        session = fake.engine.nextSession()
        session.profiles(PROFILES, session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == PROFILES }
    }

    @Test
    fun theEditorOpensBesideTheListAndBackClosesIt() {
        displayed(hasText(string(R.string.profiles_pick)))
        activate(hasContentDescription(string(R.string.profile_edit, GAME.name)))
        editorShown()
        // Beside the list, still there
        displayed(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))

        // A back gesture let go of before it is done leaves the editor open
        compose.runOnUiThread {
            val back = compose.activity.onBackPressedDispatcher
            back.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(100f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackCancelled()
        }
        editorShown()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        displayed(hasText(string(R.string.profiles_pick)))
        compose.onNode(hasSetTextAction() and hasText(string(R.string.profile_host))).assertDoesNotExist()
        displayed(hasText(string(R.string.profiles_title)))
    }

    @Test
    fun anEditorOpenBesideTheListBecomesAScreenOfItsOwnWhenTheScreenNarrows() {
        activate(hasContentDescription(string(R.string.profile_edit, GAME.name)))
        editorShown()

        compose.runOnUiThread { narrow = true }
        waitFor("the editor on a screen of its own, with Back", hasContentDescription(string(R.string.navigate_back)))
        compose.onNode(hasSetTextAction() and hasText(string(R.string.profile_host))).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.profiles_title))).assertDoesNotExist()

        // Back from the editor's own screen is the profile list, with no editor beside it
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        displayed(hasText(string(R.string.profiles_title)))
        compose.onNode(hasSetTextAction() and hasText(string(R.string.profile_host))).assertDoesNotExist()
    }

    @Test
    fun theAnswerToASaveClosesOnlyTheEditorThatSaved() {
        activate(hasContentDescription(string(R.string.profile_edit, GAME.name)))
        editorShown()
        activate(hasText(string(R.string.profile_save)))
        val save = session.receive("saveProfile")
        // The player moves on to another profile's editor before the engine answers
        activate(hasContentDescription(string(R.string.profile_edit, OTHER.name)))
        displayed(hasContentDescription(string(R.string.detail_close, OTHER.name)) and hasClickAction())

        session.profiles(PROFILES, save)
        compose.waitForIdle()
        displayed(hasContentDescription(string(R.string.detail_close, OTHER.name)) and hasClickAction())
        compose.onNode(hasText(string(R.string.profiles_pick))).assertDoesNotExist()
    }

    @Test
    fun aScriptItemOpensBesideTheLibraryWhichMarksItsDraft() {
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(PROFILES, session.receive("openProfile"))
        activate(hasContentDescription(string(R.string.scripts_open, GAME.name)))
        val triggers = JSONArray().put(scriptNode(1, "Greet")).put(scriptNode(2, "Wave"))
        // The library asks as it opens, and an item's page too, from their composition, which goes on while the test waits on it
        compose.waitForIdle()
        for (kind in listOf("trigger", "alias", "script", "timer", "key", "button")) {
            val ask = session.receive("scriptItems")
            session.send(JSONObject().put("t", "scriptItems").put("kind", kind).put("items", if (kind == "trigger") triggers else JSONArray()).put("re", ask.get("id")))
        }
        val inLibrary = hasAnyAncestor(hasTestTag(SCRIPT_LIBRARY_TAG))
        displayed(hasText(string(R.string.scripts_pick)))

        activate(hasText("Greet") and inLibrary)
        compose.waitForIdle()
        val open = session.receive("scriptItem")
        assertEquals(1, open.getInt("item"))
        val fields = scriptNode(1, "Greet").put("script", "").put("patterns", JSONArray()).put("command", "").put("lineDelta", -1).put("matchAll", false)
            .put("filter", false).put("fireLength", 0).put("sound", false).put("soundFile", "").put("highlight", false).put("highlightFg", "").put("highlightBg", "")
        session.send(JSONObject().put("t", "scriptItem").put("kind", "trigger").put("fields", fields).put("re", open.get("id")))
        val name = hasSetTextAction() and hasText(string(R.string.scripts_name))
        displayed(name)
        // Beside the library, still there
        displayed(hasText("Wave") and inLibrary)
        compose.onNode(hasText(string(R.string.scripts_pick))).assertDoesNotExist()

        // Another item takes its place with no question asked: the change stays a draft, which the library marks
        compose.onNode(name).performTextInput("!")
        activate(hasText("Wave") and inLibrary)
        compose.waitForIdle()
        assertEquals(2, session.receive("scriptItem").getInt("item"))
        displayed(hasText("Greet") and hasText(string(R.string.scripts_draft)) and inLibrary)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        displayed(hasText(string(R.string.scripts_pick)))
        displayed(hasText("Wave") and inLibrary)
    }

    @Test
    fun aSettingsPageBesideTheHubLeavesTheHubToSayThatAChangeWaitsForTheNextConnection() {
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(PROFILES, session.receive("openProfile"))
        session.status("profile", "${GAME.name} loaded")
        session.settings(mapOf("enableGMCP" to true), session.receive("getSettings").getLong("id"))
        session.status("connection", "connected")
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable && EngineClient.front.status["connection"] == "connected" } }
        compose.pickFromGameMenu(string(R.string.settings_title))
        waitFor("the settings hub", hasTestTag(SETTINGS_HUB_TAG))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_connection)))
        activate(hasText(string(R.string.settings_connection)))
        activate(hasText(string(R.string.settings_gmcp)))

        val notice = hasText(string(R.string.settings_next_connection))
        waitFor("that the change waits for the next connection", notice)
        compose.onAllNodes(notice).assertCountEquals(1)
    }

    private fun scriptNode(id: Int, name: String): JSONObject = JSONObject().put("id", id).put("parent", 0).put("name", name).put("folder", false)
        .put("enabled", true).put("active", true).put("package", "").put("error", "")

    /** The editor of [GAME], beside the list, with a Close button at its top in place of Back. */
    private fun editorShown() {
        displayed(hasSetTextAction() and hasText(string(R.string.profile_host)))
        displayed(hasContentDescription(compose.activity.getString(R.string.detail_close, GAME.name)) and hasClickAction())
        displayed(hasText(string(R.string.profiles_title)))
    }

    private fun displayed(node: SemanticsMatcher) {
        waitFor("$node", node)
        compose.onNode(node).assertIsDisplayed()
    }

    // A wait that runs out says what the screen held instead
    private fun waitFor(what: String, node: SemanticsMatcher) {
        try {
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(node).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val width = compose.onRoot().fetchSemanticsNode().size.width / (compose.activity.resources.displayMetrics.density * scale)
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrElse { "$it" }
            throw AssertionError("no $what within $WAIT_MS ms; the app $width dp wide at scale $scale; the tree:\n$tree", e)
        }
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun activate(label: SemanticsMatcher) {
        waitFor("$label", label and hasClickAction())
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        val OTHER = Profile("Other", "127.0.0.2", 4000, false, "", false)
        val PROFILES = listOf(GAME, OTHER)
        const val EXPANDED_DP = 960f
        const val COMPACT_DP = 400
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 120_000L
    }
}
