package org.mudlet.android.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.data.Settings
import org.mudlet.android.engine.EngineNotifications

/**
 * Leaving the app and coming back, as the engine hears it (desktop Mudlet's minimize), and the "new game text"
 * notification EngineNotifications posts for what the engine reports while the app is away, here in the test's own
 * process: posted once a game asks for attention, one per game that follows its count, the game's text only when the
 * player allows it, a tap opens that game, and coming back takes it away.
 */
@RunWith(AndroidJUnit4::class)
class NewTextNotificationTest {
    private val fake = FakeEngineRule(open = listOf(ALPHA, BETA))
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private val context get() = compose.activity.applicationContext

    @After
    fun tearDown() {
        EngineNotifications.appState(context, away = false)
        compose.runOnIdle { Settings.gameTextInNotifications.reset() }
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @Test
    fun theEngineHearsTheAppLeaveAndComeBack() {
        val first = fake.engine.nextSession()
        assertAppState(first.receive("setAppState"), away = false)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertAppState(first.receive("setAppState"), away = true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertAppState(fake.engine.nextSession().receive("setAppState"), away = false)
    }

    @Test
    fun aRotationIsNoLeaving() {
        val first = fake.engine.nextSession()
        assertAppState(first.receive("setAppState"), away = false)
        compose.activityRule.scenario.recreate()
        first.assertNoRequest("setAppState", 2_000)
        assertAppState(fake.engine.nextSession().receive("setAppState"), away = false)
    }

    @Test
    fun aGameThatAsksForAttentionGetsOneNoticeThatFollowsItsCount() {
        fake.engine.nextSession()
        EngineNotifications.appState(context, away = true)
        EngineNotifications.gameText(context, ALPHA, 2, LINE, attention = false)
        EngineNotifications.gameText(context, ALPHA, 3, LINE, attention = true)
        // Before Alpha's last, so a notice of Beta's would be in the list by the time Alpha's count is
        EngineNotifications.gameText(context, BETA, 4, LINE, attention = false)
        EngineNotifications.gameText(context, ALPHA, 7, LINE, attention = false)
        val shown = awaitNotice(ALPHA) { text(it) == unread(7) }
        assertEquals("only the game that asked", listOf(ALPHA), notices().map { title(it.notification) })
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertEquals("the locked screen's version has no game text", unread(7), text(shown.publicVersion))

        EngineNotifications.appState(context, away = false)
        compose.waitUntil(WAIT_MS) { notices().isEmpty() }
    }

    @Test
    fun theGamesTextOnlyWhenThePlayerAllowsIt() {
        fake.engine.nextSession()
        val marker = EngineNotifications.gameTextMarker(context)
        compose.runOnIdle { Settings.gameTextInNotifications.set(true) }
        assertTrue("the switch is a file the engine reads", marker.exists())
        EngineNotifications.appState(context, away = true)
        EngineNotifications.gameText(context, ALPHA, 1, LINE, attention = true)
        val shown = awaitNotice(ALPHA) { text(it) == LINE }
        assertEquals(unread(1), shown.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        assertEquals(unread(1), text(shown.publicVersion))

        compose.runOnIdle { Settings.gameTextInNotifications.set(false) }
        assertFalse(marker.exists())
        EngineNotifications.gameText(context, ALPHA, 2, LINE, attention = false)
        awaitNotice(ALPHA) { text(it) == unread(2) }
    }

    @Test
    fun aTapOnTheNoticeShowsThatGame() {
        val engine = fake.engine.nextSession()
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.size == 2 && EngineClient.front.name == BETA }
        EngineNotifications.appState(context, away = true)
        EngineNotifications.gameText(context, ALPHA, 1, LINE, attention = true)
        awaitNotice(ALPHA).contentIntent.send()
        assertEquals(ALPHA, engine.receive("setFront").getString("name"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.name == ALPHA } }
    }

    @Test
    fun aTellLastsAsLongAsItsScriptAsked() {
        fake.engine.nextSession()
        EngineNotifications.postTell(context, ALPHA, TELL, LINE, timeoutMs = 60_000)
        compose.waitUntil(WAIT_MS) { tells().isNotEmpty() }
        val tell = tells().single()
        assertEquals(60_000L, tell.timeoutAfter)
        assertEquals(Notification.VISIBILITY_PRIVATE, tell.visibility)
    }

    private fun assertAppState(message: JSONObject, away: Boolean) {
        assertEquals(!away, message.getBoolean("active"))
        assertEquals(away, message.getBoolean("minimized"))
    }

    private fun notices(channel: String = EngineNotifications.CHANNEL_NEW_TEXT): List<StatusBarNotification> = context.getSystemService(NotificationManager::class.java).activeNotifications.filter { it.notification.channelId == channel }

    private fun tells() = notices(EngineNotifications.CHANNEL_TELLS).map { it.notification }.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    // Posting and cancelling reach the system's list a moment later
    private fun awaitNotice(game: String, shows: (Notification) -> Boolean = { true }): Notification {
        var found: Notification? = null
        compose.waitUntil(WAIT_MS) {
            found = notices().map { it.notification }.singleOrNull { title(it) == game }?.takeIf(shows)
            found != null
        }
        return found!!
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun unread(lines: Int) = context.resources.getQuantityString(org.mudlet.android.engine.R.plurals.new_text_unread, lines, lines)

    private companion object {
        const val TIMEOUT_MS = 240_000L
        const val WAIT_MS = 10_000L
        const val ALPHA = "Alpha"
        const val BETA = "Beta"
        const val TELL = "A tell"
        const val LINE = "A dragon lands beside you."
    }
}
