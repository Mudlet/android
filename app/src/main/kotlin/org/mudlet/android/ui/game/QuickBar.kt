package org.mudlet.android.ui.game

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mudlet.android.R

/** The quick bar's test tag. */
const val QUICK_BAR_TAG = "quickBar"

/**
 * The row below the input line: [leading], fixed at its start, then a button for each of [pinned], which [onSend] sends
 * at once. Those scroll sideways when they do not fit, so that a gesture on [leading] never scrolls the row. With
 * nothing in it, the row takes no room.
 */
@Composable
fun QuickBar(
    pinned: List<String>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        if (pinned.isEmpty()) return@Row
        Row(
            Modifier.weight(1f).testTag(QUICK_BAR_TAG).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (command in pinned) {
                val label = stringResource(R.string.pinned_command, command)
                FilledTonalButton(
                    onClick = { onSend(command) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(command, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
