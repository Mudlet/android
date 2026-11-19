package org.mudlet.android.ui

import android.Manifest
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.AnnotatedString
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
import org.mudlet.android.ui.profiles.ImportState
import org.mudlet.android.ui.profiles.Imports
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The dialog that names an imported profile in the debug build's pseudo-locales, as PseudoLocaleTest checks the
 * screens: none of its text is cut short or leaves the dialog, and in ar-XB it starts on the right.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ProfileImportPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun attach() {
        compose.enableAccessibilityChecksOfWhatShows()
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(emptyList(), session.receive("listProfiles"))
    }

    // Imports outlives the Activity: an import a failed run leaves open would show over the next test's profile list
    @After
    fun cleanUp() {
        compose.runOnUiThread {
            (Imports.state as? ImportState.Ready)?.let(Imports::cancel)
            Imports.forgetEnded()
        }
    }

    @Test
    fun theImportDialogsTextFits() {
        assertNotEquals("$locale's text", english(R.string.profile_import_text), string(R.string.profile_import_text))
        val archive = File(compose.activity.cacheDir, "pseudo-locale-import.zip")
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("$NAME/current/save.xml"))
                zip.closeEntry()
            }
            compose.runOnUiThread { Imports.start(compose.activity.applicationContext, Uri.fromFile(archive)) }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(string(R.string.profile_import_text))).fetchSemanticsNodes().isNotEmpty() }
            // The name's rules show only under a name that breaks them
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("bad/name")) }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(string(R.string.profile_import_name_rules))).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(isDialog()).tryPerformAccessibilityChecks()

            val dialog = compose.onNode(isDialog()).fetchSemanticsNode()
            val width = generateSequence(dialog) { it.parent }.last().size.width
            for (text in listOf(
                string(R.string.profile_import_title, NAME),
                string(R.string.profile_import_text),
                string(R.string.profile_import_name_rules),
                string(R.string.profile_import),
                string(R.string.profile_import_cancel),
            )) {
                val node = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNode()
                val layouts = ArrayList<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
                assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
                assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
                val bounds = node.boundsInRoot
                assertTrue("$locale: \"$text\" at $bounds leaves the $width px dialog", bounds.left >= 0f && bounds.right <= width)
            }

            val bounds = compose.onNode(hasText(string(R.string.profile_import_text)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val fromLeft = bounds.left
            val fromRight = width - bounds.right
            if (locale == AR_XB) {
                assertTrue("$locale: the dialog's text at $bounds is not on the right of its $width px window", fromRight <= fromLeft)
            } else {
                assertTrue("$locale: the dialog's text at $bounds is not on the left of its $width px window", fromLeft <= fromRight)
            }

            compose.onNode(hasText(string(R.string.profile_import_cancel)) and hasAnyAncestor(isDialog())).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        } finally {
            archive.delete()
        }
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun english(@StringRes id: Int): String {
        val config = Configuration(compose.activity.resources.configuration)
        config.setLocales(LocaleList(Locale.ENGLISH))
        return compose.activity.createConfigurationContext(config).getString(id)
    }

    companion object {
        private const val AR_XB = "ar-XB"
        private const val NAME = "Game"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", AR_XB)
    }
}
