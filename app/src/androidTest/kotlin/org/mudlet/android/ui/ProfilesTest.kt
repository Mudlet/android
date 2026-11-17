package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.profiles.DefaultGame
import org.mudlet.android.ui.profiles.DefaultGames

/**
 * The profile list and editor against a fake engine, as a TalkBack user drives them: each control found by the label
 * TalkBack reads and activated through its accessibility action. The engine's side of the profile requests is
 * checked by the host tests (native/tests/profile_checks.py).
 */
@RunWith(AndroidJUnit4::class)
class ProfilesTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var engine: FakeEngine.Session
    private lateinit var passwords: PasswordStore

    @Before
    fun attach() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        passwords = PasswordStore(compose.activity)
        forgetPasswords()
        engine = fake.engine.nextSession()
    }

    @After
    fun forgetPasswords() {
        (listOf(ALPHA, BETA, GAMMA).map { it.name } + listOf(ACHAEA, STICKMUD)).forEach {
            passwords.forget(it)
            Settings.setAwaitsDefaults(it, false)
        }
    }

    @Test
    fun listsTheProfilesInTheEnginesOrderEachReadAsNameAndAddress() {
        listed(BETA, ALPHA)
        val beta = compose.onNodeWithContentDescription("Beta, b.example:4000").assertIsDisplayed().fetchSemanticsNode()
        val alpha = compose.onNodeWithContentDescription("Alpha, a.example:23").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("the last used first", beta.boundsInRoot.top < alpha.boundsInRoot.top)
        for (name in listOf("Alpha", "Beta")) {
            compose.onNode(hasContentDescription(string(R.string.profile_connect_to, name)) and hasClickAction()).assertIsDisplayed()
            compose.onNode(hasContentDescription(string(R.string.profile_edit, name)) and hasClickAction()).assertIsDisplayed()
        }
        compose.onNode(hasContentDescription(string(R.string.profiles_add)) and hasClickAction()).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun addsAProfileAndStoresItsPassword() {
        listed()
        compose.onNodeWithText(string(R.string.profiles_empty)).assertIsDisplayed()
        activate(hasContentDescription(string(R.string.profiles_add)))
        compose.onNodeWithText(string(R.string.profile_new_title)).assertIsDisplayed()

        type(R.string.profile_name, "Gamma")
        type(R.string.profile_host, "g.example")
        type(R.string.profile_port, "65536")
        compose.onNodeWithText(string(R.string.profile_port_invalid)).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.profile_save)) and hasClickAction()).assertIsNotEnabled()
        type(R.string.profile_port, "4242")
        compose.onNodeWithText(string(R.string.profile_port_invalid)).assertDoesNotExist()
        activate(hasText(string(R.string.profile_tls)))
        type(R.string.profile_login, "hero")
        type(R.string.profile_password, "gamma secret")
        activate(hasText(string(R.string.profile_auto_reconnect)))
        // A password field, which TalkBack does not read aloud
        field(R.string.profile_password).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onRoot().tryPerformAccessibilityChecks()
        activate(hasText(string(R.string.profile_save)))

        val save = engine.receive("saveProfile")
        assertEquals(GAMMA, profileOf(save))
        assertFalse("the password goes to the engine only with openProfile", save.has("password"))
        engine.profiles(listOf(GAMMA), save)
        waitFor(hasContentDescription("Gamma, g.example:4242"))
        compose.runOnIdle {
            assertEquals("gamma secret", passwords.get("Gamma"))
            assertTrue("a game added here is given the app's settings as it first loads", Settings.awaitsDefaults("Gamma"))
        }
        // Only a game that is open is given its password: for one that is not, the request would open it
        engine.assertNoRequest(setOf("openProfile"), 500)
    }

    @Test
    fun editsAProfileKeepingItsNameAndStoredPassword() {
        assertTrue(passwords.set("Alpha", "alpha secret"))
        listed(ALPHA)
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        compose.onNodeWithText(string(R.string.profile_password_stored)).assertIsDisplayed()
        assertEquals("no name field", 0, compose.onAllNodes(hasSetTextAction() and hasText(string(R.string.profile_name))).fetchSemanticsNodes().size)
        field(R.string.profile_host).assert(hasText("a.example"))
        compose.onRoot().tryPerformAccessibilityChecks()

        type(R.string.profile_port, "2323")
        activate(hasText(string(R.string.profile_save)))
        val save = engine.receive("saveProfile")
        assertEquals(ALPHA.copy(port = 2323), profileOf(save))
        engine.profiles(listOf(ALPHA.copy(port = 2323)), save)
        waitFor(hasContentDescription("Alpha, a.example:2323"))
        compose.runOnIdle {
            assertEquals("an empty password field keeps the stored one", "alpha secret", passwords.get("Alpha"))
            assertFalse("a game edited keeps its own settings", Settings.awaitsDefaults("Alpha"))
        }
    }

    @Test
    fun forgetsTheStoredPassword() {
        assertTrue(passwords.set("Alpha", "alpha secret"))
        listed(ALPHA)
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        activate(hasText(string(R.string.profile_password_forget)))
        compose.onNodeWithText(string(R.string.profile_password_stored)).assertDoesNotExist()
        compose.runOnIdle { assertFalse(passwords.has("Alpha")) }
    }

    @Test
    fun givesAnOpenGameThePasswordSavedOrForgottenForIt() {
        listed(ALPHA)
        fake.engine.openAlongside("Alpha")
        engine.openProfiles()
        compose.waitUntil(10_000) { EngineClient.sessions.size == 1 }

        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        type(R.string.profile_password, "alpha secret")
        activate(hasText(string(R.string.profile_save)))
        engine.profiles(listOf(ALPHA), engine.receive("saveProfile"))
        // The engine has a profile's password only from an openProfile, and the game's Reconnect sends none
        val given = engine.receive("openProfile")
        assertEquals("Alpha", given.getString("name"))
        assertEquals("alpha secret", given.getString("password"))
        assertFalse("the game is not connected for it", given.getBoolean("connect"))
        assertTrue("nor the other games closed", given.getBoolean("alongside"))

        waitFor(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        activate(hasText(string(R.string.profile_password_forget)))
        val withdrawn = engine.receive("openProfile")
        assertEquals("Alpha", withdrawn.getString("name"))
        assertFalse("the game is not left with a password the player had the app forget", withdrawn.has("password"))
        assertFalse(withdrawn.getBoolean("connect"))
        assertTrue(withdrawn.getBoolean("alongside"))
    }

    @Test
    fun givesAGameTheEngineHoldsNoPasswordForItsStoredOneOnce() {
        assertTrue(passwords.set("Alpha", "alpha secret"))
        listed(ALPHA, BETA)
        // As an engine started after the last was killed brings its games back: it kept no password
        fake.engine.openAlongside("Alpha")
        fake.engine.openAlongside("Beta")
        fake.engine.passwordGiven("Alpha", false)
        fake.engine.passwordGiven("Beta", false)
        engine.openProfiles()
        val given = engine.receive("openProfile")
        assertEquals("Alpha", given.getString("name"))
        assertEquals("alpha secret", given.getString("password"))
        assertFalse("the game is not connected for it", given.getBoolean("connect"))
        assertTrue("nor the other games closed", given.getBoolean("alongside"))

        // Not again while the engine has yet to say it holds one, and nothing for Beta, which has none stored
        engine.openProfiles()
        engine.assertNoRequest("openProfile", 1000)

        fake.engine.passwordGiven("Alpha", true)
        engine.openProfiles()
        engine.assertNoRequest("openProfile", 1000)

        // Lost again, as by the next engine, it is given again
        fake.engine.passwordGiven("Alpha", false)
        engine.openProfiles()
        assertEquals("alpha secret", engine.receive("openProfile").getString("password"))
    }

    @Test
    fun deletesOnlyOnceConfirmed() {
        assertTrue(passwords.set("Alpha", "alpha secret"))
        compose.runOnIdle { Settings.setAwaitsDefaults("Alpha", true) }
        listed(ALPHA, BETA)
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))

        activate(hasText(string(R.string.profile_delete)))
        compose.onNodeWithText(string(R.string.profile_delete_title, "Alpha")).assertIsDisplayed()
        activate(hasText(string(R.string.profile_delete_cancel)) and hasAnyAncestor(isDialog()))
        compose.onNodeWithText(string(R.string.profile_delete_title, "Alpha")).assertDoesNotExist()
        engine.assertNoRequest("deleteProfile", 1000)

        activate(hasText(string(R.string.profile_delete)))
        activate(hasText(string(R.string.profile_delete)) and hasAnyAncestor(isDialog()))
        val delete = engine.receive("deleteProfile")
        assertEquals("Alpha", delete.getString("name"))
        engine.profiles(listOf(BETA), delete)
        waitFor(hasContentDescription("Beta, b.example:4000"))
        compose.onNodeWithText(string(R.string.profiles_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Alpha, a.example:23").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse("its password goes with it", passwords.has("Alpha"))
            assertFalse("a new game of its name will be given the settings when added", Settings.awaitsDefaults("Alpha"))
        }
    }

    @Test
    fun showsTheEnginesRefusalAndStaysInTheEditor() {
        listed(ALPHA)
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        activate(hasText(string(R.string.profile_delete)))
        activate(hasText(string(R.string.profile_delete)) and hasAnyAncestor(isDialog()))
        engine.refuse(engine.receive("deleteProfile"), "\"Alpha\" is running")
        waitFor(hasText("deleteProfile: \"Alpha\" is running"))
        compose.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun opensAProfileWithItsStoredPasswordAndGoesToTheGame() {
        assertTrue(passwords.set("Alpha", "alpha secret"))
        listed(ALPHA, BETA)

        // Refused: the reason shows, and the list stays
        activate(hasContentDescription(string(R.string.profile_connect_to, "Beta")))
        val beta = engine.receive("openProfile")
        assertEquals("Beta", beta.getString("name"))
        assertFalse("Beta has no stored password", beta.has("password"))
        engine.refuse(beta, "there is no profile named \"Beta\"")
        waitFor(hasText("openProfile: there is no profile named \"Beta\""))
        compose.onNodeWithText(string(R.string.profiles_title)).assertIsDisplayed()

        activate(hasContentDescription(string(R.string.profile_connect_to, "Alpha")))
        val alpha = engine.receive("openProfile")
        assertEquals("Alpha", alpha.getString("name"))
        assertEquals("alpha secret", alpha.getString("password"))
        assertTrue(alpha.getBoolean("connect"))
        engine.profiles(listOf(ALPHA, BETA), alpha)
        waitFor(hasText(string(R.string.game_title)))
    }

    @Test
    fun anAnswerThatComesOnceThePlayerHasMovedOnLeavesThemWhereTheyAre() {
        listed(ALPHA, BETA)
        activate(hasContentDescription(string(R.string.profile_connect_to, "Alpha")))
        val open = engine.receive("openProfile")
        activate(hasContentDescription(string(R.string.profile_edit, "Beta")))
        compose.onNodeWithText("Beta").assertIsDisplayed()

        // The same message that lists these answers the openProfile
        engine.profiles(listOf(BETA, ALPHA), open)
        compose.waitUntil(10_000) { EngineClient.profiles == listOf(BETA, ALPHA) }
        compose.onNodeWithText(string(R.string.game_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.profile_save)).assertIsDisplayed()
    }

    @Test
    fun gamesTheEngineTellsOfOnceTheProfilesShowAreAtTheTopOfTheList() {
        // More than the screen holds, so the list can scroll the games out of view
        val many = (1..10).map { Profile("Profile $it", "p$it.example", 23, false, "", false) }
        listed(*many.toTypedArray())
        waitFor(hasContentDescription("Profile 1, p1.example:23"))
        fake.engine.openAlongside(many[0].name)
        engine.openProfiles()
        compose.waitUntil(10_000) { EngineClient.sessions.size == 1 }
        compose.onNodeWithText(string(R.string.games_title)).assertIsDisplayed()
    }

    @Test
    fun offersMudletsGamesBelowTheProfilesAndAddsOneAtATap() {
        listed(ALPHA)
        val achaea = game(ACHAEA)
        waitFor(hasText(string(R.string.default_games_title)))
        val header = compose.onNodeWithText(string(R.string.default_games_title)).fetchSemanticsNode()
        val alpha = compose.onNodeWithContentDescription("Alpha, a.example:23").fetchSemanticsNode()
        assertTrue("below the player's own", alpha.boundsInRoot.top < header.boundsInRoot.top)
        val row = gameRow(achaea)
        scrollToGame(row)
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNode(hasContentDescription(string(R.string.default_game_info, ACHAEA)) and hasClickAction()).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(row)
        val save = engine.receive("saveProfile")
        assertEquals(Profile(ACHAEA, "achaea.com", 23, false, "", false), profileOf(save))
        engine.profiles(listOf(ALPHA, achaea.newProfile()), save)
        val open = engine.receive("openProfile")
        assertEquals(ACHAEA, open.getString("name"))
        assertTrue(open.getBoolean("connect"))
        compose.runOnIdle { assertTrue("Mudlet's game is given the app's settings as it first loads", Settings.awaitsDefaults(ACHAEA)) }
        engine.profiles(listOf(achaea.newProfile(), ALPHA), open)
        waitFor(hasText(string(R.string.game_title)))
    }

    @Test
    fun leavesOutAGameThePlayerHasAProfileAt() {
        listed(Profile("Mine", " ACHAEA.COM", 23, false, "", false))
        // Aardwolf is listed before Achaea and Aetolia after, so Achaea would be laid out between them
        scrollToGame(gameRow(game("Aardwolf")))
        scrollToGame(gameRow(game("Aetolia")))
        compose.onNode(gameRow(game(ACHAEA))).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.default_game_info, ACHAEA)).assertDoesNotExist()
    }

    @Test
    fun tellsOfAGameAndShowsTheEnginesRefusalToAddIt() {
        listed()
        val stick = game(STICKMUD)
        val info = hasContentDescription(string(R.string.default_game_info, STICKMUD)) and hasClickAction()
        scrollToGame(info)
        activate(info)
        compose.onNode(hasText(STICKMUD) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.default_game_address_secure, stick.host, stick.port)).assertIsDisplayed()
        compose.onNodeWithText(stick.description).assertIsDisplayed()
        for (link in stick.links) compose.onNode(hasText(link.label) and hasClickAction() and hasAnyAncestor(isDialog())).assertExists()
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(hasText(string(R.string.profile_connect)) and hasAnyAncestor(isDialog()))
        val save = engine.receive("saveProfile")
        assertEquals(Profile(STICKMUD, stick.host, stick.port, true, "", false), profileOf(save))
        compose.onNode(isDialog()).assertDoesNotExist()
        engine.refuse(save, "the disk is full")
        waitFor(hasText("saveProfile: the disk is full"))
        compose.onNodeWithText("saveProfile: the disk is full").assertIsDisplayed()
        engine.assertNoRequest("openProfile", 1000)
    }

    private fun game(name: String) = compose.activity.assets.open(DefaultGames.FILE).bufferedReader().use { DefaultGames.parse(it.readText()) }
        .single { it.name == name }

    private fun gameRow(game: DefaultGame) = hasContentDescription(string(R.string.default_game_description, game.name, game.summary.ifEmpty { string(R.string.profile_address, game.host, game.port) })) and hasClickAction()

    // The list is lazy: a row far down is not laid out, so not found, until scrolled to
    private fun scrollToGame(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        compose.onNode(matcher).assertIsDisplayed()
    }

    /** The engine answers the UI's listProfiles, sent when it attached, with [profiles]. */
    private fun listed(vararg profiles: Profile) {
        engine.profiles(profiles.toList(), engine.receive("listProfiles"))
        compose.waitUntil(10_000) { EngineClient.profiles == profiles.toList() }
    }

    // The engine's answers come over the socket, which the test rule's idling does not wait for
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun profileOf(request: JSONObject) = Profile(
        request.getString("name"),
        request.getString("host"),
        request.getInt("port"),
        request.getBoolean("tls"),
        request.getString("login"),
        request.getBoolean("autoReconnect"),
    )

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun field(@StringRes label: Int) = compose.onNode(hasSetTextAction() and hasText(string(label)))

    // Through the field's accessibility action, as TalkBack sets text: no keyboard comes up to cover the editor
    private fun type(@StringRes label: Int, text: String) {
        scrollIntoView(hasSetTextAction() and hasText(string(label)))
        field(label).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
    }

    /** What a double tap does under TalkBack: the node's accessibility click. */
    private fun activate(label: SemanticsMatcher) {
        val control = label and hasClickAction()
        scrollIntoView(control)
        compose.onNode(control).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    // As TalkBack does when it moves to a control below the edge of a small screen
    private fun scrollIntoView(matcher: SemanticsMatcher) {
        if (compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(matcher).performScrollTo()
        }
    }

    private companion object {
        val ALPHA = Profile("Alpha", "a.example", 23, false, "", false)
        val BETA = Profile("Beta", "b.example", 4000, true, "", true)
        val GAMMA = Profile("Gamma", "g.example", 4242, true, "hero", true)

        // Two of Mudlet's games (app/src/main/assets/games), StickMUD's connection secure
        const val ACHAEA = "Achaea"
        const val STICKMUD = "StickMUD"

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
