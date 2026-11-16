package org.mudlet.android.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performScrollToNode
import org.mudlet.android.ui.settings.SETTINGS_HUB_TAG

/** Scrolls the settings hub to [row]: it is a lazy list, which has no row for a category until it is near the screen. */
fun AndroidComposeTestRule<*, MainActivity>.scrollSettingsHubTo(row: SemanticsMatcher) {
    onNode(hasTestTag(SETTINGS_HUB_TAG)).performScrollToNode(row)
}
