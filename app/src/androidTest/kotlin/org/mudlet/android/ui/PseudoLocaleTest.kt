package org.mudlet.android.ui

import android.Manifest
import android.app.LocaleManager
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import java.util.Locale

/**
 * The app in the debug build's pseudo-locales (docs/translations.md, "Pseudo-locales"): en-XA, whose accented text
 * runs longer than the English, and ar-XB, right to left. On the profile list and on the game screen, with the
 * connection bar's longer states and the password field, no text is cut off, overflows its box or leaves the screen,
 * and in ar-XB the screens are mirrored. The app's language is set as the system's per-app language setting sets it,
 * which Android 13 brought.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class PseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val rtl = locale == AR_XB

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun enableChecks() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
    }

    @Test
    fun everyScreenFitsItsTextAndFollowsTheLayoutDirection() {
        // The pseudo-locale is in force: its text is not the English
        assertNotEquals("$locale's title", english(R.string.profiles_title), string(R.string.profiles_title))

        compose.onNodeWithText(string(R.string.profiles_title)).assertIsDisplayed()
        assertTextFits("the profile list")
        assertAtStart("the profile list's text", hasText(string(R.string.profiles_empty)))
        assertAtStart("the profile list's button", hasText(string(R.string.profiles_open_game)) and hasClickAction())

        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        activate(hasText(string(R.string.profiles_open_game)))
        waitFor(hasText(string(R.string.game_title)))
        assertTextFits("the game screen")
        assertAtStart("the Back button", hasContentDescription(string(R.string.navigate_back)))

        session.status("connection", "connecting $ADDRESS")
        waitFor(hasText(string(R.string.connection_connecting_to, ADDRESS)))
        assertTextFits("the connection bar, connecting")
        session.status("connection", "error: $REASON")
        waitFor(hasText(string(R.string.connection_error, REASON)))
        assertTextFits("the connection bar, failed")

        session.hiddenInput(true)
        waitFor(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertTextFits("the password field")
        session.hiddenInput(false)

        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.profiles_title)))
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun english(@StringRes id: Int): String {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocales(LocaleList(Locale.US)) }
        return compose.activity.createConfigurationContext(config).getString(id)
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /**
     * Every text on the screen is laid out whole within its box (no ellipsis, nothing clipped at its edge or past its
     * last line) and within the screen's width, after the accessibility checks.
     */
    private fun assertTextFits(where: String) {
        compose.onRoot().tryPerformAccessibilityChecks()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val width = root.size.width
        val problems = ArrayList<String>()
        var checked = 0
        fun visit(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text
            val layouts = ArrayList<TextLayoutResult>()
            if (!text.isNullOrEmpty()) node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            checked += layouts.size
            for (layout in layouts) {
                if (layout.hasVisualOverflow) problems += "\"$text\" does not fit its ${layout.size} box"
                if ((0 until layout.lineCount).any { layout.isLineEllipsized(it) }) problems += "\"$text\" is cut short"
            }
            val bounds = node.boundsInRoot
            if (layouts.isNotEmpty() && (bounds.left < -SLACK_PX || bounds.right > width + SLACK_PX)) {
                problems += "\"$text\" at $bounds is outside the screen's width $width"
            }
            node.children.forEach(::visit)
        }
        visit(root)
        // A screen with no text laid out would pass without being checked
        assertTrue("$locale, $where: no text on the screen gave its layout", checked > 0)
        assertTrue("$locale, $where: ${problems.joinToString("; ")}", problems.isEmpty())
    }

    /** The node is nearer the start of a line than its end: the left in en-XA, the right in ar-XB. */
    private fun assertAtStart(what: String, matcher: SemanticsMatcher) {
        val bounds = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        val width = compose.onRoot().fetchSemanticsNode().size.width
        val fromLeft = bounds.left
        val fromRight = width - bounds.right
        val side = if (rtl) "right" else "left"
        assertTrue("$locale: $what at $bounds is not on the $side of the $width px screen", if (rtl) fromRight < fromLeft else fromLeft < fromRight)
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private const val EN_XA = "en-XA"
        private const val AR_XB = "ar-XB"
        private const val ADDRESS = "mud.example.org:4000"
        private const val REASON = "Connection refused by the remote host"

        // Rounding of the bounds to pixels
        private const val SLACK_PX = 1f

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        private const val TIMEOUT_MS = 240_000L
        private const val WAIT_MS = 10_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf(EN_XA, AR_XB)
    }
}

/**
 * The app's language set to [tag], as the system's per-app language setting does, before the Activity starts, and the
 * system's language again afterwards. The system hands the process each change asynchronously, so both wait for it:
 * an Activity started before it arrives would be recreated in the middle of a test.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AppLocaleRule(private val tag: String) : ExternalResource() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val manager = context.getSystemService(LocaleManager::class.java)
    private lateinit var before: LocaleList

    override fun before() {
        before = context.resources.configuration.locales
        manager.applicationLocales = LocaleList.forLanguageTags(tag)
        await("the app's language to become $tag") { context.resources.configuration.locales[0].toLanguageTag() == tag }
    }

    override fun after() {
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        await("the app's language to be ${before.toLanguageTags()} again") { context.resources.configuration.locales == before }
    }

    private fun await(what: String, done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (!done()) {
            check(SystemClock.uptimeMillis() < deadline) { "waited $WAIT_MS ms for $what: ${context.resources.configuration.locales}" }
            Thread.sleep(50)
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
