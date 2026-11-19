package org.mudlet.android.ui.game

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.LineSelection
import org.mudlet.android.ui.copyText
import org.mudlet.android.ui.shareText

/** Keeps a selection of the main console's lines across a rotation; a switch of game keeps each game's own. */
internal val LineSelectionSaver = listSaver<LineSelection?, Long>(
    save = { if (it == null) emptyList() else listOf(it.anchor, it.extent) },
    restore = { saved -> if (saved.size == 2) LineSelection(saved[0], saved[1]) else null },
)

/**
 * The bar of a selection of whole lines of the main console, [selection]: how many of them are drawn, as [countOf]
 * says, Copy and Share of their text, as [textOf] gives it, and closing with [onClose], which copying or sharing also
 * does, as Android's own text selection ends. Text too long to hand over ([textOf] gives null) is not copied or shared,
 * and says so, leaving the selection to shrink. TalkBack says the count once each time the selection changes, and
 * reaches the bar before the lines. A selection none of whose lines is left closes.
 */
@Composable
internal fun LineSelectionBar(
    selection: State<LineSelection?>,
    countOf: (LineSelection) -> Int,
    textOf: (LineSelection) -> String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = selection.value ?: return
    val count = countOf(current)
    val countText = pluralStringResource(R.plurals.console_selection_count, count, count)
    LaunchedEffect(current) { Announcer.announce(countText, assertive = false) }
    LaunchedEffect(count == 0) { if (count == 0) onClose() }
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.app_name)
    val copied = stringResource(R.string.console_selection_copied)
    val shareTitle = stringResource(R.string.console_selection_share_title)
    val noShare = stringResource(R.string.console_selection_no_share)
    val tooLong = stringResource(R.string.console_selection_too_long)

    fun handOver(to: (String) -> Unit) {
        val text = textOf(current)
        if (text == null) {
            Toast.makeText(context, tooLong, Toast.LENGTH_LONG).show()
            return
        }
        to(text)
        onClose()
    }
    Surface(
        modifier.semantics {
            isTraversalGroup = true
            traversalIndex = -1f
        },
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(countText, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.console_selection_hint), style = MaterialTheme.typography.bodySmall)
            }
            TextButton({ handOver { copyText(context, clipLabel, it, copied) } }) { Text(stringResource(R.string.console_selection_copy)) }
            TextButton({ handOver { shareText(context, it, shareTitle, noShare) } }) { Text(stringResource(R.string.console_selection_share)) }
            IconButton(onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.console_selection_close)) }
        }
    }
}
