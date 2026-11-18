package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.GAME_SHEET_TAG
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * Two games open against a fake engine, with the engine's counts of their lines (docs/multi-profile.md, "The
 * switcher"): the unread and attention badges and what TalkBack reads of them on the profile list, the game sheet and
 * the game title; markSeen for the game in front; and what TalkBack says for a game in the background.
 */
@RunWith(AndroidJUnit4::class)
class GameBadgesTest {
    private val fake = FakeEngineRule(open = listOf(ALPHA.name, BETA.name))
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var alpha: FakeEngine.Session

    // The one opened last and in front at the start, whose session the control socket is answered on
    private lateinit var beta: FakeEngine.Session

    private val gameTitle = hasTestTag(GAME_TITLE_TAG)

    @Before
    fun attach() {
        beta = fake.engine.nextSession()
        beta.profiles(PROFILES, beta.receive("listProfiles"))
        alpha = beta.forProfile(ALPHA.name)
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.size == 2 && EngineClient.sessions.all { it.linkState.startsWith("attached") } }
        for (session in listOf(alpha, beta)) {
            session.clear()
            session.synced(0)
            session.status("connection", "connected")
        }
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.all { it.status["connection"] == "connected" } }
    }

    @Test
    fun aGameInTheBackgroundShowsItsBadges_andTalkBackReadsThemAndSaysItsActivityOnce() {
        val said = serial()
        counts(ALPHA.name, lastSeq = 150, unread = 150, attention = true)
        waitFor(openGame(ALPHA.name, unread = 150, attention = true))
        compose.onNode(hasText(string(R.string.game_unread_many, 99)), useUnmergedTree = true).assertIsDisplayed()
        compose.waitUntil(WAIT_MS) { Announcer.current?.text == string(R.string.game_attention_announce, ALPHA.name) }
        assertFalse(Announcer.current!!.assertive)
        assertEquals(said + 1, serial())
        // Until it is seen, more lines say nothing more
        counts(ALPHA.name, lastSeq = 151, unread = 151, attention = true)
        waitFor(openGame(ALPHA.name, unread = 151, attention = true))
        SystemClock.sleep(QUIET_MS.toLong())
        assertEquals(said + 1, serial())
        beta.assertNoRequest("markSeen", QUIET_MS)

        show(BETA.name)
        waitFor(titleReading(BETA.name, index = 2, others = 151, attention = true))

        activate(gameTitle)
        waitFor(hasTestTag(GAME_SHEET_TAG))
        compose.onNode(openGame(ALPHA.name, unread = 151, attention = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(openGame(BETA.name)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theGameInFrontIsMarkedSeen_whenItComesToTheFrontAndAsLinesCome() {
        counts(ALPHA.name, lastSeq = 12, unread = 12, attention = true)
        waitFor(openGame(ALPHA.name, unread = 12, attention = true))
        // In front from the start: its lines are seen as they come
        counts(BETA.name, lastSeq = 30, unread = 4, attention = false)
        assertSeen(BETA.name, 30)

        show(ALPHA.name, unread = 12, attention = true)
        assertSeen(ALPHA.name, 12)
        waitFor(titleReading(ALPHA.name, index = 1))
        // Once seen, the engine's next count asks for nothing
        counts(ALPHA.name, lastSeq = 12, unread = 0, attention = false)
        beta.assertNoRequest("markSeen", QUIET_MS)
        // Nor is the game in front said to want attention: it has it
        val said = serial()
        counts(ALPHA.name, lastSeq = 20, unread = 8, attention = true)
        assertSeen(ALPHA.name, 20)
        SystemClock.sleep(QUIET_MS.toLong())
        assertEquals(said, serial())

        // The one now in the background has its lines read on the title
        counts(BETA.name, lastSeq = 35, unread = 5, attention = false)
        waitFor(titleReading(ALPHA.name, index = 1, others = 5))
    }

    @Test
    fun aScriptsAnnounceInTheBackgroundIsSaidPolitelyWithItsGamesName() {
        show(BETA.name)
        beta.announce("You are hungry.", assertive = true)
        compose.waitUntil(WAIT_MS) { Announcer.current?.text?.endsWith("You are hungry.") == true }
        assertTrue(Announcer.current!!.assertive)
        SystemClock.sleep(QUIET_MS.toLong())

        alpha.announce("You are thirsty.", assertive = true)
        val expected = string(R.string.game_announce, ALPHA.name, "You are thirsty.")
        compose.waitUntil(WAIT_MS) { Announcer.current?.text == expected }
        assertFalse(Announcer.current!!.assertive)
    }

    private fun counts(name: String, lastSeq: Long, unread: Long, attention: Boolean) {
        fake.engine.counts(name, lastSeq, unread, attention)
        beta.openProfiles()
    }

    // And counts from there, as the engine does
    private fun assertSeen(name: String, seq: Long) {
        val seen = beta.receive("markSeen")
        assertEquals(name, seen.getString("name"))
        assertEquals(seq, seen.getLong("seq"))
        fake.engine.counts(name, seq, unread = 0, attention = false)
    }

    // From the profile list's open games, as a double tap under TalkBack does
    private fun show(name: String, unread: Long = 0, attention: Boolean = false) {
        waitFor(hasText(string(R.string.games_title)))
        activate(openGame(name, unread, attention))
        assertEquals(name, beta.receive("setFront").getString("name"))
        waitFor(gameTitle)
    }

    private fun openGame(name: String, unread: Long = 0, attention: Boolean = false): SemanticsMatcher {
        var description = string(R.string.game_state, name, string(R.string.connection_connected))
        if (unread > 0) description = string(R.string.game_description_more, description, quantity(R.plurals.game_unread, unread))
        if (attention) description = string(R.string.game_description_more, description, string(R.string.game_wants_attention))
        return hasContentDescription(description) and hasClickAction()
    }

    private fun titleReading(name: String, index: Int, others: Long = 0, attention: Boolean = false): SemanticsMatcher {
        var description = string(R.string.game_title_description, name, string(R.string.connection_connected), index, 2)
        if (others > 0) description = string(R.string.game_description_more, description, quantity(R.plurals.game_others_unread, others))
        if (attention) description = string(R.string.game_description_more, description, string(R.string.game_others_attention))
        return gameTitle and hasContentDescription(description)
    }

    private fun quantity(id: Int, count: Long) = compose.activity.resources.getQuantityString(id, count.toInt(), count)

    private fun serial() = compose.runOnIdle { Announcer.current?.serial ?: 0L }

    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        val node = compose.onNode(label and hasClickAction()).assertIsDisplayed()
        assertTrue(node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick) != null)
        node.performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private companion object {
        val ALPHA = Profile("Alpha", "127.0.0.1", 4001, false, "", false)
        val BETA = Profile("Beta", "127.0.0.1", 4002, false, "", false)
        val PROFILES = listOf(ALPHA, BETA)

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
        const val WAIT_MS = 10_000L

        // Long enough for a request or an announcement that went astray to have come
        const val QUIET_MS = 500
    }
}
