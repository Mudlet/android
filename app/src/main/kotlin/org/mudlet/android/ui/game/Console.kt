package org.mudlet.android.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import org.mudlet.android.R
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.ansiText
import kotlin.math.roundToInt

private const val TAIL_KEY = "tail"
private val CONSOLE_SIDE_PADDING = 4.dp

// The keyboard and a pinch resize the console in many small steps: only where it comes to rest goes to the game
private const val SCREEN_SIZE_SETTLE_MS = 200L

// A run of characters, shaped as a line of game text is, rather than one on its own
private const val CELLS_MEASURED = 100

/**
 * The game's text: [lines], then [tail] (the prompt), in their colours and styles, at [textSizeSp] in [colors]. At the
 * bottom it follows new lines; scrolled up, it stays where it is and offers a button back to the bottom that counts the
 * lines that came meanwhile. [linesAdded] is the profile session's count, which numbers the lines.
 *
 * Each line is one node for TalkBack, whose text is the line's without its styles.
 *
 * A two-finger pinch hands [onTextSize] a new size. [onScreenSize] gets the columns and rows that fit, once they have
 * held for a moment. [behindText] draws between the background and the text: the map, when it is behind the text.
 */
@Composable
fun Console(
    lines: List<UiLine>,
    tail: UiLine?,
    linesAdded: Long,
    textSizeSp: Int,
    colors: ConsoleColors,
    onTextSize: (Int) -> Unit,
    onScreenSize: (columns: Int, rows: Int) -> Unit,
    modifier: Modifier = Modifier,
    behindText: @Composable BoxScope.() -> Unit = {},
) {
    val listState = rememberLazyListState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = remember(textSizeSp) { TextStyle(fontFamily = FontFamily.Monospace, fontSize = textSizeSp.sp) }
    val cell = remember(measurer, density, style) {
        val row = measurer.measure("M".repeat(CELLS_MEASURED), style, softWrap = false)
        CellSize(row.getLineRight(0) / CELLS_MEASURED, row.size.height)
    }
    // Each side rounded on its own, as contentPadding does
    val sidePaddingPx = with(density) { CONSOLE_SIDE_PADDING.roundToPx() * 2 }
    var consoleSize by remember { mutableStateOf(IntSize.Zero) }
    var following by rememberSaveable { mutableStateOf(true) }
    val latestTextSize by rememberUpdatedState(textSizeSp)
    val latestOnTextSize by rememberUpdatedState(onTextSize)
    val latestOnScreenSize by rememberUpdatedState(onScreenSize)

    LaunchedEffect(cell, sidePaddingPx) {
        snapshotFlow { cell.fit(consoleSize, sidePaddingPx) }.filterNotNull().distinctUntilChanged().collectLatest { (columns, rows) ->
            delay(SCREEN_SIZE_SETTLE_MS)
            latestOnScreenSize(columns, rows)
        }
    }

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

    Box(modifier.background(colors.background).pinchToZoom({ latestTextSize }, { latestOnTextSize(it) })) {
        behindText()
        LazyColumn(
            Modifier.fillMaxSize().onSizeChanged { consoleSize = it },
            state = listState,
            contentPadding = PaddingValues(horizontal = CONSOLE_SIDE_PADDING),
        ) {
            // Each line's key is its number, so the lines in view stay put as the oldest are dropped
            items(itemCount, key = { i -> if (i < lines.size) firstLine + i else TAIL_KEY }) { i ->
                val line = if (i < lines.size) lines[i] else tail ?: return@items
                val text = remember(line, colors) { ansiText(line, colors) }
                ConsoleLine(text, line.text, style, Modifier.fillMaxWidth(), color = colors.foreground)
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

/** One character of the console's monospaced font: its advance, which may be a fraction of a pixel, and line height. */
private data class CellSize(val width: Float, val height: Int) {
    /** The columns and rows that fit in [console], [sidePaddingPx] narrower; null before the console is laid out. */
    fun fit(console: IntSize, sidePaddingPx: Int): Pair<Int, Int>? {
        if (console == IntSize.Zero || width <= 0f || height <= 0) return null
        val columns = ((console.width - sidePaddingPx) / width).toInt()
        return columns.coerceIn(1, 65535) to (console.height / height).coerceIn(1, 65535)
    }
}

/**
 * Two fingers spreading or closing scale [textSize] by how far they moved since they came down, and hand the result to
 * [onTextSize]. One finger is left to the list, to scroll.
 */
private fun Modifier.pinchToZoom(textSize: () -> Int, onTextSize: (Int) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var start = 0
        var zoom = 1f
        while (true) {
            // Seen before the list, so a pinch never scrolls it
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            if (pressed < 2) continue
            if (start == 0) start = textSize()
            zoom *= event.calculateZoom()
            onTextSize((start * zoom).roundToInt())
            event.changes.forEach { it.consume() }
        }
    }
}
