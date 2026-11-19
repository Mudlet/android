package org.mudlet.android.ui

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.data.LogFiles
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.profiles.LOG_FILE_TAG
import java.io.File
import java.util.Locale

/**
 * The profile editor's log files (ShareLogTest) in the debug build's pseudo-locales, as PseudoLocaleTest checks the
 * screens: none of the text is cut short or leaves the window, and each log's buttons are at its row's end, on the
 * right in en-XA and on the left in ar-XB.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ShareLogPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private lateinit var folder: File

    @Before
    fun writeTheLogs() {
        compose.enableAccessibilityChecksOfWhatShows()
        folder = LogFiles.folder(compose.activity.filesDir, GAME.name).apply {
            deleteRecursively()
            mkdirs()
        }
        // More than the list shows at first, so "Show all" shows too
        for (i in 1..LOGS) {
            assertTrue(File(folder, "2026-11-${i.toString().padStart(2, '0')}#20-00-00.html").apply { writeText("<html>$i</html>") }.setLastModified(NEWEST - i * 60_000L))
        }
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
    }

    @After
    fun removeTheLogs() {
        folder.deleteRecursively()
    }

    @Test
    fun theLogsTextFits() {
        assertNotEquals("$locale's text", english(R.string.profile_logs_text), string(R.string.profile_logs_text))
        val edit = hasContentDescription(string(R.string.profile_edit, GAME.name)) and hasClickAction()
        waitFor(edit)
        compose.onNode(edit).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(hasTestTag(LOG_FILE_TAG))
        val showAll = compose.activity.resources.getQuantityString(R.plurals.profile_logs_show_all, LOGS, LOGS)
        compose.onNode(hasText(showAll) and hasClickAction()).performScrollTo()
        compose.onRoot().tryPerformAccessibilityChecks()

        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        val context = compose.activity
        val facts = string(R.string.profile_log_facts, DateUtils.formatDateTime(context, NEWEST - 60_000L, flags), Formatter.formatShortFileSize(context, "<html>1</html>".length.toLong()))
        for (text in listOf(string(R.string.profile_logs_title), string(R.string.profile_logs_text), FIRST, facts, showAll)) assertFits(text)

        compose.onNode(hasText(FIRST), useUnmergedTree = true).performScrollTo()
        val name = compose.onNode(hasText(FIRST), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$locale: the log's name is not on the screen", name.width > 0f)
        for (button in listOf(R.string.profile_log_open, R.string.profile_log_share)) {
            val bounds = compose.onNode(hasContentDescription(string(button, FIRST)) and hasClickAction()).fetchSemanticsNode().boundsInRoot
            if (locale == AR_XB) {
                assertTrue("$locale: the button at $bounds is not left of the log's name at $name", bounds.right <= name.left)
            } else {
                assertTrue("$locale: the button at $bounds is not right of the log's name at $name", bounds.left >= name.right)
            }
        }
    }

    private fun assertFits(text: String) {
        val count = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("$locale: \"$text\" is not shown", count > 0)
        for (i in 0 until count) {
            // On the screen first: boundsInRoot is clipped to it, and would be empty for a node scrolled off
            val node = compose.onAllNodes(hasText(text), useUnmergedTree = true)[i].performScrollTo().fetchSemanticsNode()
            val layouts = ArrayList<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
            val width = generateSequence(node) { it.parent }.last().size.width
            val bounds = node.boundsInRoot
            assertTrue("$locale: \"$text\" is not on the screen", bounds.width > 0f)
            assertTrue("$locale: \"$text\" at $bounds leaves its $width px window", bounds.left >= 0f && bounds.right <= width)
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun english(@StringRes id: Int): String {
        val config = Configuration(compose.activity.resources.configuration)
        config.setLocales(LocaleList(Locale.ENGLISH))
        return compose.activity.createConfigurationContext(config).getString(id)
    }

    companion object {
        private const val AR_XB = "ar-XB"
        private const val LOGS = 12
        private const val FIRST = "2026-11-01#20-00-00.html"
        private const val NEWEST = 1_763_600_000_000L
        private const val TIMEOUT_MS = 240_000L
        private val GAME = Profile("Logbook", "127.0.0.1", 4000, false, "", false)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", AR_XB)
    }
}
