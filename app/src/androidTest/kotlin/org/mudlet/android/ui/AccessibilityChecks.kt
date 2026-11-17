package org.mudlet.android.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import org.junit.rules.TestRule
import kotlin.math.abs

/**
 * Turns on the accessibility checks before every action of a test, where they can run: they need API 34, so the API 36
 * emulator runs them and API 28 does not. Of a page that scrolls, they check what shows: see [ScrolledOutOfSight].
 */
internal fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.enableAccessibilityChecksOfWhatShows() {
    if (Build.VERSION.SDK_INT >= 34) {
        enableAccessibilityChecks(AccessibilityValidator().setRunChecksFromRootView(true).setSuppressingResultMatcher(ScrolledOutOfSight))
    }
}

/**
 * A result about something that lies wholly outside the scrolling area it is in. Compose hands accessibility a node
 * that ends within half a touch target of the area that clips it, without those of its children that are further out:
 * a text field with nothing typed, scrolled to just above its page, is then an edit box with no label, though none of
 * it shows.
 */
private object ScrolledOutOfSight : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        val element = result.accessibilityHierarchyCheckResult.element ?: return false
        return generateSequence(element.parentView) { it.parentView }.any { it.isScrollable == true && !it.shows(element) }
    }

    private fun ViewHierarchyElement.shows(other: ViewHierarchyElement): Boolean {
        val area = boundsInScreen
        val its = other.boundsInScreen
        return its.top < area.bottom && area.top < its.bottom && its.left < area.right && area.left < its.right
    }

    override fun describeTo(description: Description) {
        description.appendText("about what its scrolling area has scrolled out of sight")
    }
}

/**
 * Whether the keyboard has come and [onIt], what the screen keeps right over it, has stopped moving with it. An
 * emulator set up with keys of its own shows no keyboard: false then, once [timeoutMs] have passed.
 */
internal fun AndroidComposeTestRule<*, *>.keyboardHasCome(onIt: SemanticsMatcher, timeoutMs: Long): Boolean {
    val view = activity.window.decorView
    return try {
        waitUntil(timeoutMs) {
            val insets = runOnUiThread<WindowInsetsCompat?> { ViewCompat.getRootWindowInsets(view) }
            val keyboard = insets?.takeIf { it.isVisible(WindowInsetsCompat.Type.ime()) }?.getInsets(WindowInsetsCompat.Type.ime())?.bottom
            keyboard != null && onAllNodes(onIt).fetchSemanticsNodes().any { abs(it.boundsInRoot.bottom - (view.height - keyboard)) <= 1f }
        }
        true
    } catch (_: ComposeTimeoutException) {
        false
    }
}
