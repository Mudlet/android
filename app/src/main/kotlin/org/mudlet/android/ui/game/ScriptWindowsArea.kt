package org.mudlet.android.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.mudlet.android.R
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.PlacedWindow
import org.mudlet.android.protocol.PxRect
import org.mudlet.android.protocol.RUN_RGB
import org.mudlet.android.protocol.ScriptWindow
import org.mudlet.android.protocol.ScriptWindows
import org.mudlet.android.protocol.WindowKey
import org.mudlet.android.protocol.WindowKind
import org.mudlet.android.protocol.WindowLayout
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.CONSOLE_FOREGROUND
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ScriptWindowsClient
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.ansiText
import kotlin.math.roundToInt

private const val CHAR_SAMPLE = "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM"

// Script windows' text is never drawn smaller than this; a window that would need it smaller scrolls sideways
private const val MIN_WINDOW_FONT_SP = 9f
private const val MAX_WINDOW_FONT_SP = 28f

// The row with a dock's or a page's title and buttons, and the window switcher's row of tabs
private val HEADER_HEIGHT = 48.dp
private val SWITCHER_HEIGHT = 56.dp

// Script windows come after the console, the input line and the screen's buttons, which keep the default of 0
private const val WINDOWS_TRAVERSAL_INDEX = 1f

private val WindowKeySaver = listSaver<WindowKey?, String>(
    save = { if (it == null) emptyList() else listOf(it.name, it.kind.wire) },
    restore = { saved -> WindowKind.fromWire(saved.getOrNull(1).orEmpty())?.let { WindowKey(saved[0], it) } },
)

/**
 * The width of a character of the main console in pixels, and of a script window's glyph per sp of its size; the main
 * console's text size, which sets the scale Mudlet's windows are drawn at.
 */
private class ConsoleMetrics(val charWidth: Float, val glyphWidthPerSp: Float, val fontSp: Float)

/**
 * The game area: the main console, drawn by [content] with the modifier it is given, and around and over it the
 * windows the profile's scripts made, placed as [WindowLayout] says; below it, while there are any, the window
 * switcher's tabs, which open a window in a pane over the console. It gives Mudlet's main window the size that makes
 * Mudlet's grid the console's, at the console's [textSizeSp], and tells the engine where it put the user windows.
 */
@Composable
fun ScriptWindowsArea(textSizeSp: Int, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val client = ScriptWindowsClient
    val windows = client.state
    val metrics = rememberConsoleMetrics(textSizeSp)
    val density = LocalDensity.current
    val headerHeight = with(density) { HEADER_HEIGHT.toPx() }
    val switcherHeight = with(density) { SWITCHER_HEIGHT.toPx() }
    var selected by rememberSaveable(stateSaver = WindowKeySaver) { mutableStateOf<WindowKey?>(null) }

    BoxWithConstraints(modifier.background(CONSOLE_BACKGROUND).clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val fitted = WindowLayout.fitted(client.layoutMode, maxWidth.value)
        val layout = remember(windows, w, h, fitted, metrics, headerHeight, switcherHeight, selected) {
            val full = WindowLayout.of(windows, WindowLayout.Area(w, h, fitted, metrics.charWidth, headerHeight, selected))
            // Which windows are pages does not depend on the height, so the switcher's row can be taken from it
            if (full.pages.isEmpty()) {
                full
            } else {
                val rest = (h - switcherHeight).coerceAtLeast(0f)
                WindowLayout.of(windows, WindowLayout.Area(w, rest, fitted, metrics.charWidth, headerHeight, selected))
            }
        }
        // A new engine instance has none of what the last was told
        val attaches = EngineClient.attachCount
        LaunchedEffect(layout.mainWindowSize, attaches) { layout.mainWindowSize?.let(client::setMainWindowSize) }
        LaunchedEffect(layout.placements, attaches) {
            for ((name, placement) in layout.placements) client.placeUserWindow(name, placement)
        }
        // A page the layout no longer offers can no longer be open
        LaunchedEffect(layout.pages) { if (layout.pages.none { it.key == selected }) selected = null }

        val page = layout.paneWindows.firstOrNull()?.window
        val pane = layout.pane?.takeIf { page != null }

        // What the pane covers is not read either
        fun Modifier.coveredBy(rect: PxRect): Modifier = if (pane != null && pane.contains(rect)) clearAndSetSemantics {} else this

        content(Modifier.placeAt(layout.console, density).coveredBy(layout.console))
        for (placed in layout.windows) {
            key(placed.window.key) { PlacedWindowView(placed, windows, metrics, density, Modifier.coveredBy(placed.rect)) }
        }
        for (header in layout.dockHeaders) {
            key(header.window.key) { WindowHeader(header.window, onBack = null, Modifier.placeAt(header.rect, density)) }
        }
        if (pane != null && page != null) {
            BackHandler { selected = null }
            // Touches on the pane stay off what it covers
            Box(Modifier.placeAt(pane, density).background(CONSOLE_BACKGROUND).pointerInput(Unit) { consumeAll() })
            WindowHeader(page, onBack = { selected = null }, Modifier.placeAt(pane.copy(h = headerHeight), density))
            for (placed in layout.paneWindows) {
                key(placed.window.key) { PlacedWindowView(placed, windows, metrics, density) }
            }
        }
        if (layout.pages.isNotEmpty()) {
            WindowSwitcher(
                layout.pages,
                selected,
                onSelect = { selected = if (selected == it) null else it },
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(SWITCHER_HEIGHT),
            )
        }
    }
}

private suspend fun PointerInputScope.consumeAll() {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

private fun PxRect.contains(other: PxRect): Boolean = other.x >= x && other.y >= y && other.right <= right && other.bottom <= bottom

@Composable
private fun rememberConsoleMetrics(textSizeSp: Int): ConsoleMetrics {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(fontFamily = FontFamily.Monospace, fontSize = textSizeSp.sp))
    return remember(measurer, style) {
        val chars = measurer.measure(CHAR_SAMPLE, style).size.width / CHAR_SAMPLE.length.toFloat()
        val glyphs = measurer.measure(CHAR_SAMPLE, style.merge(TextStyle(letterSpacing = 0.sp))).size.width / CHAR_SAMPLE.length.toFloat()
        ConsoleMetrics(chars, glyphs / textSizeSp, textSizeSp.toFloat())
    }
}

private fun Modifier.placeAt(rect: PxRect, density: Density): Modifier = with(density) {
    // Never a negative size, which Compose refuses, should the area be squeezed below a header's height
    this@placeAt.offset { IntOffset(rect.x.roundToInt(), rect.y.roundToInt()) }
        .size(rect.w.coerceAtLeast(0f).toDp(), rect.h.coerceAtLeast(0f).toDp())
}

private fun title(window: ScriptWindow): String = window.dock?.title?.ifEmpty { null } ?: window.name

private fun windowColour(code: Int, default: Color): Color = if (code >= RUN_RGB) Color(0xFF000000.toInt() or (code and 0xFFFFFF)) else default

@Composable
private fun PlacedWindowView(placed: PlacedWindow, windows: ScriptWindows, metrics: ConsoleMetrics, density: Density, modifier: Modifier = Modifier) {
    val window = placed.window
    val placedModifier = Modifier.placeAt(placed.rect, density).then(modifier)
    when (window.kind) {
        in ScriptWindows.CONSOLE_KINDS -> {
            WindowConsole(placed, windows.text[window.name], windows.commandLineOf(window), windows.commandLines, metrics, density, placedModifier)
        }

        // A label's text and images come with its content, later; its colour now
        WindowKind.LABEL -> if (window.bg != -1) Box(placedModifier.background(windowColour(window.bg, Color.Transparent)))

        // One a script made with createCommandLine(), where it put it
        WindowKind.COMMAND_LINE -> {
            val description = stringResource(R.string.window_description, title(window))
            WindowCommandLine(window.name, windows.commandLines[window.name], placedModifier.windowSemantics(description))
        }

        // A scroll box only holds windows; maps and text boxes are drawn later
        else -> {}
    }
}

/** A script window is read after the game screen's own controls, as one group, by its [description]. */
private fun Modifier.windowSemantics(description: String): Modifier = semantics {
    contentDescription = description
    isTraversalGroup = true
    traversalIndex = WINDOWS_TRAVERSAL_INDEX
}

/**
 * A mini console, user window or MXP frame: its rows as the main console draws its lines, at the size that fits the
 * window's columns in its width. It follows new rows unless the player or a script scrolled it back. Its own
 * command line, when shown, goes along its bottom.
 */
@Composable
private fun WindowConsole(
    placed: PlacedWindow,
    text: EngineMessage.WindowText?,
    commandLine: ScriptWindow?,
    commandLines: Map<String, String>,
    metrics: ConsoleMetrics,
    density: Density,
    modifier: Modifier = Modifier,
) {
    val window = placed.window
    val columns = window.console?.columns ?: 0
    val wantedSp = if (columns > 0) placed.fullWidth / columns / metrics.glyphWidthPerSp else metrics.fontSp
    val fontSp = wantedSp.coerceIn(MIN_WINDOW_FONT_SP, MAX_WINDOW_FONT_SP)
    // Too small to read at its own width: drawn at the smallest size, as wide as its rows need, and scrolled sideways
    val rowsWidth = if (wantedSp < MIN_WINDOW_FONT_SP) with(density) { (columns * metrics.glyphWidthPerSp * fontSp).toDp() } else null
    val style = LocalTextStyle.current.merge(
        TextStyle(color = CONSOLE_FOREGROUND, fontFamily = FontFamily.Monospace, fontSize = fontSp.sp, lineHeight = 1.2.em, letterSpacing = 0.sp),
    )
    val first = text?.first ?: 0
    val lines = remember(text) { text?.lines.orEmpty().map { UiLine(it.text, it.runs, it.prompt) } }
    val listState = rememberLazyListState()
    var following by remember { mutableStateOf(true) }

    SideEffect {
        if (following && lines.isNotEmpty()) listState.requestScrollToItem(lines.size - 1)
    }
    // Only a scroll the player makes decides whether it follows: reaching the bottom does, leaving it does not
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, more) ->
            if (scrolling) following = !more
        }
    }
    val scrolledBackTo = window.console?.scrolledBackTo
    LaunchedEffect(scrolledBackTo) {
        if (scrolledBackTo == null) {
            following = true
        } else {
            following = false
            val index = scrolledBackTo - first
            if (index in lines.indices) listState.scrollToItem(index)
        }
    }

    val description = stringResource(R.string.window_description, title(window))
    Column(modifier.background(windowColour(window.bg, CONSOLE_BACKGROUND)).windowSemantics(description)) {
        val sideways = if (rowsWidth != null) Modifier.horizontalScroll(rememberScrollState()) else Modifier
        Box(Modifier.weight(1f).fillMaxWidth().then(sideways)) {
            LazyColumn(if (rowsWidth != null) Modifier.width(rowsWidth).fillMaxSize() else Modifier.fillMaxSize(), state = listState) {
                // Each row's key is its number in the console, so the rows in view stay put as new ones come
                items(lines.size, key = { first + it }) { i ->
                    val row = remember(lines[i]) { ansiText(lines[i]) }
                    Text(row, Modifier.fillMaxWidth(), style = style, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
        }
        if (commandLine != null) WindowCommandLine(commandLine.name, commandLines[commandLine.name], Modifier.fillMaxWidth())
    }
}

/**
 * A window's command line: what is typed there goes to the engine with `sendWindowCommandLine`, and a script's
 * [scriptText] replaces it. The engine's emptying of the line after a send is not taken as a script's, as the player
 * may already be typing the next.
 */
@Composable
private fun WindowCommandLine(name: String, scriptText: String?, modifier: Modifier = Modifier) {
    var text by rememberSaveable(name, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var notSent by remember { mutableStateOf(false) }
    var awaitingClear by remember { mutableStateOf(false) }

    LaunchedEffect(scriptText) {
        if (scriptText == null) return@LaunchedEffect
        // Only the first text after a send can be Mudlet's emptying of the line
        if (!(scriptText.isEmpty() && awaitingClear)) text = TextFieldValue(scriptText, TextRange(scriptText.length))
        awaitingClear = false
        ScriptWindowsClient.commandLineShown(name)
    }

    fun submit() {
        val sent = ScriptWindowsClient.sendWindowCommandLine(name, text.text)
        notSent = !sent
        if (sent) {
            text = TextFieldValue()
            awaitingClear = true
        }
    }

    Row(modifier.background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                notSent = false
            },
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            label = { Text(stringResource(R.string.window_command_line, name)) },
            supportingText = if (notSent) {
                { Text(stringResource(R.string.input_not_sent), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            } else {
                null
            },
            isError = notSent,
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        IconButton(::submit) {
            Icon(painterResource(R.drawable.ic_send), stringResource(R.string.window_command_send, name))
        }
    }
}

/**
 * A dock's or a page's title row: for a page, a button back to the console ([onBack]); for a user window, its close
 * button, which hides it as the dock's own would.
 */
@Composable
private fun WindowHeader(window: ScriptWindow, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val name = title(window)
    Row(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant).semantics {
            isTraversalGroup = true
            traversalIndex = WINDOWS_TRAVERSAL_INDEX
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.window_back_to_console, name))
            }
        }
        Text(
            name,
            Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (window.kind == WindowKind.USER_WINDOW) {
            // Off, and read as such, while there is no engine to tell
            IconButton({ ScriptWindowsClient.closeUserWindow(window.name) }, enabled = EngineClient.enginePid > 0) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.window_close, name))
            }
        }
    }
}

/** The window switcher: a tab for each page, which opens it over the console, and closes it again. */
@Composable
private fun WindowSwitcher(pages: List<ScriptWindow>, selected: WindowKey?, onSelect: (WindowKey) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).semantics {
            isTraversalGroup = true
            traversalIndex = WINDOWS_TRAVERSAL_INDEX
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (page in pages) {
            key(page.key) {
                FilterChip(selected = page.key == selected, onClick = { onSelect(page.key) }, label = { Text(title(page)) })
            }
        }
    }
}
