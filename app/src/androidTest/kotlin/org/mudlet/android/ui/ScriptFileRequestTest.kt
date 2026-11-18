package org.mudlet.android.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.ScriptFilePicks
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A game script's invokeFileDialog() against a fake engine: the engine's pickFile shows the app's dialog with the
 * script's title; Cancel, Back and the system picker's own back answer a cancel; a chosen file is copied into the
 * folder the engine named, beside a file of the same name it leaves alone, and its path is the answer; a folder outside
 * the app's files takes no file; and neither the file's name nor its content reaches the log.
 */
@RunWith(AndroidJUnit4::class)
class ScriptFileRequestTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @get:Rule
    val rules: RuleChain = scriptFileRules(fake, compose)

    private lateinit var session: FakeEngine.Session
    private lateinit var into: File

    @Before
    fun listTheProfile() {
        compose.enableAccessibilityChecksOfWhatShows()
        into = File(compose.activity.filesDir, "mudlet/profiles/${GAME.name}").apply { deleteRecursively() }
        into.mkdirs()
        session = fake.engine.nextSession()
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
    }

    // EngineClient lives as long as the test process: a dialog left showing would cover every later test's screen
    @After
    fun endThePick() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScriptFilePicks.choosing = null
            EngineClient.answerFilePick("")
        }
        into.deleteRecursively()
    }

    @Test
    fun cancelBackAndThePickersBackAnswerACancel() {
        ask(1, SCRIPT_TITLE)
        compose.onNodeWithText(SCRIPT_TITLE, substring = true).assertExists()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText(compose.string(R.string.script_file_cancel)).performClick()
        assertAnswer(1, "")
        waitForDialog(false)

        ask(2, "")
        device.pressBack()
        assertAnswer(2, "")

        ask(3, SCRIPT_TITLE)
        compose.onNodeWithText(compose.string(R.string.script_file_choose)).performClick()
        assertTrue("the system's picker in front", device.wait(Until.gone(By.pkg(compose.activity.packageName)), WAIT_MS))
        device.pressBack()
        assertTrue("the app in front again", device.wait(Until.hasObject(By.pkg(compose.activity.packageName)), WAIT_MS))
        assertAnswer(3, "")
        waitForDialog(false)
    }

    @Test
    fun aChosenFileIsCopiedIntoTheFolderAndItsPathIsTheAnswer() {
        val there = File(into, "file").apply { writeText(OLD) }
        val chosen = File(compose.activity.cacheDir, FILE_NAME).apply { writeText(CONTENT) }
        ask(4, SCRIPT_TITLE)
        // As the system picker's result would: Choose has been tapped, and the file comes back
        choose(4, Uri.fromFile(chosen))
        val answer = session.receive("answerPickFile")
        assertEquals(4L, answer.getLong("id"))
        val copy = File(answer.getString("path"))
        assertEquals(into.canonicalFile, copy.parentFile?.canonicalFile)
        assertEquals(CONTENT, copy.readText())
        assertEquals(OLD, there.readText())
        waitForDialog(false)

        Log.w(TAG, SENTINEL)
        val log = readLog()
        assertTrue("the log was not read", log.any { it.contains(SENTINEL) })
        val found = log.filter { it.contains(CONTENT) || it.contains(FILE_NAME) }
        assertTrue("the file is in the log:\n${found.joinToString("\n")}", found.isEmpty())
        chosen.delete()
    }

    @Test
    fun aFolderOutsideTheAppsFilesTakesNoFile() {
        val outside = File(compose.activity.cacheDir, "outside").apply { mkdirs() }
        val chosen = File(compose.activity.cacheDir, FILE_NAME).apply { writeText(CONTENT) }
        session.send(JSONObject().put("t", "pickFile").put("id", 5).put("title", "").put("into", outside.path))
        waitForDialog(true)
        choose(5, Uri.fromFile(chosen))
        assertAnswer(5, "")
        assertEquals(0, outside.list()?.size)
        outside.deleteRecursively()
        chosen.delete()
    }

    private fun ask(id: Long, title: String) {
        session.send(JSONObject().put("t", "pickFile").put("id", id).put("title", title).put("into", into.path))
        waitForDialog(true)
    }

    private fun choose(id: Long, uri: Uri) = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        ScriptFilePicks.choosing = id
        ScriptFilePicks.chosen(compose.activity.applicationContext, uri)
    }

    private fun assertAnswer(id: Long, path: String) {
        val answer = session.receive("answerPickFile")
        assertEquals(id, answer.getLong("id"))
        assertEquals(path, answer.getString("path"))
    }

    private fun waitForDialog(shown: Boolean) = compose.waitForScriptFileDialog(shown)

    // As About > Report a problem reads it: this app's own lines, whose uid the instrumentation shares
    private fun readLog(): List<String> {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(10, TimeUnit.SECONDS)
        return lines
    }

    private companion object {
        const val TAG = "ScriptFileRequest"
        const val SCRIPT_TITLE = "Choose the map to load"
        const val FILE_NAME = "pick-Sf5-name.dat"
        const val CONTENT = "pick-Sf5-content"
        const val OLD = "already here"
        const val SENTINEL = "log-Sf5-sentinel"
        val GAME = Profile("File Game", "f.example", 4000, false, "", false)
    }
}

/** The fake engine, the notification permission, so that its explanation does not cover the dialog, [inner] and [compose]. */
internal fun scriptFileRules(fake: FakeEngineRule, compose: TestRule, inner: TestRule = RuleChain.emptyRuleChain()): RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS))
    .around(fake)
    .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
    .around(inner)
    .around(compose)

internal fun AndroidComposeTestRule<*, MainActivity>.waitForScriptFileDialog(shown: Boolean) = waitUntil(WAIT_MS) {
    onAllNodesWithText(string(R.string.script_file_title)).fetchSemanticsNodes().isNotEmpty() == shown
}

private const val WAIT_MS = 10_000L

// Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
private const val TIMEOUT_MS = 240_000L
