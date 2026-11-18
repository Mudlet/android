package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
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
import org.mudlet.android.protocol.Profile

/**
 * The search bar (ConsoleSearchTest) in the debug build's pseudo-locales, as PseudoLocaleTest checks the screens: what
 * it says, the count of matches and that there is no more, is not cut short.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class SearchPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private val game = Profile("search pseudo ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
    private val said = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    @Before
    fun openTheGame() {
        compose.enableAccessibilityChecksOfWhatShows()
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        val session = fake.engine.nextSession()
        session.clear()
        LINES.forEachIndexed { i, line -> session.line(i + 1L, line) }
        session.synced(LINES.size.toLong())
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasText(LINES.last()))
    }

    @Test
    fun whatTheBarSaysFits() {
        compose.pickFromGameMenu(string(R.string.console_search))
        val field = hasSetTextAction() and hasText(string(R.string.console_search_field))
        waitFor(field)
        compose.onNode(field).performTextInput("rat")
        assertFits(compose.activity.resources.getQuantityString(R.plurals.console_search_count, 2, 2))
        repeat(LINES.size) { activate(hasContentDescription(string(R.string.console_search_older))) }
        assertFits(string(R.string.console_search_none))
    }

    private fun assertFits(text: String) {
        val node = said and hasText(text)
        waitFor(node)
        checkAccessibility()
        val layouts = ArrayList<TextLayoutResult>()
        compose.onNode(node).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
        assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    }

    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        checkAccessibility()
        compose.onNode(node).performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private const val INSTANCE = "fake-search-pseudo"
        private val LINES = listOf("You hit the rat.", "A bat bites you.", "The rat bites you.")
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
