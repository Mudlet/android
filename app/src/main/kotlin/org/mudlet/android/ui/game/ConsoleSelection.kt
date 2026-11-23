package org.mudlet.android.ui.game

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.LineSelection
import org.mudlet.android.ui.copyText
import org.mudlet.android.ui.shareText

/** The test tag of the bar of a selection of the main console's lines. */
const val LINE_SELECTION_BAR_TAG = "lineSelectionBar"

// The bar's text beside its buttons, rather than over them, from this wide
private val ONE_ROW_WIDTH = 560.dp

/** Keeps a selection of the main console's lines across a rotation; a switch of game keeps each game's own. */
internal val LineSelectionSaver = listSaver<LineSelection?, Long>(
    save = { if (it == null) emptyList() else listOf(it.anchor, it.extent) },
    restore = { saved -> if (saved.size == 2) LineSelection(saved[0], saved[1]) else null },
)

/**
 * Where the game screen draws the bar of a selection of the main console's lines ([LineSelectionBarHost]): over the
 * bottom of the game area, above the input line and the keyboard, where a thumb is, and over the script windows and
 * the map in the console's corner, which cover the console's top. The console puts the bar here while a selection is
 * open, and leaves room under its last line for as much of it as is over the console ([top]).
 */
@Stable
class LineSelectionBarSlot {
    internal var bar by mutableStateOf<(@Composable (Modifier) -> Unit)?>(null)

    /** The top in the window of the bar drawn, while one is. */
    internal var top by mutableStateOf<Float?>(null)
}

/** Draws the bar the console put in [slot], if any. */
@Composable
internal fun LineSelectionBarHost(slot: LineSelectionBarSlot, modifier: Modifier = Modifier) {
    slot.bar?.invoke(modifier.onGloballyPositioned { slot.top = it.boundsInWindow().top })
}

/**
 * The bar of a selection of whole lines of the main console, [selection]: how many of them are drawn, as [countOf]
 * says, how to select more and finish, Copy and Share of their text, as [textOf] gives it, and Done, which closes it
 * with [onClose], as copying or sharing also does, as Android's own text selection ends. Text too long to hand over
 * ([textOf] gives null) is not copied or shared, and says so, leaving the selection to shrink. TalkBack says the count
 * once each time the selection changes, and reaches the bar before the lines. A selection none of whose lines is left
 * closes. Narrower than [ONE_ROW_WIDTH], the buttons go under the text.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val texts = @Composable { m: Modifier ->
        Column(m.padding(vertical = 8.dp)) {
            Text(countText, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.console_selection_how), style = MaterialTheme.typography.bodySmall)
        }
    }
    val buttons = @Composable { m: Modifier ->
        FlowRow(m, horizontalArrangement = Arrangement.End) {
            TextButton({ handOver { copyText(context, clipLabel, it, copied) } }) { Text(stringResource(R.string.console_selection_copy)) }
            TextButton({ handOver { shareText(context, it, shareTitle, noShare) } }) { Text(stringResource(R.string.console_selection_share)) }
            TextButton({}) { Text(stringResource(R.string.console_selection_done), Modifier.width(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    Surface(
        modifier.testTag(LINE_SELECTION_BAR_TAG).semantics {
            isTraversalGroup = true
            traversalIndex = -1f
        },
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        BoxWithConstraints(Modifier.padding(start = 16.dp, end = 4.dp)) {
            if (maxWidth >= ONE_ROW_WIDTH) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    texts(Modifier.weight(1f))
                    buttons(Modifier)
                }
            } else {
                Column {
                    texts(Modifier)
                    buttons(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
