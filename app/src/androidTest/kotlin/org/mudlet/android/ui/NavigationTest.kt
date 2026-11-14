package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R

/**
 * The app starts on the profile list and reaches the game screen and the self-test (debug builds) and back, as a
 * TalkBack user would: each control found by the label TalkBack reads and activated through its accessibility click
 * action, with no gesture.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(FakeEngineRule())
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    @Before
    fun enableChecks() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
    }

    @Test
    fun startsOnTheProfileListAndReachesEveryScreenThroughLabelledControls() {
        assertOnScreen(R.string.profiles_title)

        activate(hasText(string(R.string.profiles_open_game)))
        assertOnScreen(R.string.game_title)
        activate(hasContentDescription(string(R.string.navigate_back)))
        assertOnScreen(R.string.profiles_title)

        activate(hasText(string(R.string.selftest_title)))
        compose.onNodeWithText(string(R.string.selftest_connect)).assertIsDisplayed()
        activate(hasContentDescription(string(R.string.navigate_back)))
        assertOnScreen(R.string.profiles_title)
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    private fun assertOnScreen(@StringRes title: Int) {
        compose.onNodeWithText(string(title)).assertIsDisplayed()
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
