package org.mudlet.android.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mudlet.android.R
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.CONSOLE_FOREGROUND
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.ansiText

private const val TAIL_KEY = "tail"
private val CONSOLE_SIDE_PADDING = 4.dp
private val consoleStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)

/**
 * The game's text: [lines], then [tail] (the prompt), in their colours and styles. At the bottom it follows new lines;
 * scrolled up, it stays where it is and offers a button back to the bottom that counts the lines that came meanwhile.
 * [linesAdded] is EngineClient's count, which numbers the lines. [onScreenSize] gets the columns and rows that fit.
 */
@Composable
fun Console(lines: List<UiLine>, tail: UiLine?, linesAdded: Long, onScreenSize: (columns: Int, rows: Int) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cell = remember(measurer, density) { measurer.measure("M", consoleStyle).size }
    val sidePaddingPx = with(density) { (CONSOLE_SIDE_PADDING * 2).roundToPx() }
    var following by rememberSaveable { mutableStateOf(true) }

    // linesAdded when the player scrolled away from the bottom: the lines since then are new to them
    var seenUpTo by rememberSaveable { mutableLongStateOf(linesAdded) }
    val latestLinesAdded by rememberUpdatedState(linesAdded)
    val itemCount = lines.size + if (tail != null) 1 else 0
    val firstLine = linesAdded - lines.size

    // Applied before the layout that shows the new lines, so that layout already ends at the bottom and never looks
    // like a scroll away from it
    SideEffect {
        if (following && itemCount > 0) listState.requestScrollToItem(itemCount - 1)
    }

    LaunchedEffect(listState) {
        var viewport = IntSize.Zero
        snapshotFlow { listState.layoutInfo.viewportSize to listState.canScrollForward }.collect { (size, canScrollForward) ->
            // Not laid out yet: canScrollForward is false whatever the position restored after a rotation
            if (size == IntSize.Zero) return@collect
            if (size == viewport) {
                // A scroll reaching or leaving the bottom, or the lines coming to fit. New lines do not change it:
                // followed, the bottom stays in view; not followed, there was more below already
                if (following && canScrollForward) seenUpTo = latestLinesAdded
                following = !canScrollForward
            } else {
                // The keyboard came or went, or the window changed: the list kept its top line, so bring back the bottom
                viewport = size
                val last = listState.layoutInfo.totalItemsCount - 1
                if (following && last >= 0) listState.requestScrollToItem(last)
            }
        }
    }

    Box(modifier.background(CONSOLE_BACKGROUND)) {
        LazyColumn(
            Modifier.fillMaxSize().onSizeChanged {
                if (cell.width > 0 && cell.height > 0) {
                    onScreenSize(((it.width - sidePaddingPx) / cell.width).coerceIn(1, 65535), (it.height / cell.height).coerceIn(1, 65535))
                }
            },
            state = listState,
            contentPadding = PaddingValues(horizontal = CONSOLE_SIDE_PADDING),
        ) {
            // Each line's key is its number, so the lines in view stay put as the oldest are dropped
            items(itemCount, key = { i -> if (i < lines.size) firstLine + i else TAIL_KEY }) { i ->
                val line = if (i < lines.size) lines[i] else tail ?: return@items
                val text = remember(line) { ansiText(line) }
                Text(text, Modifier.fillMaxWidth(), color = CONSOLE_FOREGROUND, style = consoleStyle)
            }
        }
        if (!following) {
            val newLines = (linesAdded - seenUpTo).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            ExtendedFloatingActionButton({ following = true }, Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (newLines > 0) {
                        pluralStringResource(R.plurals.console_jump_new_lines, newLines, newLines)
                    } else {
                        stringResource(R.string.console_jump_to_bottom)
                    },
                )
            }
        }
    }
}
