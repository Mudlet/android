package org.mudlet.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.engine.EngineNotifications

/**
 * A tap on one of a game's notifications (EngineNotifications.EXTRA_PROFILE on the launcher intent) brings that game
 * to the front and shows the game screen, from wherever the app was.
 */
@RunWith(AndroidJUnit4::class)
class NotificationTapTest {
    private val fake = FakeEngineRule(open = listOf(ALPHA, BETA))
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    @Test
    fun aTapOnAGamesNotificationShowsThatGame() {
        val engine = fake.engine.nextSession()
        compose.waitUntil(10_000) { EngineClient.sessions.size == 2 && EngineClient.front.name == BETA }
        compose.onNodeWithText(compose.activity.getString(R.string.profiles_title)).assertIsDisplayed()

        val context = compose.activity.applicationContext
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(EngineNotifications.EXTRA_PROFILE, ALPHA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        assertEquals(ALPHA, engine.receive("setFront").getString("name"))
        val game = compose.activity.getString(R.string.game_title)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(game).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(game).assertIsDisplayed()
        compose.runOnIdle { assertEquals(ALPHA, EngineClient.front.name) }
    }

    private companion object {
        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
        const val ALPHA = "Alpha"
        const val BETA = "Beta"
    }
}
