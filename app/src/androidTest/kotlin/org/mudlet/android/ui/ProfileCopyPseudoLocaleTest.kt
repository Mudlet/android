package org.mudlet.android.ui

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
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
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.protocol.Profile
import java.util.Locale

/**
 * The profile editor's Copy, and the dialog that names each of its two copies, in the debug build's pseudo-locales, as
 * PseudoLocaleTest checks the screens: none of the text is cut short or leaves its window, and in ar-XB the dialog's
 * starts on the right.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ProfileCopyPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private lateinit var passwords: PasswordStore

    @Before
    fun attach() {
        compose.enableAccessibilityChecksOfWhatShows()
        // The dialog's switch shows only for a profile with a password stored
        passwords = PasswordStore(compose.activity)
        assertTrue(passwords.set(NAME, "secret"))
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(Profile(NAME, "g.example", 23, false, "", false)), session.receive("listProfiles"))
    }

    @After
    fun forgetPassword() {
        passwords.forget(NAME)
    }

    @Test
    fun theCopysTextFits() {
        assertNotEquals("$locale's text", english(R.string.profile_copy_text), string(R.string.profile_copy_text))
        val edit = hasContentDescription(string(R.string.profile_edit, NAME)) and hasClickAction()
        waitFor(edit)
        compose.onNode(edit).performSemanticsAction(SemanticsActions.OnClick)
        val settingsButton = hasText(string(R.string.profile_copy_settings)) and hasClickAction()
        waitFor(settingsButton)
        compose.onNode(settingsButton).performScrollTo()
        for (text in listOf(string(R.string.profile_copy_text), string(R.string.profile_copy), string(R.string.profile_copy_settings))) {
            assertFits(text, inDialog = false)
        }

        for ((button, title, body) in listOf(
            Triple(R.string.profile_copy, R.string.profile_copy_whole_title, R.string.profile_copy_whole_text),
            Triple(R.string.profile_copy_settings, R.string.profile_copy_settings_title, R.string.profile_copy_settings_text),
        )) {
            compose.onNode(hasText(string(button)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
            waitFor(hasText(string(body)))
            // What is said of a name shows only under one that is taken
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(NAME)) }
            waitFor(hasText(string(R.string.profile_copy_taken, NAME)))
            compose.onNode(isDialog()).tryPerformAccessibilityChecks()

            for (text in listOf(
                string(title, NAME),
                string(body),
                string(R.string.profile_copy_taken, NAME),
                string(R.string.profile_copy_password),
                string(R.string.profile_copy),
                string(R.string.profile_copy_cancel),
            )) {
                assertFits(text, inDialog = true)
            }

            val node = compose.onNode(hasText(string(body)), useUnmergedTree = true).fetchSemanticsNode()
            val width = generateSequence(node) { it.parent }.last().size.width
            val bounds = node.boundsInRoot
            val fromLeft = bounds.left
            val fromRight = width - bounds.right
            if (locale == AR_XB) {
                assertTrue("$locale: the dialog's text at $bounds is not on the right of its $width px window", fromRight <= fromLeft)
            } else {
                assertTrue("$locale: the dialog's text at $bounds is not on the left of its $width px window", fromLeft <= fromRight)
            }

            compose.onNode(hasText(string(R.string.profile_copy_cancel)) and hasAnyAncestor(isDialog())).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        }
    }

    // Each text that reads [text], in the dialog or in the screen under it: the editor's heading and its first button
    // read the same
    private fun assertFits(text: String, inDialog: Boolean) {
        val where = if (inDialog) hasAnyAncestor(isDialog()) else !hasAnyAncestor(isDialog())
        val nodes = compose.onAllNodes(hasText(text) and where, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$locale: \"$text\" is not shown", nodes.isNotEmpty())
        for (node in nodes) {
            val layouts = ArrayList<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
            val width = generateSequence(node) { it.parent }.last().size.width
            val bounds = node.boundsInRoot
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
        private const val NAME = "Game"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", AR_XB)
    }
}
