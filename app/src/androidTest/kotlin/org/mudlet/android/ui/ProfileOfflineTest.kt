package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * The editor's "Open offline", desktop's Offline: the game opens without connecting, with its stored password for when
 * the player connects it; beside the games open, and one open already only comes to the front.
 */
@RunWith(AndroidJUnit4::class)
class ProfileOfflineTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(GameLinkTest.TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var engine: FakeEngine.Session
    private lateinit var passwords: PasswordStore

    @Before
    fun attach() {
        compose.enableAccessibilityChecksOfWhatShows()
        passwords = PasswordStore(compose.activity)
        forget()
        engine = fake.engine.nextSession()
    }

    @After
    fun forget() {
        passwords.forget(ALPHA.name)
    }

    @Test
    fun opensTheGameWithoutConnectingWithItsStoredPassword() {
        assertTrue(passwords.set(ALPHA.name, "alpha secret"))
        listed(ALPHA, BETA)
        openOffline(ALPHA.name)
        val open = engine.receive("openProfile")
        assertEquals(ALPHA.name, open.getString("name"))
        assertFalse("opened offline", open.getBoolean("connect"))
        assertFalse(open.optBoolean("alongside"))
        assertEquals("alpha secret", open.getString("password"))
        engine.profiles(listOf(ALPHA, BETA), open)
        waitFor(hasTestTag(GAME_TITLE_TAG))
        engine.assertNoRequest("connect", GameLinkTest.QUIET_MS)
    }

    @Test
    fun withAGameOpenItOpensBesideItInFront() {
        listed(ALPHA, BETA)
        fake.engine.openAlongside(BETA.name)
        engine.openProfiles()
        compose.waitUntil(10_000) { EngineClient.sessions.size == 1 }
        openOffline(ALPHA.name)
        val open = engine.receive("openProfile")
        assertFalse(open.getBoolean("connect"))
        assertTrue("beside the game open, which stays", open.getBoolean("alongside"))
        engine.profiles(listOf(ALPHA, BETA), open)
        assertEquals(ALPHA.name, engine.receive("setFront").getString("name"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
    }

    @Test
    fun aGameOpenAlreadyOnlyComesToTheFront() {
        listed(ALPHA, BETA)
        fake.engine.openAlongside(ALPHA.name)
        engine.openProfiles()
        val alpha = engine.forProfile(ALPHA.name)
        compose.waitUntil(10_000) { EngineClient.sessions.size == 1 }
        openOffline(ALPHA.name)
        assertEquals(ALPHA.name, engine.receive("setFront").getString("name"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
        engine.assertNoRequest("openProfile", GameLinkTest.QUIET_MS)
        alpha.assertNoRequest(setOf("connect", "disconnect"), GameLinkTest.QUIET_MS)
    }

    @Test
    fun whatWasTypedIsSavedBeforeItOpens() {
        listed(ALPHA, BETA)
        activate(hasContentDescription(text(R.string.profile_edit, ALPHA.name)))
        compose.onNode(hasSetTextAction() and hasText(text(R.string.profile_host))).performTextReplacement("a2.example")
        // As a player does before going down to the button. Left up, it can end where an empty field begins, and
        // Compose hands accessibility a sliver of the field without its label: see ScrolledOutOfSight
        hideKeyboard()
        activate(hasText(text(R.string.profile_manage_section)))
        activate(hasText(text(R.string.profile_open_offline)))
        val save = engine.receive("saveProfile")
        val edited = ALPHA.copy(host = "a2.example")
        assertEquals(edited, GameLinkTest.profileOf(save))
        engine.assertNoRequest("openProfile", GameLinkTest.QUIET_MS)
        engine.profiles(listOf(edited, BETA), save)
        val open = engine.receive("openProfile")
        assertFalse(open.getBoolean("connect"))
        engine.profiles(listOf(edited, BETA), open)
        waitFor(hasTestTag(GAME_TITLE_TAG))
    }

    @Test
    fun aRefusalIsSaidInTheEditor() {
        listed(ALPHA, BETA)
        openOffline(ALPHA.name)
        engine.refuse(engine.receive("openProfile"), "at most 4 open")
        waitFor(hasText("openProfile: at most 4 open"))
        compose.onNode(hasTestTag(GAME_TITLE_TAG)).assertDoesNotExist()
        compose.onNode(hasText(text(R.string.profile_open_offline))).assertIsEnabled()
    }

    private fun openOffline(name: String) {
        activate(hasContentDescription(text(R.string.profile_edit, name)))
        activate(hasText(text(R.string.profile_manage_section)))
        activate(hasText(text(R.string.profile_open_offline)))
    }

    private fun hideKeyboard() {
        val window = compose.activity.window
        compose.runOnUiThread { WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        // Older versions only estimate the keyboard's insets, and run no accessibility checks to need it gone
        if (Build.VERSION.SDK_INT >= 30) {
            compose.waitUntil(10_000) {
                compose.runOnUiThread { ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 } == 0
            }
        }
    }

    private fun listed(vararg profiles: Profile) {
        engine.profiles(profiles.toList(), engine.receive("listProfiles"))
        compose.waitUntil(10_000) { EngineClient.profiles == profiles.toList() }
    }

    private fun text(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) = GameLinkTest.activate(compose, label)

    companion object {
        private val ALPHA = GameLinkTest.ALPHA
        private val BETA = Profile("Beta", "b.example", 4000, false, "", false)
    }
}
