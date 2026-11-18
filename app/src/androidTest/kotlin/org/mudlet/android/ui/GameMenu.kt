package org.mudlet.android.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import org.mudlet.android.R

private const val MENU_WAIT_MS = 10_000L

/** Opens the game screen's title bar menu, as TalkBack's double tap does. */
fun AndroidComposeTestRule<*, MainActivity>.openGameMenu() = doubleTap(hasContentDescription(activity.getString(R.string.game_menu)))

/** Opens the game screen's title bar menu and picks [item] from it, as TalkBack's double taps do. */
fun AndroidComposeTestRule<*, MainActivity>.pickFromGameMenu(item: String) {
    openGameMenu()
    doubleTap(hasText(item))
}

// After the checks of every window's: the open menu is a window, and a root, of its own
private fun AndroidComposeTestRule<*, MainActivity>.doubleTap(label: SemanticsMatcher) {
    val node = label and hasClickAction()
    waitUntil(MENU_WAIT_MS) { onAllNodes(node).fetchSemanticsNodes().isNotEmpty() }
    val roots = onAllNodes(isRoot())
    for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
}
