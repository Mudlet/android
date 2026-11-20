package org.mudlet.android.ui

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.mudlet.android.data.LogFiles
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.profiles.LOG_FILE_TAG
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A profile's log files against a fake engine, in its editor and on its game's Logging page: listed newest first with
 * their date and size, Mudlet's replays and its empty error log left out, and each shared or opened through a copy the
 * app's FileProvider serves with a read grant. Neither the share sheet nor a viewer starts: the test sees the intent.
 */
@RunWith(AndroidJUnit4::class)
class ShareLogTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val starts = Starts()

    // A blocking monitor gives a plain startActivity no result, and starts nothing
    private val blockers = listOf(
        Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, true),
        Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_VIEW, "text/*").apply { addDataScheme("content") }, null, true),
    )
    private lateinit var session: FakeEngine.Session
    private lateinit var folder: File

    /** Every intent the app starts */
    private class Starts : Instrumentation.ActivityMonitor() {
        val started = CopyOnWriteArrayList<Intent>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            started += intent
            return null
        }
    }

    @Before
    fun writeTheLogs() {
        compose.enableAccessibilityChecksOfWhatShows()
        instrumentation.addMonitor(starts)
        blockers.forEach(instrumentation::addMonitor)
        folder = LogFiles.folder(compose.activity.filesDir, GAME.name).apply {
            deleteRecursively()
            mkdirs()
        }
        write(HTML, HTML_TEXT, OLDER)
        write(TEXT, TEXT_TEXT, NEWER)
        write(REPLAY, "replay", NEWEST)
        write(ERRORS, "", NEWEST)
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
    }

    @After
    fun removeTheLogs() {
        instrumentation.removeMonitor(starts)
        blockers.forEach(instrumentation::removeMonitor)
        folder.deleteRecursively()
    }

    @Test
    fun theEditorListsTheLogsNewestFirst_andSharesOneThroughTheShareSheet() {
        openTheEditor()
        val rows = compose.onAllNodes(hasTestTag(LOG_FILE_TAG)).fetchSemanticsNodes()
        assertEquals("the replay and the empty error log are not logs", 2, rows.size)
        // positionInRoot, as boundsInRoot is clipped to the screen and a row below it has none
        val text = compose.onNode(hasText(TEXT)).fetchSemanticsNode().positionInRoot
        val html = compose.onNode(hasText(HTML)).fetchSemanticsNode().positionInRoot
        assertTrue("the newest log is not first", text.y < html.y)
        compose.onNode(hasText(TEXT) and hasText(facts(NEWER, TEXT_TEXT.length.toLong()))).assertExists()
        compose.onNode(hasText(HTML) and hasText(facts(OLDER, HTML_TEXT.length.toLong()))).assertExists()
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(hasContentDescription(string(R.string.profile_log_share, TEXT)))
        compose.waitUntil(WAIT_MS) { starts.started.any { it.action == Intent.ACTION_CHOOSER } }
        val chooser = starts.started.single { it.action == Intent.ACTION_CHOOSER }
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java) ?: throw AssertionError("the share sheet has nothing to share")
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java) ?: throw AssertionError("no file is shared")
        assertGranted(uri, send.flags, TEXT_TEXT)
        assertTrue("the log itself stays", File(folder, TEXT).isFile)
    }

    @Test
    fun aLogOpensInAViewer_inPlaceOfTheCopyShared() {
        openTheEditor()
        activate(hasContentDescription(string(R.string.profile_log_share, TEXT)))
        compose.waitUntil(WAIT_MS) { starts.started.any { it.action == Intent.ACTION_CHOOSER } }

        activate(hasContentDescription(string(R.string.profile_log_open, HTML)))
        compose.waitUntil(WAIT_MS) { starts.started.any { it.action == Intent.ACTION_VIEW } }
        val view = starts.started.single { it.action == Intent.ACTION_VIEW }
        assertEquals("text/html", view.type)
        val uri = view.data ?: throw AssertionError("nothing to open")
        assertGranted(uri, view.flags, HTML_TEXT)
        assertEquals("only the last copy is kept", listOf(HTML), File(compose.activity.cacheDir, "logs").list()!!.toList())
    }

    @Test
    fun onlyTheNewestTenShowUntilAllAreAskedFor() {
        for (i in 1..10) write("2026-10-${i.toString().padStart(2, '0')}.txt", "log $i", OLDER - i * 60_000L)
        openTheEditor()
        assertEquals(10, compose.onAllNodes(hasTestTag(LOG_FILE_TAG)).fetchSemanticsNodes().size)
        val showAll = hasText(compose.activity.resources.getQuantityString(R.plurals.profile_logs_show_all, 12, 12)) and hasClickAction()
        activate(showAll)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(LOG_FILE_TAG)).fetchSemanticsNodes().size == 12 }
        assertTrue(compose.onAllNodes(showAll).fetchSemanticsNodes().isEmpty())
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aProfileWithoutLogsSaysSo() {
        folder.deleteRecursively()
        openTheEditor(rowsExpected = false)
        waitFor(hasText(string(R.string.profile_logs_none)))
        compose.onNode(hasText(string(R.string.profile_logs_none))).performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTag(LOG_FILE_TAG)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theGamesLoggingPageListsItsLogs() {
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_TITLE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        session.status("profile", "${GAME.name} loaded")
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable } }
        compose.pickFromGameMenu(string(R.string.settings_title))
        val page = hasText(string(R.string.settings_logging)) and hasClickAction()
        compose.scrollSettingsHubTo(page)
        activate(page)
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))

        waitFor(hasTestTag(LOG_FILE_TAG))
        compose.onNode(hasText(TEXT)).performScrollTo().assertIsDisplayed()
        assertEquals(2, compose.onAllNodes(hasTestTag(LOG_FILE_TAG)).fetchSemanticsNodes().size)
        compose.onRoot().tryPerformAccessibilityChecks()
        activate(hasContentDescription(string(R.string.profile_log_share, HTML)))
        compose.waitUntil(WAIT_MS) { starts.started.any { it.action == Intent.ACTION_CHOOSER } }
    }

    // A content URI of the app's log provider alone, which the other app may read once and which names no profile
    private fun assertGranted(uri: Uri, flags: Int, text: String) {
        val context = compose.activity
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.logs", uri.authority)
        assertFalse("the URI names the profile", uri.toString().contains(GAME.name))
        assertTrue("the other app may not read it", (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertFalse("the other app may write it", (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
        context.contentResolver.openInputStream(uri)!!.use { assertEquals(text, it.readBytes().decodeToString()) }
    }

    private fun openTheEditor(rowsExpected: Boolean = true) {
        activate(hasContentDescription(string(R.string.profile_edit, GAME.name)))
        activate(hasText(string(R.string.profile_manage_section)))
        waitFor(hasText(string(R.string.profile_logs_title)))
        if (rowsExpected) {
            waitFor(hasTestTag(LOG_FILE_TAG))
            compose.onAllNodes(hasTestTag(LOG_FILE_TAG))[0].performScrollTo()
        }
    }

    private fun facts(modified: Long, size: Long): String {
        val context = compose.activity
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        return context.getString(R.string.profile_log_facts, DateUtils.formatDateTime(context, modified, flags), Formatter.formatShortFileSize(context, size))
    }

    private fun write(name: String, text: String, modified: Long) {
        assertTrue(File(folder, name).apply { writeText(text) }.setLastModified(modified))
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, scrolled to as TalkBack does. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        val node = compose.onNode(label and hasClickAction())
        node.performScrollTo()
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private companion object {
        val GAME = Profile("Logbook", "127.0.0.1", 4000, false, "", false)
        const val HTML = "2026-11-18#20-00-00.html"
        const val TEXT = "2026-11-19#09-30-00.txt"
        const val REPLAY = "2026-11-19#10-00-00.dat"
        const val ERRORS = "errors.txt"
        const val HTML_TEXT = "<html><body>Hello</body></html>"
        const val TEXT_TEXT = "Hello there"

        // Whole seconds, as some file systems keep no finer
        const val OLDER = 1_763_000_000_000L
        const val NEWER = 1_763_500_000_000L
        const val NEWEST = 1_763_600_000_000L
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
