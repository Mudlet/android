package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.SpeakableTextPresentCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import org.hamcrest.BaseMatcher
import org.hamcrest.Description
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.BuildConfig
import org.mudlet.android.R
import org.mudlet.android.engine.R as EngineR

/**
 * The About screen as a TalkBack user reaches it: from the profile list's top bar, with the app's version and its
 * engine, and the licence list, whose Qt entry opens the full texts of the LGPL and the GPL it builds on; each control
 * found by its label and activated through its accessibility action, after the accessibility checks.
 */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(FakeEngineRule())
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    @Before
    fun enableChecks() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) {
            compose.enableAccessibilityChecks(AccessibilityValidator().setRunChecksFromRootView(true).setSuppressingResultMatcher(CutByTheList))
        }
    }

    /**
     * SpeakableTextPresentCheck on a row the list's edge cuts. Compose leaves out of the accessibility tree the texts
     * the list has scrolled out of view, so a row of which only a strip shows has nothing to read; TalkBack scrolls a
     * row it reaches fully into view first (docs/architecture.md's on-device checklist, item 17).
     */
    private object CutByTheList : BaseMatcher<Any>() {
        override fun matches(item: Any?): Boolean {
            val result = item as? AccessibilityViewCheckResult ?: return false
            if (result.sourceCheckClass != SpeakableTextPresentCheck::class.java) return false
            val element = result.element ?: return false
            val bounds = element.boundsInScreen
            var parent = element.parentView
            while (parent != null && parent.isScrollable != true) parent = parent.parentView
            val list = parent?.boundsInScreen ?: return false
            return bounds.top < list.top || bounds.bottom > list.bottom
        }

        override fun describeTo(description: Description) {
            description.appendText("a missing label on a row the list's edge cuts")
        }
    }

    @Test
    fun showsTheVersionsAndEveryLicenceTextOfAComponent() {
        compose.onNodeWithText(string(R.string.profiles_title)).assertIsDisplayed()
        activate(hasContentDescription(string(R.string.about_title)))

        compose.onNodeWithText(compose.activity.getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.about_engine_mudlet, string(EngineR.string.engine_version)))
            .assertIsDisplayed()

        // the licence list, where Qt, near its top, opens its licence screen with the texts of the LGPL and the GPL
        scrollTo(hasText("Qt") and hasClickAction())
        activate(hasText("Qt") and hasClickAction())
        for (licence in listOf("LGPL-3.0-only", "GPL-3.0-only")) {
            scrollTo(hasText(compose.activity.getString(R.string.licence_text_heading, licence)) and isHeading())
        }
        // the GPL's preamble, read from the APK's assets
        scrollTo(hasText("The GNU General Public License is a free, copyleft license", substring = true))
        backFromDeepInTheList()

        // back on the list, further down: its group titles are headings for TalkBack's headings navigation
        scrollTo(hasText(string(R.string.about_group_android)) and isHeading())
        backFromDeepInTheList()
        compose.onNodeWithText(string(R.string.profiles_title)).assertIsDisplayed()
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /**
     * Scrolls the screen's list to [node], waiting while the list is still read from the assets. Only "not found" or
     * "not displayed" (an AssertionError) is retried: a failed accessibility check, which the scroll runs first, fails
     * the test. The licence texts arrive after their headings show, and push a heading already scrolled to out of view.
     */
    private fun scrollTo(node: SemanticsMatcher) {
        compose.waitUntil(TIMEOUT_MS / 4) {
            try {
                compose.onNode(hasScrollAction()).performScrollToNode(node)
                compose.onNode(node).assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }

    /**
     * Back from far down a list. The accessibility checks are not run here: on the API 36 emulator,
     * SpeakableTextPresentCheck reports a one-line element at the list's top edge with no label once the list is
     * scrolled far, which no item of these lists lacks (docs/architecture.md's on-device checklist, item 17, has
     * TalkBack read them). The Back button is still found by its label; the system Back (the activity's back dispatcher) takes it.
     */
    private fun backFromDeepInTheList() {
        compose.onNode(hasContentDescription(string(R.string.navigate_back)) and hasClickAction()).assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        try {
            compose.onRoot().tryPerformAccessibilityChecks()
        } catch (e: Throwable) {
            throw AssertionError("${e.message}\nOn the screen:\n${screen()}", e)
        }
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    // The nodes in the screen's bottom tenth, where the checks have flagged a row, merged as TalkBack and the checks
    // see them, then as composed; a long message's end can be cut off
    private fun screen(): String = listOf(false, true).joinToString("\n") { unmerged ->
        runCatching {
            val bottom = compose.onRoot().fetchSemanticsNode().boundsInWindow.bottom * 0.9f
            compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = unmerged).fetchSemanticsNodes()
                .filter { it.boundsInWindow.bottom > bottom }
                .joinToString("\n") { "${it.id} ${it.boundsInWindow} ${it.config}" }
        }.getOrElse { "$it" }
    }

    private companion object {
        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
