package org.mudlet.android.ui

import android.content.res.Resources
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.SpeakableTextPresentCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import org.hamcrest.CoreMatchers.anyOf
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import org.junit.rules.TestRule
import kotlin.math.abs

/**
 * Turns on the accessibility checks before every action of a test, where they can run: they need API 34, so the API 36
 * emulator runs them and API 28 does not. Of a page that scrolls, they check what shows: see [ScrolledOutOfSight],
 * [UnderWhatFloats] and [OverWhatScrolled].
 */
internal fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.enableAccessibilityChecksOfWhatShows() {
    if (Build.VERSION.SDK_INT >= 34) {
        enableAccessibilityChecks(
            AccessibilityValidator().setRunChecksFromRootView(true).setSuppressingResultMatcher(anyOf(ScrolledOutOfSight, UnderWhatFloats, OverWhatScrolled)),
        )
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
        return element.ancestors().any { it.isScrollable == true && !it.shows(element) }
    }

    override fun describeTo(description: Description) {
        description.appendText("about what its scrolling area has scrolled out of sight")
    }
}

/**
 * A missing label or a small touch target on something that a notice or a floating button covers in part. Compose hands
 * accessibility only what is left uncovered of a node, without those of its children that are covered: a row of which
 * a strip shows between the two is then a button with no label, and a low one. A notice of a script's page shows again
 * over the library when Back leaves the page first, and the CI emulator's 640 px screen has a row of the library under
 * it and the New button.
 */
private object UnderWhatFloats : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        if (result.sourceCheckClass != SpeakableTextPresentCheck::class.java && result.sourceCheckClass != TouchTargetSizeCheck::class.java) return false
        val element = result.accessibilityHierarchyCheckResult.element ?: return false
        val above = element.ancestors().toList()
        val rest = element.boundsInScreen
        return element.window.allViews.any { view ->
            val over = view.boundsInScreen
            // What is left of the element ends where what floats begins
            val upOrDown = (over.top == rest.bottom || over.bottom == rest.top) && over.left < rest.right && rest.left < over.right
            val beside = (over.left == rest.right || over.right == rest.left) && over.top < rest.bottom && rest.top < over.bottom
            // It floats: it is over what the element is in, and not of it, where the next row of a list only meets a row
            (upOrDown || beside) && view !in above && above.any { it.shows(view) && it !in view.ancestors() }
        }
    }

    override fun describeTo(description: Description) {
        description.appendText("a missing label or a small touch target where a notice or a floating button covers part of it")
    }
}

/**
 * A small touch target just above a scrolling area that has scrolled. Compose hands accessibility the scrolled content
 * as reaching half a touch target above the area that clips it, and takes that strip off what lies there: a sheet's
 * 48 dp close button over its scrolled list is then 24 dp tall, though all of it shows and takes a touch.
 */
private object OverWhatScrolled : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        if (result.sourceCheckClass != TouchTargetSizeCheck::class.java) return false
        val element = result.accessibilityHierarchyCheckResult.element ?: return false
        val rest = element.boundsInScreen
        val half = Math.round(HALF_TOUCH_TARGET_DP * Resources.getSystem().displayMetrics.density)
        val above = element.ancestors().toList()
        return element.window.allViews.any { view ->
            val area = view.boundsInScreen
            view.isScrollable == true && view !in above && area.top - half == rest.bottom && area.left < rest.right && rest.left < area.right
        }
    }

    override fun describeTo(description: Description) {
        description.appendText("a small touch target where a scrolled area's content is taken to reach half a touch target above it")
    }
}

private const val HALF_TOUCH_TARGET_DP = 24

private fun ViewHierarchyElement.ancestors() = generateSequence(parentView) { it.parentView }

// Whether any of [other] is within this one's bounds
private fun ViewHierarchyElement.shows(other: ViewHierarchyElement): Boolean {
    val area = boundsInScreen
    val its = other.boundsInScreen
    return its.top < area.bottom && area.top < its.bottom && its.left < area.right && area.left < its.right
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
