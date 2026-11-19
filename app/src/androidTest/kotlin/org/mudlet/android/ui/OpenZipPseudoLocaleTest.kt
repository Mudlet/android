package org.mudlet.android.ui

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.content.FileProvider
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
import org.mudlet.android.data.MapFileStaging
import org.mudlet.android.ui.profiles.Imports
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The dialogs of a .zip another app opens with Mudlet in the debug build's pseudo-locales, as PseudoLocaleTest checks
 * the screens: none of their text is cut short or leaves the dialog, and in ar-XB it starts on the right.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class OpenZipPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private val archive get() = File(MapFileStaging.folder(compose.activity.cacheDir), "pseudo-locale-open.zip")

    @Before
    fun attach() {
        compose.enableAccessibilityChecksOfWhatShows()
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(emptyList(), session.receive("listProfiles"))
    }

    @After
    fun cleanUp() {
        compose.runOnUiThread {
            Imports.dismiss()
            Imports.forgetEnded()
        }
        archive.delete()
    }

    @Test
    fun whatTheFileHoldsFits() {
        assertNotEquals("$locale's text", english(R.string.profile_import_text), string(R.string.profile_import_text))
        open("$NAME/current/save.xml")
        val details = compose.activity.resources.getQuantityString(R.plurals.profile_file_contents, 1, 1, Formatter.formatShortFileSize(compose.activity, SAVE.length.toLong()))
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(details)).fetchSemanticsNodes().isNotEmpty() }
        assertFits(listOf(string(R.string.profile_import_title, NAME), string(R.string.profile_import_text), details, string(R.string.profile_import), string(R.string.profile_import_cancel)))
        assertStartsOnTheReadingSide(string(R.string.profile_import_text))
        compose.onNode(hasText(string(R.string.profile_import_cancel)) and hasAnyAncestor(isDialog())).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aRefusalFits() {
        open("$NAME/map/map.dat")
        val refusal = string(R.string.profile_import_no_save)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(refusal)).fetchSemanticsNodes().isNotEmpty() }
        assertFits(listOf(string(R.string.profile_file_title), refusal, string(R.string.profile_file_ok)))
        assertStartsOnTheReadingSide(refusal)
        compose.onNode(hasText(string(R.string.profile_file_ok)) and hasAnyAncestor(isDialog())).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    // Shared with the app as a file manager shares one, through the manifest's filter
    private fun open(path: String) {
        archive.parentFile!!.mkdirs()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(SAVE.toByteArray())
            zip.closeEntry()
        }
        val uri: Uri = FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.mapfiles", archive)
        compose.activity.applicationContext.startActivity(
            Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).setPackage(compose.activity.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun assertFits(texts: List<String>) {
        compose.onNode(isDialog()).tryPerformAccessibilityChecks()
        val width = dialogWindowWidth()
        for (text in texts) {
            val node = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNode()
            val layouts = ArrayList<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
            val bounds = node.boundsInRoot
            assertTrue("$locale: \"$text\" at $bounds leaves the $width px dialog", bounds.left >= 0f && bounds.right <= width)
        }
    }

    private fun assertStartsOnTheReadingSide(text: String) {
        val width = dialogWindowWidth()
        val bounds = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val fromLeft = bounds.left
        val fromRight = width - bounds.right
        if (locale == AR_XB) {
            assertTrue("$locale: \"$text\" at $bounds is not on the right of its $width px window", fromRight <= fromLeft)
        } else {
            assertTrue("$locale: \"$text\" at $bounds is not on the left of its $width px window", fromLeft <= fromRight)
        }
    }

    private fun dialogWindowWidth() = generateSequence(compose.onNode(isDialog()).fetchSemanticsNode()) { it.parent }.last().size.width

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun english(@StringRes id: Int): String {
        val config = Configuration(compose.activity.resources.configuration)
        config.setLocales(LocaleList(Locale.ENGLISH))
        return compose.activity.createConfigurationContext(config).getString(id)
    }

    companion object {
        private const val AR_XB = "ar-XB"
        private const val NAME = "Game"
        private const val SAVE = "<MudletPackage/>"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", AR_XB)
    }
}
