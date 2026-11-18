package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.ConsoleZoom
import org.mudlet.android.protocol.Profile

/**
 * Desktop Mudlet's "Clear stored media" on a game's Security page against a fake engine: a button row that runs
 * Mudlet's purgeMediaCache() in the game and has it say so as desktop's button does, also where search finds it, and
 * not there for "New games", which have no media to clear.
 */
@RunWith(AndroidJUnit4::class)
class ClearMediaTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val row get() = hasText(string(R.string.settings_clear_media)) and hasClickAction()

    @Before
    fun openTheGameScreen() {
        compose.enableAccessibilityChecksOfWhatShows()
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.game_title))).fetchSemanticsNodes().isNotEmpty() }
        // As the engine does as the profile loads: EngineClient outlives a test, so an earlier one's would be there
        session.status("profile", "${GAME.name} loaded")
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable } }
    }

    @Test
    fun theGamesSecurityPageClearsItsStoredMedia_asDesktopsButtonDoes() {
        activate(hasContentDescription(string(R.string.settings_title)))
        val page = hasText(string(R.string.settings_security)) and hasClickAction()
        compose.scrollSettingsHubTo(page)
        activate(page)
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        waitFor(row)
        compose.onNode(row).performScrollTo().assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
        val node = compose.onNode(row).fetchSemanticsNode()
        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals("TalkBack says what a double tap does", string(R.string.settings_clear_media_button), node.config.getOrNull(SemanticsActions.OnClick)?.label)

        activate(row)
        assertClearsTheMedia(session.receive("runLua").getString("code"))
    }

    @Test
    fun searchFindsItForTheOpenGameAlone_andItWorksThere() {
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasContentDescription(string(R.string.settings_title)))
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("cache")
        waitFor(row)
        assertEquals("the open game's alone, as new games have no media", 1, compose.onAllNodes(row).fetchSemanticsNodes().size)

        activate(row)
        assertClearsTheMedia(session.receive("runLua").getString("code"))
    }

    private fun assertClearsTheMedia(lua: String) {
        assertTrue(lua, "purgeMediaCache()" in lua)
        assertTrue(lua, ConsoleZoom.luaString(string(R.string.settings_clear_media_done).substringAfter("]  - ")) in lua)
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private companion object {
        val GAME = Profile("Media", "127.0.0.1", 4000, false, "", false)
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
