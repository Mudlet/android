package org.mudlet.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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

/**
 * A telnet:// link from another app, sent as a browser sends one (an ACTION_VIEW Android resolves through the
 * manifest's filter): the profile at its address connects only once the player has said yes to a question that names
 * the address, an unknown address opens the new game's editor filled in, and nothing changes a saved profile.
 */
@RunWith(AndroidJUnit4::class)
class GameLinkTest {
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
        compose.enableAccessibilityChecksOfWhatShows()
        passwords = PasswordStore(compose.activity)
        forget()
        engine = fake.engine.nextSession()
    }

    @After
    fun forget() {
        for (name in listOf(ALPHA.name, "New", "A", ACHAEA)) {
            passwords.forget(name)
            Settings.setAwaitsDefaults(name, false)
        }
    }

    @Test
    fun aLinkToAProfilesAddressAsksNamingItThenConnectsWithTheStoredPassword() {
        assertTrue(passwords.set(ALPHA.name, "alpha secret"))
        listed(ALPHA)
        openLink("telnet://visitor:link-password@A.EXAMPLE:23/")
        val question = text(R.string.game_link_question_profile, "a.example:23", ALPHA.name)
        waitFor(hasText(question))
        compose.onNode(isDialog()).tryPerformAccessibilityChecks()
        engine.assertNoRequest(setOf("openProfile", "saveProfile"), QUIET_MS)

        activate(inDialog(text(R.string.game_link_connect)))
        val open = engine.receive("openProfile")
        assertEquals(ALPHA.name, open.getString("name"))
        assertTrue(open.getBoolean("connect"))
        assertEquals("the profile's own password, never the link's", "alpha secret", open.getString("password"))
        engine.profiles(listOf(ALPHA), open)
        waitFor(hasText(text(R.string.game_title)))
        compose.onNodeWithText(question).assertDoesNotExist()
        engine.assertNoRequest("saveProfile", QUIET_MS)
    }

    @Test
    fun theQuestionOutlivesARotation() {
        listed(ALPHA)
        openLink("telnet://a.example")
        val question = text(R.string.game_link_question_profile, "a.example:23", ALPHA.name)
        waitFor(hasText(question))
        compose.activityRule.scenario.recreate()
        waitFor(hasText(question))
        engine.assertNoRequest(setOf("openProfile", "saveProfile"), QUIET_MS)
        activate(inDialog(text(R.string.game_link_cancel)))
    }

    @Test
    fun cancelConnectsNothing() {
        listed(ALPHA)
        openLink("telnet://a.example")
        waitFor(hasText(text(R.string.game_link_question_profile, "a.example:23", ALPHA.name)))
        activate(inDialog(text(R.string.game_link_cancel)))
        compose.onNode(isDialog()).assertDoesNotExist()
        engine.assertNoRequest(setOf("openProfile", "saveProfile", "setFront"), QUIET_MS)
        compose.onNodeWithText(text(R.string.profiles_title)).assertIsDisplayed()
    }

    @Test
    fun anOpenGameComesToTheFrontAndConnectsToItsOwnAddress() {
        listed(ALPHA)
        fake.engine.openAlongside(ALPHA.name)
        engine.openProfiles()
        val alpha = engine.forProfile(ALPHA.name)
        compose.waitUntil(10_000) { EngineClient.sessions.size == 1 }
        openLink("telnet://a.example:23")
        activate(inDialog(text(R.string.game_link_connect)))
        assertEquals(ALPHA.name, engine.receive("setFront").getString("name"))
        val connect = alpha.receive("connect")
        assertEquals("a.example", connect.getString("host"))
        assertEquals(23, connect.getInt("port"))
        waitFor(hasText(text(R.string.game_title)))
        engine.assertNoRequest("openProfile", QUIET_MS)
    }

    @Test
    fun anUnknownAddressOpensTheEditorFilledInAndSavesNothingUntilThePlayerDoes() {
        listed(ALPHA)
        openLink("telnets://hero:link-password@new.example:6000")
        waitFor(hasText(text(R.string.game_link_check)))
        compose.onNode(isDialog()).assertDoesNotExist()
        field(R.string.profile_name).assert(hasText("New"))
        field(R.string.profile_host).assert(hasText("new.example"))
        field(R.string.profile_port).assert(hasText("6000"))
        field(R.string.profile_login).assert(hasText("hero"))
        compose.onNode(hasText("link-password", substring = true)).assertDoesNotExist()
        compose.onNode(hasText(text(R.string.profile_tls))).assertIsDisplayed()
        engine.assertNoRequest(setOf("saveProfile", "openProfile"), QUIET_MS)

        activate(hasText(text(R.string.profile_save)))
        val save = engine.receive("saveProfile")
        assertEquals(Profile("New", "new.example", 6000, true, "hero", false), profileOf(save))
        assertFalse(save.has("password"))
        engine.profiles(listOf(ALPHA, profileOf(save)), save)
        waitFor(hasText(text(R.string.profiles_title)))
        compose.runOnIdle { assertFalse("the link's password is not stored", passwords.has("New")) }
        engine.assertNoRequest("openProfile", QUIET_MS)
    }

    @Test
    fun aLinkCannotChangeASavedProfilesAddressOrSecurity() {
        listed(ALPHA)
        // Alpha's host at another port, then at its port over TLS, which Alpha does not use: a new game each time
        for ((link, port, tls) in listOf(Triple("telnet://a.example:4000", 4000, false), Triple("telnets://a.example:23", 23, true))) {
            openLink(link)
            waitFor(hasText(text(R.string.game_link_check)))
            field(R.string.profile_name).assert(hasText("A"))
            field(R.string.profile_port).assert(hasText(port.toString()))
            activate(hasText(text(R.string.profile_save)))
            val save = engine.receive("saveProfile")
            assertEquals(Profile("A", "a.example", port, tls, "", false), profileOf(save))
            engine.profiles(listOf(ALPHA), save)
            waitFor(hasText(text(R.string.profiles_title)))
        }
        compose.runOnIdle { assertEquals(listOf(ALPHA), EngineClient.profiles) }
    }

    @Test
    fun aLinkToOneOfMudletsGamesAsksThenAddsItAndConnects() {
        listed(ALPHA)
        openLink("telnet://achaea.com")
        waitFor(hasText(text(R.string.game_link_question_game, "achaea.com:23", ACHAEA)))
        compose.onNode(isDialog()).tryPerformAccessibilityChecks()
        engine.assertNoRequest(setOf("openProfile", "saveProfile"), QUIET_MS)
        activate(inDialog(text(R.string.game_link_connect)))
        val save = engine.receive("saveProfile")
        val achaea = Profile(ACHAEA, "achaea.com", 23, false, "", false)
        assertEquals(achaea, profileOf(save))
        engine.profiles(listOf(ALPHA, achaea), save)
        val open = engine.receive("openProfile")
        assertEquals(ACHAEA, open.getString("name"))
        engine.profiles(listOf(achaea, ALPHA), open)
        waitFor(hasText(text(R.string.game_title)))
    }

    @Test
    fun aBrokenLinkSaysSoAndOpensNothing() {
        listed(ALPHA)
        for (link in listOf("telnet://a.example:0", "telnets://192.0.2.1", "telnet://")) {
            openLink(link)
            waitFor(hasText(text(R.string.game_link_refused_text)))
            compose.onNode(isDialog()).tryPerformAccessibilityChecks()
            activate(inDialog(text(R.string.game_link_refused_close)))
            compose.onNode(isDialog()).assertDoesNotExist()
        }
        engine.assertNoRequest(setOf("openProfile", "saveProfile", "setFront"), QUIET_MS)
        compose.onNodeWithText(text(R.string.profiles_title)).assertIsDisplayed()
    }

    private fun openLink(link: String) {
        val context = compose.activity.applicationContext
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun listed(vararg profiles: Profile) {
        engine.profiles(profiles.toList(), engine.receive("listProfiles"))
        compose.waitUntil(10_000) { EngineClient.profiles == profiles.toList() }
    }

    private fun text(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun field(@StringRes label: Int) = compose.onNode(hasSetTextAction() and hasText(text(label)))

    private fun activate(label: SemanticsMatcher) = activate(compose, label)

    companion object {
        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
        const val QUIET_MS = 1_000
        const val ACHAEA = "Achaea"
        val ALPHA = Profile("Alpha", "a.example", 23, false, "", false)

        fun profileOf(request: JSONObject) = Profile(
            request.getString("name"),
            request.getString("host"),
            request.getInt("port"),
            request.getBoolean("tls"),
            request.getString("login"),
            request.getBoolean("autoReconnect"),
        )

        /** The dialog's button or text [text]: the profile list behind it has a Connect of its own. */
        fun inDialog(text: String) = hasText(text) and hasAnyAncestor(isDialog())

        /** What a double tap does under TalkBack: the node's accessibility click, scrolled into view first. */
        fun activate(compose: ComposeTestRule, label: SemanticsMatcher) {
            val control = label and hasClickAction()
            compose.waitUntil(10_000) { compose.onAllNodes(control).fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodes(control and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNode(control).performScrollTo()
            }
            compose.onNode(control).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        }
    }
}

/** A link that starts the app, as from a browser while it is not running: the same question, before anything connects. */
@RunWith(AndroidJUnit4::class)
class GameLinkColdStartTest {
    private val fake = FakeEngineRule()
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(GameLinkTest.TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    @Test
    fun aLinkThatStartsTheAppAsksBeforeItConnects() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("telnet://a.example:23")).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            val engine = fake.engine.nextSession()
            engine.profiles(listOf(GameLinkTest.ALPHA), engine.receive("listProfiles"))
            val question = context.getString(R.string.game_link_question_profile, "a.example:23", GameLinkTest.ALPHA.name)
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(question)).fetchSemanticsNodes().isNotEmpty() }
            engine.assertNoRequest("openProfile", GameLinkTest.QUIET_MS)
            GameLinkTest.activate(compose, GameLinkTest.inDialog(context.getString(R.string.game_link_connect)))
            assertEquals(GameLinkTest.ALPHA.name, engine.receive("openProfile").getString("name"))
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { it.finish() }
            }
        }
    }
}
