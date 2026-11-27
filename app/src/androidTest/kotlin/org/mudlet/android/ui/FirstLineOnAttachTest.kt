package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile

/**
 * A profile's first line once the UI attaches, against a fake engine: shown ahead of the state the engine sends after
 * its lines (docs/protocol.md, "Attaching"), not after it, as a profile's labels and windows can take the UI longer to
 * show than its lines.
 */
@RunWith(AndroidJUnit4::class)
class FirstLineOnAttachTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    @Test
    fun theFirstLineIsShownBeforeTheStateThatFollowsIt() {
        compose.enableAccessibilityChecksOfWhatShows()
        val game = Profile("first line ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
        val session = fake.engine.nextSession()
        // Held for the profile, and sent together once the UI attaches to it
        session.clear()
        session.line(1, LINE)
        session.send(JSONObject().put("t", "commands").put("commands", JSONArray().put(JSONObject().put("id", 1).put("package", "p").put("name", "Command"))))
        session.synced(1)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }

        // Looked at after each of the main thread's messages, as that is where the UI takes in what the engine sent
        var lineShown = false
        var stateFirst = false
        Looper.getMainLooper().setMessageLogging { message ->
            val opened = EngineClient.sessions.firstOrNull { it.name == game.name }
            if (message.startsWith("<<<<<") && opened != null && !lineShown) {
                lineShown = opened.lines.any { it.text == LINE }
                stateFirst = stateFirst || (!lineShown && opened.windows.commands.isNotEmpty())
            }
        }
        try {
            val connect = hasContentDescription(compose.activity.getString(R.string.profile_connect_to, game.name)) and hasClickAction()
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(connect).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(connect).performSemanticsAction(SemanticsActions.OnClick)
            session.profiles(listOf(game), session.receive("openProfile"))
            compose.waitUntil(WAIT_MS) { compose.runOnIdle { lineShown && EngineClient.front.windows.commands.isNotEmpty() } }
        } finally {
            Looper.getMainLooper().setMessageLogging(null)
        }
        assertFalse("the commands sent after the first line were shown before it", stateFirst)
    }

    private companion object {
        const val LINE = "the first line"
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 60_000L
    }
}
