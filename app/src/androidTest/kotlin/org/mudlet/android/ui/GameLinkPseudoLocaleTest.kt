package org.mudlet.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R

/**
 * What a telnet:// link shows (GameLinkTest) in the debug build's pseudo-locales, as PseudoLocaleTest checks the
 * screens: the question, the message about a broken link and the note in the editor it fills in, none cut short.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class GameLinkPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(GameLinkTest.TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private lateinit var engine: FakeEngine.Session

    @Before
    fun attach() {
        compose.enableAccessibilityChecksOfWhatShows()
        engine = fake.engine.nextSession()
        engine.profiles(listOf(GameLinkTest.ALPHA), engine.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GameLinkTest.ALPHA) }
    }

    @Test
    fun theQuestionFits() {
        openLink("telnet://a.example")
        val question = string(R.string.game_link_question_profile, "a.example:23", GameLinkTest.ALPHA.name)
        assertFits(listOf(string(R.string.game_link_title), question, string(R.string.game_link_connect), string(R.string.game_link_cancel)))
        GameLinkTest.activate(compose, GameLinkTest.inDialog(string(R.string.game_link_cancel)))
    }

    @Test
    fun theMessageAboutABrokenLinkFits() {
        openLink("telnet://a.example:0")
        assertFits(listOf(string(R.string.game_link_refused_title), string(R.string.game_link_refused_text), string(R.string.game_link_refused_close)))
        GameLinkTest.activate(compose, GameLinkTest.inDialog(string(R.string.game_link_refused_close)))
    }

    @Test
    fun theEditorsNoteFits() {
        openLink("telnet://new.example")
        assertFits(listOf(string(R.string.game_link_check)), inDialog = false)
    }

    private fun assertFits(texts: List<String>, inDialog: Boolean = true) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(texts.first())).fetchSemanticsNodes().isNotEmpty() }
        if (inDialog) compose.onNode(isDialog()).tryPerformAccessibilityChecks()
        for (text in texts) {
            val layouts = ArrayList<TextLayoutResult>()
            val node = if (inDialog) GameLinkTest.inDialog(text) else hasText(text)
            compose.onNode(node, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
        }
    }

    private fun openLink(link: String) {
        val context = compose.activity.applicationContext
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    companion object {
        private const val WAIT_MS = 10_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
