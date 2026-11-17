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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
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
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.concealLinks
import org.mudlet.android.protocol.linkRuns
import org.mudlet.android.protocol.linkSpans
import org.mudlet.android.protocol.matchStarts
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.ConsoleLinksClient
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
 * Each line is one node for TalkBack, whose text is the line's without its styles. Its [links] are followed as
 * [ConsoleLine] offers them, each asked about once its line is shown, and again after the engine says it changed, and
 * drawn as the engine last said it looks: visited or chosen in its colours, which TalkBack's actions also say, and a
 * concealed one as blanks.
 *
 * A long press on a line, but on a link with a menu, which opens that, offers to copy or share the line as it reads,
 * and to hand its text to [onMakeTrigger], to make a trigger from; TalkBack has actions to copy it and make the trigger.
 *
 * Each match of [searchQuery] is drawn as desktop's search draws it, and the list goes to [searchFound], the number of
 * the line a search found, each time a search finds one.
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
    links: ConsoleLinksClient? = null,
    onMakeTrigger: ((String) -> Unit)? = null,
    searchQuery: String = "",
    searchFound: Long? = null,
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
    // Read once: the measure pass calls the key lambda after the lines may have changed, and a live size there gives
    // two items the tail's key
    val lineCount = lines.size
    val itemCount = lineCount + if (tail != null) 1 else 0
    val firstLine = linesAdded - lineCount

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

    val latestFirstLine by rememberUpdatedState(firstLine)
    LaunchedEffect(searchFound) {
        val index = (searchFound ?: return@LaunchedEffect) - latestFirstLine
        if (index !in 0 until lines.size) return@LaunchedEffect
        // Left first, or the next composition's follow would take the list back to the bottom
        if (following) seenUpTo = latestLinesAdded
        following = false
        listState.scrollToItem(index.toInt())
    }

    Box(modifier.background(colors.background).pinchToZoom({ latestTextSize }, { latestOnTextSize(it) })) {
        behindText()
        LazyColumn(
            Modifier.fillMaxSize().onSizeChanged { consoleSize = it },
            state = listState,
            contentPadding = PaddingValues(horizontal = CONSOLE_SIDE_PADDING),
        ) {
            // Each line's key is its number, so the lines in view stay put as the oldest are dropped
            items(itemCount, key = { i -> if (i < lineCount) firstLine + i else TAIL_KEY }) { i ->
                val line = if (i < lineCount) lines.getOrNull(i) ?: return@items else tail ?: return@items
                val spans = remember(line) { linkSpans(line.runs, line.text.length) }
                // A line from before a new engine instance names links that instance does not hold; the tail is its own
                val held = links?.takeIf { spans.isNotEmpty() && (i >= lineCount || firstLine + i >= it.firstLine) }
                // Read here, so a new answer draws the line again in the link's new look
                val answers = held?.let { l -> spans.map { l.info(it.link) } }.orEmpty()
                val text = remember(line, colors, answers, searchQuery) {
                    ansiText(held?.let { line.paintedAs(it::info) } ?: line, colors).withMatches(line.text, searchQuery)
                }
                val plain = remember(line, answers) { held?.let { concealLinks(line.text, spans, it::info) } ?: line.text }
                val lineLinks = held?.let { l ->
                    val changes = l.changes
                    LaunchedEffect(spans, changes) { for (span in spans) l.request(span.link) }
                    // A concealed link is blanks no tap or action reaches
                    LineLinks(spans.filter { l.info(it.link)?.concealed != true }, l::info, states = true, follow = l::follow)
                }
                // The app's own markers are not the game's text: no trigger sees them
                val makeTrigger = remember(line, onMakeTrigger) { onMakeTrigger?.takeUnless { line.marker }?.let { make -> { make(line.text) } } }
                ConsoleLine(text, plain, style, Modifier.fillMaxWidth(), color = colors.foreground, links = lineLinks, onMakeTrigger = makeTrigger, copyable = true)
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

// The text drawn for [plain] with each match of [query] in it as desktop's search draws one
private fun AnnotatedString.withMatches(plain: String, query: String): AnnotatedString {
    val starts = matchStarts(plain, query)
    if (starts.isEmpty()) return this
    return AnnotatedString.Builder(this).apply { for (start in starts) addStyle(SEARCH_MATCH, start, start + query.length) }.toAnnotatedString()
}

// The line with its links painted as the engine last said they are
private fun UiLine.paintedAs(info: (Int) -> EngineMessage.Link?): UiLine {
    val painted = linkRuns(runs, info)
    return if (painted === runs) this else UiLine(text, painted, prompt, marker)
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
 * [onTextSize]; [onPinchEnd] follows once every finger has lifted. Two fingers tapped twice, without moving, call
 * [onTwoFingerDoubleTap] instead. One finger is left to the list, to scroll, and to the links, to tap.
 */
internal fun Modifier.pinchToZoom(
    textSize: () -> Int,
    onTextSize: (Int) -> Unit,
    onPinchEnd: () -> Unit = {},
    onTwoFingerDoubleTap: (() -> Unit)? = null,
) = pointerInput(Unit) {
    var lastTwoFingerTapUp = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val downAt = HashMap<PointerId, Offset>()
        downAt[down.id] = down.position
        var start = 0
        var zoom = 1f
        var mostPressed = 1
        var moved = false
        var upTime = down.uptimeMillis
        while (true) {
            // Seen before the list, so a pinch never scrolls it
            val event = awaitPointerEvent(PointerEventPass.Initial)
            for (change in event.changes) {
                val from = downAt.getOrPut(change.id) { change.position }
                if ((change.position - from).getDistance() > viewConfiguration.touchSlop) moved = true
                upTime = maxOf(upTime, change.uptimeMillis)
            }
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            mostPressed = maxOf(mostPressed, pressed)
            if (pressed < 2) continue
            if (start == 0) start = textSize()
            zoom *= event.calculateZoom()
            onTextSize((start * zoom).roundToInt())
            event.changes.forEach { it.consume() }
        }
        if (start == 0) {
            lastTwoFingerTapUp = 0L
            return@awaitEachGesture
        }
        val tap = onTwoFingerDoubleTap != null && mostPressed == 2 && !moved && upTime - down.uptimeMillis <= viewConfiguration.longPressTimeoutMillis
        val doubleTap = tap && lastTwoFingerTapUp != 0L && down.uptimeMillis - lastTwoFingerTapUp <= viewConfiguration.doubleTapTimeoutMillis
        lastTwoFingerTapUp = if (tap && !doubleTap) upTime else 0L
        if (doubleTap) onTwoFingerDoubleTap?.invoke() else onPinchEnd()
    }
}
