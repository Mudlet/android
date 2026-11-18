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
 * [CutByTheBottom] and [UnderWhatFloats].
 */
internal fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.enableAccessibilityChecksOfWhatShows() {
    if (Build.VERSION.SDK_INT >= 34) {
        enableAccessibilityChecks(AccessibilityValidator().setRunChecksFromRootView(true).setSuppressingResultMatcher(anyOf(ScrolledOutOfSight, CutByTheBottom, UnderWhatFloats, DiagDump)))
    }
}

// DIAG (not for merging): the plain checks, with the dump of a result they fail on
internal fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.enableAccessibilityChecksWithDump() {
    if (Build.VERSION.SDK_INT >= 34) {
        enableAccessibilityChecks(AccessibilityValidator().setRunChecksFromRootView(true).setSuppressingResultMatcher(DiagDump))
    }
}

// DIAG (not for merging): never suppresses; throws the failing node, its ancestors and the window's every view instead
private object DiagDump : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        if (result.type != com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType.ERROR) return false
        val element = result.accessibilityHierarchyCheckResult.element ?: return false
        fun ViewHierarchyElement.line() = "$boundsInScreen cls=$className clickable=$isClickable scrollable=$isScrollable " +
            "visible=$isVisibleToUser important=$isImportantForAccessibility text=${text?.toString()?.take(40)} desc=${contentDescription?.toString()?.take(40)}"
        val dump = StringBuilder("DIAG ${result.sourceCheckClass.simpleName} on ${element.line()}\nANCESTORS\n")
        for (a in element.ancestors()) dump.append("  ").append(a.line()).append('\n')
        dump.append("WINDOW\n")
        for (v in element.window.allViews) dump.append("  ").append(v.line()).append('\n')
        throw AssertionError(dump.toString())
    }

    override fun describeTo(description: Description) {
        description.appendText("DIAG dump")
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
 * A missing label or a small touch target on something the bottom of its scrolling area cuts. Compose hands
 * accessibility only the strip of a node that shows, without the children that do not: the CI emulator's 640 px screen
 * cuts a row of the game sheet so, under its card's switches, a button 24 dp high. TalkBack scrolls a node into view
 * before it reads it.
 */
private object CutByTheBottom : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        if (result.sourceCheckClass != SpeakableTextPresentCheck::class.java && result.sourceCheckClass != TouchTargetSizeCheck::class.java) return false
        val element = result.accessibilityHierarchyCheckResult.element ?: return false
        val area = element.ancestors().firstOrNull { it.isScrollable == true }?.boundsInScreen ?: return false
        return abs(element.boundsInScreen.bottom - area.bottom) <= 1
    }

    override fun describeTo(description: Description) {
        description.appendText("a missing label or a small touch target on what the bottom of its scrolling area cuts")
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
