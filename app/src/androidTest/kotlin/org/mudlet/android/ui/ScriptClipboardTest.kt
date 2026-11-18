package org.mudlet.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import java.util.concurrent.TimeUnit

/**
 * A game script's use of the device's clipboard against a fake engine: the engine's clipboardRead is answered with the
 * clipboard's text, its clipboardWrite puts the text there under the app's label, and neither text reaches the log.
 */
@RunWith(AndroidJUnit4::class)
class ScriptClipboardTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake).around(compose)

    private lateinit var session: FakeEngine.Session

    @Before
    fun listTheProfile() {
        session = fake.engine.nextSession()
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
    }

    @Test
    fun aReadIsAnsweredWithTheClipboardsTextAndAWritePutsTextThere() {
        compose.runOnUiThread { clipboard().setPrimaryClip(ClipData.newPlainText("test", READ)) }
        session.send(JSONObject().put("t", "clipboardRead").put("id", 3))
        val answer = session.receive("answerClipboardRead")
        assertEquals(3L, answer.getLong("id"))
        assertEquals(READ, answer.getString("text"))

        session.send(JSONObject().put("t", "clipboardWrite").put("text", WRITTEN))
        compose.waitUntil(WAIT_MS) { clip()?.getItemAt(0)?.text?.toString() == WRITTEN }
        assertEquals(compose.activity.getString(R.string.script_clipboard_label), clip()?.description?.label?.toString())

        compose.runOnUiThread { clipboard().setPrimaryClip(ClipData.newPlainText("test", "")) }
        session.send(JSONObject().put("t", "clipboardRead").put("id", 4))
        assertEquals("", session.receive("answerClipboardRead").getString("text"))

        Log.w(TAG, SENTINEL)
        val log = readLog()
        assertTrue("the log was not read", log.any { it.contains(SENTINEL) })
        val found = log.filter { it.contains(READ) || it.contains(WRITTEN) }
        assertTrue("the clipboard's text is in the log:\n${found.joinToString("\n")}", found.isEmpty())
    }

    private fun clipboard() = compose.activity.getSystemService(ClipboardManager::class.java)

    private fun clip(): ClipData? {
        var clip: ClipData? = null
        compose.runOnUiThread { clip = clipboard().primaryClip }
        return clip
    }

    // As About > Report a problem reads it: this app's own lines, whose uid the instrumentation shares
    private fun readLog(): List<String> {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(10, TimeUnit.SECONDS)
        return lines
    }

    private companion object {
        const val TAG = "ScriptClipboard"
        const val READ = "clip-Sc4-read"
        const val WRITTEN = "clip-Sc4-written"
        const val SENTINEL = "log-Sc4-sentinel"
        val GAME = Profile("Clipboard Game", "c.example", 4000, false, "", false)
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
