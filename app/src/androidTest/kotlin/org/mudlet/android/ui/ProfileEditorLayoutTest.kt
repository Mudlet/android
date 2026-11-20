package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
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
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * The profile editor as a first-time player meets it: only what connecting needs shows, and the rest is folded under
 * headings that TalkBack reads as buttons with their state, each saying what it holds while folded. A section opens by
 * its heading, or by itself when it holds a mistake Save waits for, stays as it was over a rotation, and what is
 * folded is saved all the same.
 */
@RunWith(AndroidJUnit4::class)
class ProfileEditorLayoutTest {
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
        forgetPasswords()
        engine = fake.engine.nextSession()
    }

    @After
    fun forgetPasswords() {
        for (name in listOf(HERO.name, NEW.name)) {
            passwords.forget(name)
            passwords.forget(name, PasswordStore.Kind.PROXY)
            Settings.setAwaitsDefaults(name, false)
            Settings.setAwaitsInputClear(name, false)
        }
    }

    @Test
    fun aNewGameShowsWhatConnectingNeedsAndFoldsTheRest() {
        listed()
        activate(hasContentDescription(string(R.string.profiles_add)))
        for (field in listOf(R.string.profile_name, R.string.profile_host, R.string.profile_port)) {
            compose.onNode(hasSetTextAction() and hasText(string(field))).assertIsDisplayed()
        }
        field(R.string.profile_port).assert(hasText("23"))
        compose.onNode(hasText(string(R.string.profile_host_help))).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.profile_tls)) and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.profile_save)) and hasClickAction()).assertIsDisplayed()
        for (folded in listOf(R.string.profile_login, R.string.profile_password, R.string.profile_proxy_host)) {
            absent(hasSetTextAction() and hasText(string(folded)))
        }
        absent(hasText(string(R.string.profile_auto_reconnect)))
        absent(hasText(string(R.string.profile_proxy_title)))
        absent(hasText(string(R.string.profile_manage_section)))

        for ((section, summary) in listOf(
            R.string.profile_login_section to string(R.string.profile_login_not_set),
            R.string.profile_advanced_section to string(R.string.profile_advanced_reconnect_off),
        )) {
            heading(section)
                .assert(hasText(summary))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(state(R.string.profile_section_collapsed))
        }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aHeadingOpensItsSectionAndClosesItAgain() {
        listed()
        activate(hasContentDescription(string(R.string.profiles_add)))
        unfold(R.string.profile_login_section)
        heading(R.string.profile_login_section).assert(state(R.string.profile_section_expanded))
        field(R.string.profile_login).assertIsDisplayed()
        field(R.string.profile_password).assertIsDisplayed()
        absent(hasText(string(R.string.profile_login_not_set)))

        unfold(R.string.profile_advanced_section)
        compose.onNode(hasText(string(R.string.profile_auto_reconnect)) and hasClickAction()).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(string(R.string.profile_proxy_while_open))).performScrollTo().assertIsDisplayed()

        unfold(R.string.profile_login_section)
        absent(hasSetTextAction() and hasText(string(R.string.profile_login)))
        heading(R.string.profile_login_section).assert(state(R.string.profile_section_collapsed))
    }

    @Test
    fun eachFoldedSectionSaysWhatItHoldsButNeverThePassword() {
        assertEquals(true, passwords.set(HERO.name, SECRET))
        assertEquals(true, passwords.set(HERO.name, PROXY_SECRET, PasswordStore.Kind.PROXY))
        listed(HERO)
        activate(hasContentDescription(string(R.string.profile_edit, HERO.name)))
        heading(R.string.profile_login_section).assert(hasText(string(R.string.profile_login_as_password, HERO.login)))
        heading(R.string.profile_advanced_section).assert(
            hasText(string(R.string.profile_advanced_summary, string(R.string.profile_advanced_reconnect_on), string(R.string.profile_advanced_proxy_password))),
        )
        heading(R.string.profile_manage_section).assert(hasText(string(R.string.profile_manage_summary)))
        for (secret in listOf(SECRET, PROXY_SECRET)) absent(hasText(secret, substring = true))

        // A saved game's own actions are under its heading, Delete last
        absent(hasText(string(R.string.profile_export)) and hasClickAction())
        absent(hasText(string(R.string.profile_delete)) and hasClickAction())
        unfold(R.string.profile_manage_section)
        compose.onNode(hasText(string(R.string.profile_delete)) and hasClickAction()).performScrollTo().assertIsDisplayed()
        val order = pageOrder(R.string.profile_open_offline, R.string.profile_export, R.string.profile_logs_title, R.string.profile_delete)
        assertEquals("Open offline, Export, the logs, then Delete", order.sorted(), order)
    }

    @Test
    fun aMistakeInAFoldedSectionOpensIt() {
        val session = openLoaded(HERO)
        activate(hasContentDescription(string(R.string.profile_edit, HERO.name)))
        compose.waitForIdle()
        // On with no address: the engine would refuse it, so Save waits for it
        session.proxy(true, "", 0, "", session.receive("getProxy").get("id"))
        waitFor(hasSetTextAction() and hasText(string(R.string.profile_proxy_host)))
        heading(R.string.profile_advanced_section).assert(state(R.string.profile_section_expanded))
        compose.onNode(hasText(string(R.string.profile_save)) and hasClickAction()).assertIsNotEnabled()

        // Folded again by the player, it says so
        unfold(R.string.profile_advanced_section)
        heading(R.string.profile_advanced_section).assert(
            hasText(string(R.string.profile_advanced_summary, string(R.string.profile_advanced_reconnect_on), string(R.string.profile_advanced_proxy_fix))),
        )
    }

    @Test
    fun whatIsOpenOutlivesARotation() {
        listed()
        activate(hasContentDescription(string(R.string.profiles_add)))
        unfold(R.string.profile_login_section)
        type(R.string.profile_login, "hero")
        compose.activityRule.scenario.recreate()
        // The new Activity attaches to the engine again
        engine = fake.engine.nextSession()
        listed()
        waitFor(hasSetTextAction() and hasText(string(R.string.profile_login)))
        field(R.string.profile_login).assert(hasText("hero"))
        absent(hasText(string(R.string.profile_auto_reconnect)))
    }

    @Test
    fun aGameIsSavedWithItsSectionsFolded() {
        listed()
        activate(hasContentDescription(string(R.string.profiles_add)))
        type(R.string.profile_name, NEW.name)
        type(R.string.profile_host, NEW.host)
        unfold(R.string.profile_login_section)
        type(R.string.profile_login, NEW.login)
        type(R.string.profile_password, SECRET)
        unfold(R.string.profile_advanced_section)
        activate(hasText(string(R.string.profile_auto_reconnect)))
        unfold(R.string.profile_login_section)
        unfold(R.string.profile_advanced_section)
        heading(R.string.profile_login_section).assert(hasText(string(R.string.profile_login_as_password, NEW.login)))
        activate(hasText(string(R.string.profile_save)))

        val save = engine.receive("saveProfile")
        assertEquals(NEW, profileOf(save))
        assertFalse(save.has("password"))
        engine.profiles(listOf(NEW), save)
        waitFor(hasContentDescription(string(R.string.profile_description, NEW.name, NEW.host, NEW.port)))
        compose.runOnIdle { assertEquals("a password typed into a folded section is stored", SECRET, passwords.get(NEW.name)) }
    }

    // Where each text is in the page's semantics tree, which is the order of the page's column, top to bottom
    private fun pageOrder(@StringRes vararg ids: Int): List<Int> {
        val texts = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            node.children.forEach(::walk)
        }
        walk(compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(string(ids.first())))).fetchSemanticsNode())
        return ids.map { id -> texts.indexOf(string(id)).also { assertTrue("${string(id)} is on the page", it >= 0) } }
    }

    private fun state(@StringRes id: Int) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(id))

    private fun openLoaded(profile: Profile): FakeEngine.Session {
        listed(profile)
        activate(hasContentDescription(string(R.string.profile_connect_to, profile.name)))
        engine.profiles(listOf(profile), engine.receive("openProfile"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
        engine.status("profile", "${profile.name} loaded")
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.session(profile.name)?.settingsAvailable == true } }
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasContentDescription(string(R.string.profile_edit, profile.name)))
        return engine
    }

    private fun listed(vararg profiles: Profile) {
        engine.profiles(profiles.toList(), engine.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == profiles.toList() }
    }

    private fun profileOf(request: JSONObject) = Profile(
        request.getString("name"),
        request.getString("host"),
        request.getInt("port"),
        request.getBoolean("tls"),
        request.getString("login"),
        request.getBoolean("autoReconnect"),
    )

    private fun heading(@StringRes section: Int) = compose.onNode(hasText(string(section)) and hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

    private fun unfold(@StringRes section: Int) = activate(hasText(string(section)))

    private fun absent(matcher: SemanticsMatcher) = assertEquals("${matcher.description} shows", 0, compose.onAllNodes(matcher).fetchSemanticsNodes().size)

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun field(@StringRes label: Int) = compose.onNode(hasSetTextAction() and hasText(string(label)))

    // Through the field's accessibility action, as TalkBack sets text
    private fun type(@StringRes label: Int, text: String) {
        scrollIntoView(hasSetTextAction() and hasText(string(label)))
        field(label).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
    }

    private fun activate(label: SemanticsMatcher) {
        val control = label and hasClickAction()
        waitFor(control)
        scrollIntoView(control)
        compose.onNode(control).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun scrollIntoView(matcher: SemanticsMatcher) {
        if (compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(matcher).performScrollTo()
        }
    }

    // The engine's answers come over the socket, which the test rule's idling does not wait for
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private companion object {
        val HERO = Profile("Fold Test", "f.example", 23, false, "hero", true)
        val NEW = Profile("Fold New", "n.example", 23, false, "newbie", true)
        const val SECRET = "fold secret"
        const val PROXY_SECRET = "fold proxy secret"
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
