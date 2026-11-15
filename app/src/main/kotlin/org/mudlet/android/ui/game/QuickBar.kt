package org.mudlet.android.ui.game

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mudlet.android.R

/** The quick bar's test tag. */
const val QUICK_BAR_TAG = "quickBar"

/**
 * The row below the input line: the History button, which [onHistory] opens the command history with, then a button
 * for each of [pinned], which [onSend] sends at once. It scrolls sideways when the buttons do not fit.
 */
@Composable
fun QuickBar(pinned: List<String>, onHistory: () -> Unit, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.testTag(QUICK_BAR_TAG).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onHistory, Modifier.heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_history), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.history_open))
        }
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
