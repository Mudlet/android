package org.mudlet.android.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.ConsoleCells
import org.mudlet.android.protocol.ConsoleZoom
import org.mudlet.android.protocol.DrawnWindow
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.LabelStyle
import org.mudlet.android.protocol.MapCorner
import org.mudlet.android.protocol.PlacedWindow
import org.mudlet.android.protocol.PxRect
import org.mudlet.android.protocol.RUN_RGB
import org.mudlet.android.protocol.ScriptWindow
import org.mudlet.android.protocol.ScriptWindows
import org.mudlet.android.protocol.ScrollThumb
import org.mudlet.android.protocol.WindowKey
import org.mudlet.android.protocol.WindowKind
import org.mudlet.android.protocol.WindowLayout
import org.mudlet.android.protocol.backgroundArgb
import org.mudlet.android.protocol.layoutSize
import org.mudlet.android.protocol.linkSpans
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.CONSOLE_FOREGROUND
import org.mudlet.android.ui.ConsoleZoomClient
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.UiLine
import org.mudlet.android.ui.ansiText
import kotlin.math.max
import kotlin.math.roundToInt

private const val CHAR_SAMPLE = "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM"

// The printable ASCII characters, whose average width a console in a proportional family is fitted by
private val AVERAGE_SAMPLE = (' '..'~').joinToString("")

// What a row squeezed to its width leaves spare, for the rounding of its width
private const val SQUEEZE_SPARE_PX = 1f

// Script windows' text is never drawn smaller than this; a window that would need it smaller scrolls sideways
private const val MIN_WINDOW_FONT_SP = 9f
private const val MAX_WINDOW_FONT_SP = 28f

// The row with a dock's or a page's title and buttons, and the window switcher's row of tabs
private val HEADER_HEIGHT = 48.dp
private val SWITCHER_HEIGHT = 56.dp

// How long the size a pinch reached is drawn after the fingers lift, while the engine has not reported a new size
private const val PINCH_HELD_MS = 2000L

// A console's scroll bar: the thumb as drawn, the strip of it a finger takes, and the shortest it gets
private val SCROLL_THUMB_WIDTH = 4.dp
private val SCROLL_THUMB_TOUCH = 24.dp
private val SCROLL_THUMB_MIN = 48.dp

// As the desktop's handle, white or black ([ScrollThumb.whiteOn]) at an alpha of 200
private const val SCROLL_THUMB_ALPHA = 200 / 255f

internal const val SCROLL_THUMB_TAG = "window-scroll-thumb"

// Script windows come after the console, the input line and the screen's buttons, which keep the default of 0
internal const val WINDOWS_TRAVERSAL_INDEX = 1f

private val WindowKeySaver = listSaver<WindowKey?, String>(
    save = { if (it == null) emptyList() else listOf(it.name, it.kind.wire) },
    restore = { saved -> WindowKind.fromWire(saved.getOrNull(1).orEmpty())?.let { WindowKey(saved[0], it) } },
)

/**
 * The width of a character of the main console in pixels, and of a script window's monospace glyph per sp of its
 * size; the main console's text size, which sets the scale Mudlet's windows are drawn at. [widthPerSp] gives a
 * console's character width per sp in any family, measured by [measurer] in [style], the main console's, at that size,
 * as the glyph is.
 */
private class ConsoleMetrics(
    val charWidth: Float,
    val glyphWidthPerSp: Float,
    val fontSp: Float,
    val measurer: TextMeasurer,
    private val style: TextStyle,
) {
    // A plain map, filled only during composition, on the main thread, with values that depend on nothing but its key
    private val averages = HashMap<FontFamily, Float>()

    /**
     * The width of a character of a console in [family] per sp of its size, in pixels: monospace's glyph, or another
     * family's average, its printable ASCII characters', measured once a family. Measured at the main console's size,
     * as the glyph is, so a font scale that grows small sizes more than large ones, as Android 14's does, skews both
     * alike.
     * It stands in for the average the engine counts a console's columns by, Qt's `QFontMetricsF::averageCharWidth()`
     * of the font the script named, that font's OS/2 average, of all its glyphs or, in an older font, of its lowercase
     * letters weighted by use: as much as a fifth off either way even when [family] is that very font, further when it
     * stands in for another, so such a console can be drawn a little smaller or larger than on the desktop. A CJK
     * font's average, of glyphs mostly a full em wide, is nearly an em, so the engine counts about half the columns
     * this average would, and such a console is drawn larger than on the desktop, though its rows still fit.
     */
    fun widthPerSp(family: FontFamily): Float = if (family == FontFamily.Monospace) {
        glyphWidthPerSp
    } else {
        averages.getOrPut(family) {
            val sample = measurer.measure(AVERAGE_SAMPLE, style.merge(TextStyle(fontFamily = family, letterSpacing = 0.sp)))
            sample.size.width / AVERAGE_SAMPLE.length.toFloat() / fontSp
        }
    }
}

/**
 * The game area: the main console, drawn by [content] with the modifier it is given and whether a script's mapper
 * window is on the screen ([WindowLayout.mapperDrawn]), and around and over it the
 * windows the profile's scripts made, the map among them ([ScriptMap]) and MXP frames' tabs ([ScriptFrameTabs]), placed
 * as [WindowLayout] says; below it, while there are any, the window switcher's tabs, which open a window in a pane over
 * the console. It gives Mudlet's main window the size that makes Mudlet's grid the console's, at the console's
 * [textSizeSp], and tells the engine where it put the user windows. The profile's button bars go around all of it ([ScriptButtonBars]).
 * Given [appMapCorner], while no script's mapper is drawn and the console has room for it clear of the windows
 * ([MapCorner.ofAppMap]), it draws the app's map in the console's corner: at the [MapCorner] and in the console it is
 * given, with the modifier it is given.
 */
@Composable
fun ScriptWindowsArea(
    textSizeSp: Int,
    modifier: Modifier = Modifier,
    appMapCorner: (@Composable (corner: MapCorner, console: PxRect, modifier: Modifier) -> Unit)? = null,
    content: @Composable (consoleModifier: Modifier, mapperDrawn: Boolean) -> Unit,
) {
    ScriptButtonBars(modifier) { area, fitted -> WindowsArea(textSizeSp, area, fitted, appMapCorner, content) }
}

// [fitted] is the whole game area's, bars and all, so the windows are fitted when the bars are
@Composable
private fun WindowsArea(
    textSizeSp: Int,
    modifier: Modifier,
    fitted: Boolean,
    appMapCorner: (@Composable (corner: MapCorner, console: PxRect, modifier: Modifier) -> Unit)?,
    content: @Composable (consoleModifier: Modifier, mapperDrawn: Boolean) -> Unit,
) {
    val client = EngineClient.front.windows
    val windows = client.state
    val metrics = rememberConsoleMetrics(textSizeSp)
    val density = LocalDensity.current
    val headerHeight = with(density) { HEADER_HEIGHT.toPx() }
    val switcherHeight = with(density) { SWITCHER_HEIGHT.toPx() }
    var selected by rememberSaveable(stateSaver = WindowKeySaver) { mutableStateOf<WindowKey?>(null) }

    BoxWithConstraints(modifier.background(CONSOLE_BACKGROUND).clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
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
        val attaches = EngineClient.front.attaches
        LaunchedEffect(layout.mainWindowSize, attaches) { layout.mainWindowSize?.let(client::setMainWindowSize) }
        // What a profile with no windows yet is given here, for the next one opened before this screen has measured it
        val startSize = remember(windows.charWidth, w, h, fitted, metrics, headerHeight) {
            WindowLayout.of(ScriptWindows(charWidth = windows.charWidth), WindowLayout.Area(w, h, fitted, metrics.charWidth, headerHeight, null)).mainWindowSize
        }
        LaunchedEffect(startSize) { startSize?.let(EngineClient::setStartMainWindowSize) }
        LaunchedEffect(layout.placements, attaches) {
            for ((name, placement) in layout.placements) client.placeUserWindow(name, placement)
        }
        // A size a script gives a console the player pinched is set back to the player's, once the engine reports it
        val zoom = EngineClient.front.consoleZoom
        val consoleSizes = remember(windows.windows) {
            windows.windows.filter { it.kind in ScriptWindows.CONSOLE_KINDS }.associate { it.name to ConsoleZoom.points(it.fontSize) }
        }
        LaunchedEffect(zoom, consoleSizes, attaches) { for ((name, size) in consoleSizes) zoom.reported(name, size) }
        // A page the layout no longer offers can no longer be open, and one an MXP frame put another of its pages in
        // front of is that one
        LaunchedEffect(layout.selectedPage) { if (selected != layout.selectedPage) selected = layout.selectedPage }

        val page = layout.paneWindows.firstOrNull()?.window
        val pane = layout.pane?.takeIf { page != null }

        // What the pane covers is not read either
        fun Modifier.coveredBy(rect: PxRect): Modifier = if (pane != null && pane.contains(rect)) clearAndSetSemantics {} else this

        // The borders' colour fills the main window's area, showing where the console leaves room for them, as the
        // desktop paints its main frame; not under the docks, which are outside that frame there, nor the switcher's
        // row, which is the app's own and stays black
        Box(Modifier.placeAt(layout.main, density).background(windowColour(windows.mainArea?.borderColor ?: -1, CONSOLE_BACKGROUND)))
        content(Modifier.placeAt(layout.console, density).coveredBy(layout.console), layout.mapperDrawn)
        // What else is drawn over the game area, which a map in its corner stops short of
        fun drawnBesides(map: PlacedWindow?) = layout.windows.filter { it !== map && it.scrollBox == null }.map { it.rect } + layout.dockHeaders.map { it.rect } + layout.frameTabs.map { it.rect }

        // A mapper's panel goes beside it where there is room, and a small mapper is drawn larger, over what is under it
        // but never over another window it was clear of
        val mapper = layout.windows.firstOrNull { it.window.kind == WindowKind.MAPPER && it.scrollBox == null }
        val mapperKey = mapper?.window?.key
        val corner = remember(layout, w, h, switcherHeight, density.density) {
            mapper?.let { m ->
                val areaH = if (layout.pages.isEmpty()) h else (h - switcherHeight).coerceAtLeast(0f)
                MapCorner.of(m.rect, PxRect(0f, 0f, w, areaH), drawnBesides(m), density.density)
            }
        }
        // The app's map goes in the console's corner as a script's small mapper would, never as well as one
        val wantsAppCorner = appMapCorner != null && !layout.mapperDrawn
        val appCorner = remember(layout, density.density, wantsAppCorner) {
            if (wantsAppCorner) MapCorner.ofAppMap(layout.console, drawnBesides(null), density.density) else null
        }
        // The windows in a scroll box are drawn in it, to scroll with it; the map and its panel beside it go over the
        // windows it was drawn among
        val drawn = remember(layout.windows, corner) {
            DrawnWindow.of(layout.windows).let { all -> if (corner == null) all else all.sortedBy { it.placed.window.key == mapperKey } }
        }
        for (window in drawn) {
            val placedCorner = corner?.takeIf { window.placed.window.key == mapperKey }
            key(window.placed.window.key) { PlacedWindowView(window, windows, metrics, density, Modifier.coveredBy(placedCorner?.bounds ?: window.placed.rect), placedCorner) }
        }
        for (header in layout.dockHeaders) {
            key(header.window.key) { WindowHeader(header.window, onBack = null, Modifier.placeAt(header.rect, density)) }
        }
        for (strip in layout.frameTabs) {
            key(strip.frame.key) { ScriptFrameTabs(strip, Modifier.placeAt(strip.rect, density).coveredBy(strip.rect)) }
        }
        if (appCorner != null && appMapCorner != null) appMapCorner(appCorner, layout.console, Modifier.coveredBy(appCorner.bounds))
        if (pane != null && page != null) {
            BackHandler { selected = null }
            // Touches on the pane stay off what it covers
            Box(Modifier.placeAt(pane, density).background(CONSOLE_BACKGROUND).pointerInput(Unit) { consumeAll() })
            WindowHeader(page, onBack = { selected = null }, Modifier.placeAt(pane.copy(h = headerHeight), density))
            val paneDrawn = remember(layout.paneWindows) { DrawnWindow.of(layout.paneWindows) }
            for (window in paneDrawn) {
                key(window.placed.window.key) { PlacedWindowView(window, windows, metrics, density, page = false) }
            }
            // After the windows, as the tabs of a frame opened in the page go over the page
            for (strip in layout.paneFrameTabs) {
                key(strip.frame.key) { ScriptFrameTabs(strip, Modifier.placeAt(strip.rect, density)) }
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
        ConsoleMetrics(chars, glyphs / textSizeSp, textSizeSp.toFloat(), measurer, style)
    }
}

internal fun Modifier.placeAt(rect: PxRect, density: Density): Modifier = with(density) {
    // Never a negative size, which Compose refuses, should the area be squeezed below a header's height, nor one past
    // what it can measure, which a window in a scroll box may have
    val (w, h) = layoutSize(rect.w, rect.h)
    this@placeAt.offset { IntOffset(rect.x.roundToInt(), rect.y.roundToInt()) }.size(w.toDp(), h.toDp())
}

// The mapper's name is always "mapper", so it is called the map; an MXP frame's page is called what its tab shows
@Composable
private fun title(window: ScriptWindow): String = if (window.kind == WindowKind.MAPPER) {
    stringResource(R.string.map_title)
} else {
    window.dock?.title?.ifEmpty { null } ?: EngineClient.front.windows.state.tabTitleOf(window) ?: window.name
}

internal fun windowColour(code: Int, default: Color): Color = if (code >= RUN_RGB) Color(0xFF000000.toInt() or (code and 0xFFFFFF)) else default

// [corner] is where a mapper with room beside it for its panel goes, and the panel; [page] whether it is in the dock's page
@Composable
private fun PlacedWindowView(drawn: DrawnWindow, windows: ScriptWindows, metrics: ConsoleMetrics, density: Density, modifier: Modifier = Modifier, corner: MapCorner? = null, page: Boolean = false) {
    val placed = drawn.placed
    val window = placed.window
    val placedModifier = Modifier.placeAt(placed.rect, density).then(modifier)
    when (window.kind) {
        in ScriptWindows.CONSOLE_KINDS -> {
            WindowConsole(
                placed,
                windows.text[window.name],
                windows.links[window.name].orEmpty(),
                windows.commandLineOf(window),
                windows.commandLines,
                metrics,
                density,
                placedModifier,
            )
        }

        // A label places itself, as a clickable one's touch bounds can be larger than it
        WindowKind.LABEL -> ScriptLabel(placed, windows.labels[window.name], windows.gaugeParts[window.key], density, modifier)

        // One a script made with createCommandLine(), where it put it
        WindowKind.COMMAND_LINE -> {
            val description = stringResource(R.string.window_description, title(window))
            WindowCommandLine(window.name, windows.commandLines[window.name], window.styleSheet, placedModifier.windowSemantics(description))
        }

        // The map places itself, as its panel may go beside it and a small one is drawn larger
        WindowKind.MAPPER -> ScriptMap(placed, corner, density, modifier, page)

        // A text box places itself too, as a small one's touch bounds are larger than it
        WindowKind.TEXT_BOX -> ScriptTextBox(placed, windows.textBoxes[window.name], windows.textBoxEdits[window.name], density, modifier)

        WindowKind.SCROLL_BOX -> ScrollBox(drawn, windows, metrics, density, placedModifier)

        // No kind is left, but Kotlin does not count those in CONSOLE_KINDS
        else -> {}
    }
}

/**
 * A scroll box, a `QScrollArea` on the desktop: what of it shows, and the windows in it drawn in its content
 * ([PlacedWindow.content]), which scrolls under the whole box, from its top left, by a drag or TalkBack's scroll
 * actions, along each axis its content is larger than the box on. Each axis scrolls in a node of its own, with its own
 * actions. A pointer event is in the pixels of the window touched, so a label in it takes taps as anywhere else, and a
 * drag the box takes, along an axis it scrolls on, is not the label's ([LocalScrollingBox]). Its scroll is kept only
 * while it is drawn: a box hidden and shown again starts at its top left (docs/divergences.md).
 */
@Composable
private fun ScrollBox(drawn: DrawnWindow, windows: ScriptWindows, metrics: ConsoleMetrics, density: Density, modifier: Modifier = Modifier) {
    val placed = drawn.placed
    val full = placed.full
    val content = placed.content ?: full
    // As placeIn lays the box out below
    val (boxW, boxH) = layoutSize(full.w, full.h)
    val contentW = content.w.roundToInt()
    val contentH = content.h.roundToInt()
    // An axis it cannot scroll on takes no drag, so a label's drag along it stays the label's, and offers no action
    val scrollsDown = contentH > boxH
    val scrollsAcross = contentW > boxW
    // Its windows are read together, in reading order, after the game screen's own controls
    Box(
        modifier.clipToBounds().semantics {
            isTraversalGroup = true
            traversalIndex = WINDOWS_TRAVERSAL_INDEX
        },
    ) {
        // The whole box scrolls, though only part of it may show
        Box(Modifier.placeIn(full.x - placed.rect.x, full.y - placed.rect.y, full.w, full.h).verticalScroll(rememberScrollState(), enabled = scrollsDown)) {
            Box(Modifier.horizontalScroll(rememberScrollState(), enabled = scrollsAcross)) {
                CompositionLocalProvider(LocalScrollingBox provides (LocalScrollingBox.current || scrollsDown || scrollsAcross)) {
                    // Never smaller than the box, as each scroll clips what it holds to its size: a window's 48 dp touch
                    // bounds are cut where the box cuts them, not where content smaller than the box ends. Each window
                    // places itself, at a size of its own, as in the area.
                    Layout(content = {
                        for (window in drawn.scrolled) {
                            key(window.placed.window.key) { PlacedWindowView(window, windows, metrics, density) }
                        }
                    }) { measurables, _ ->
                        val placeables = measurables.map { it.measure(Constraints()) }
                        layout(max(contentW, boxW), max(contentH, boxH)) { placeables.forEach { it.place(0, 0) } }
                    }
                }
            }
        }
    }
}

/**
 * Whether what is drawn here is in a scroll box that scrolls, whose drags a label leaves to it (docs/phase-f.md,
 * "Labels").
 */
internal val LocalScrollingBox = compositionLocalOf { false }

/** A script window is read after the game screen's own controls, as one group, by its [description]. */
private fun Modifier.windowSemantics(description: String): Modifier = semantics {
    contentDescription = description
    isTraversalGroup = true
    traversalIndex = WINDOWS_TRAVERSAL_INDEX
}

/**
 * A mini console, user window or MXP frame: its rows as the main console draws its lines, at the size that fits the
 * window's columns in its width, and read as it reads them, with its [links] as [ConsoleLine] offers them. It follows
 * new rows unless the player or a script scrolled it back. Its own command line, when shown, goes along its bottom.
 *
 * Its rows are drawn in the family a script gave it (`setFont()`), mapped to the device's fonts as a label's is
 * ([fontFamily]), and in monospace for a family that maps to none. The size is the one that fits the columns in the
 * width by the family's average character width ([ConsoleMetrics.widthPerSp]: monospace's cell, or another family's
 * average), as the engine counted them, each row laid out in the family's own widths; a row of a proportional family
 * whose first columns would run past the width it has, as a full row of capitals does, its letters being wider than
 * that average, is squeezed sideways to it ([squeezed]), so none of those columns' letters is cut off. The console's
 * own command line is drawn in the same font as its rows unless its style sheet names one, at the theme's size
 * (docs/divergences.md, "A console's font").
 *
 * A two-finger pinch, or TalkBack's actions, set the console's font size, in the points scripts give `setFontSize()`
 * ([ConsoleZoomClient]): the text is drawn at the size reached while the fingers move, and at the engine's new grid
 * once it reports it. A two-finger double tap, or TalkBack's reset action, gives back the scripts' size.
 *
 * A console a script gave a scroll bar (`enableScrollBar()`) has one along its right edge ([ConsoleScrollBar]).
 *
 * Its new rows are not read aloud as the main console's are: a script that wants one read calls `announce()`.
 */
@Composable
private fun WindowConsole(
    placed: PlacedWindow,
    text: EngineMessage.WindowText?,
    links: Map<Int, EngineMessage.Link>,
    commandLine: ScriptWindow?,
    commandLines: Map<String, String>,
    metrics: ConsoleMetrics,
    density: Density,
    modifier: Modifier = Modifier,
) {
    val window = placed.window
    val zoom = EngineClient.front.consoleZoom
    val reported = ConsoleZoom.points(window.fontSize)
    val zoomable = ConsoleZoomClient.zoomable(window.name, reported)
    // The size a pinch has reached, drawn until the engine reports the console's new size and grid
    var pinched by remember { mutableStateOf<Int?>(null) }
    var pinching by remember { mutableStateOf(false) }
    var pinchStart by remember { mutableIntStateOf(0) }
    LaunchedEffect(reported) { pinched = null }
    // Nor kept for long once the fingers lift, should the engine not take the size (a style sheet's font holds)
    LaunchedEffect(pinched, pinching) {
        if (pinched != null && !pinching) {
            delay(PINCH_HELD_MS)
            pinched = null
        }
    }
    val columns = window.console?.columns ?: 0
    val scale = pinched?.takeIf { reported > 0 }?.let { it.toFloat() / reported } ?: 1f
    val family = window.fontFamily?.let(::fontFamily) ?: FontFamily.Monospace
    val proportional = family != FontFamily.Monospace
    // Monospace's is the cell measured once for every console, so a console in monospace draws its rows as ever
    val widthPerSp = metrics.widthPerSp(family)
    val wantedSp = (if (columns > 0) placed.full.w / columns / widthPerSp else metrics.fontSp) * scale
    val fontSp = wantedSp.coerceIn(MIN_WINDOW_FONT_SP, MAX_WINDOW_FONT_SP)
    // Too small to read at its own width: drawn at the smallest size, as wide as its rows need, and scrolled sideways
    val rowsWidth = if (wantedSp < MIN_WINDOW_FONT_SP) with(density) { (columns * widthPerSp * fontSp).toDp() } else null
    val style = LocalTextStyle.current.merge(
        TextStyle(color = CONSOLE_FOREGROUND, fontFamily = family, fontSize = fontSp.sp, lineHeight = 1.2.em, letterSpacing = 0.sp),
    )
    val first = text?.first ?: 0
    val lines = remember(text) { text?.lines.orEmpty().map { UiLine(it.text, it.runs, it.prompt) } }
    val spans = remember(text) { lines.map { linkSpans(it.runs, it.text.length) } }
    val blankLines = EngineClient.front.setting(Settings.blankLines)
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
    val current = pinched ?: reported
    val larger = ConsoleZoom.clamp(current + 1, current)
    val smaller = ConsoleZoom.clamp(current - 1, current)
    val chosen = zoom.chosen(window.name)
    val zoomActions = if (!zoomable) {
        emptyList()
    } else {
        listOfNotNull(
            CustomAccessibilityAction(stringResource(R.string.window_text_larger)) {
                zoom.choose(window.name, larger, reported)
                true
            }.takeIf { larger != current },
            CustomAccessibilityAction(stringResource(R.string.window_text_smaller)) {
                zoom.choose(window.name, smaller, reported)
                true
            }.takeIf { smaller != current },
            CustomAccessibilityAction(stringResource(R.string.window_text_reset)) {
                zoom.reset(window.name, reported)
                true
            }.takeIf { chosen != null },
        )
    }
    // The gesture outlives this composition's values
    val latestCurrent by rememberUpdatedState(current)
    val latestReported by rememberUpdatedState(reported)
    val latestZoom by rememberUpdatedState(zoom)
    val pinch = if (!zoomable) {
        Modifier
    } else {
        Modifier.pinchToZoom(
            textSize = { latestCurrent.also { pinchStart = it } },
            onTextSize = {
                pinching = true
                pinched = ConsoleZoom.clamp(it, pinchStart)
            },
            onPinchEnd = {
                pinching = false
                pinched?.let { latestZoom.choose(window.name, it, latestReported) }
            },
            onTwoFingerDoubleTap = {
                pinching = false
                pinched = null
                latestZoom.reset(window.name, latestReported)
            },
        )
    }
    val background = window.backgroundArgb()?.let { Color(it) } ?: CONSOLE_BACKGROUND
    Column(
        modifier.background(background).windowSemantics(description).semantics { customActions = zoomActions },
    ) {
        val sidewaysState = rememberScrollState()
        val sideways = if (rowsWidth != null) Modifier.horizontalScroll(sidewaysState) else Modifier
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().then(pinch).then(sideways)) {
            // The width each row has: the window's, or the rows' own while they scroll sideways
            val available = rowsWidth?.let { with(density) { it.roundToPx() } } ?: constraints.maxWidth
            LazyColumn(if (rowsWidth != null) Modifier.width(rowsWidth).fillMaxSize() else Modifier.fillMaxSize(), state = listState) {
                // Each row's key is its number in the console, so the rows in view stay put as new ones come
                items(lines.size, key = { first + it }) { i ->
                    val row = remember(lines[i]) { ansiText(lines[i]) }
                    // Monospace's rows fit their columns as they are, so only a proportional family's are measured
                    val rowStyle = if (proportional) remember(metrics, row, style, available, columns) { squeezed(metrics.measurer, row, style, available, columns) } else style
                    val rowSpans = spans[i]
                    val rowLinks = if (rowSpans.isEmpty()) {
                        null
                    } else {
                        // Asked about again for each new text, which may have changed them
                        LaunchedEffect(text, rowSpans) { for (span in rowSpans) EngineClient.front.windows.requestLink(window.name, span.link) }
                        LineLinks(rowSpans, links::get) { link, item -> EngineClient.front.windows.followLink(window.name, link, item) }
                    }
                    ConsoleLine(row, lines[i].text, rowStyle, Modifier.fillMaxWidth(), blankLines = blankLines, singleLine = true, links = rowLinks)
                }
            }
            if (window.console?.scrollBar == true) {
                // At the right edge of what shows of rows scrolled sideways, not of the rows; a drag of it is the
                // player's scroll, so the console follows new rows again only if it ends at the bottom
                ConsoleScrollBar(listState, background, { if (rowsWidth != null) sidewaysState.value - sidewaysState.maxValue else 0 }) { following = it }
            }
        }
        if (commandLine != null) WindowCommandLine(commandLine.name, commandLines[commandLine.name], commandLine.styleSheet, Modifier.fillMaxWidth(), family = family)
    }
}

/**
 * [style] for a console's row [text] in a proportional family, squeezed sideways to [available] pixels where its
 * letters, on the whole wider than the family's average the size was fitted by, would run past them; else [style]. Only
 * its first [columns] count, in the desktop's cells, two for a wide East Asian character and none for a combining mark
 * ([ConsoleCells]), as a row a console does not wrap can be longer, and what is past them is cut off at the edge, as in
 * monospace and on the desktop, though where the first columns end short of the edge some of it shows before it, the
 * row being drawn whole, not cut at its columns, which would leave that width blank; spaces at its end, which show no
 * letter, count for nothing, a background they have then cut off with the rest. A console with no grid yet is not
 * squeezed: its rows are cut off. A squeezed row is laid out in [TextMotion.Animated], whose letters' widths are not
 * rounded to whole pixels, so the squeezed row is as wide as the scale makes it.
 */
private fun squeezed(measurer: TextMeasurer, text: AnnotatedString, style: TextStyle, available: Int, columns: Int): TextStyle {
    if (columns <= 0 || available <= SQUEEZE_SPARE_PX || available == Constraints.Infinity) return style
    val shown = text.subSequence(0, text.text.take(ConsoleCells.end(text.text, columns)).trimEnd().length)
    fun widthIn(rowStyle: TextStyle) = measurer.measure(shown, rowStyle, softWrap = false, maxLines = 1).multiParagraph.intrinsics.maxIntrinsicWidth
    if (widthIn(style) <= available) return style
    val linear = style.merge(TextStyle(textMotion = TextMotion.Animated))
    val unrounded = widthIn(linear)
    val scaleX = ((available - SQUEEZE_SPARE_PX) / unrounded).coerceAtMost(1f)
    return linear.merge(TextStyle(textGeometricTransform = TextGeometricTransform(scaleX = scaleX)))
}

/**
 * The scroll bar of a console a script gave one: a thumb at the right edge, over the rows' last columns, as long as
 * the share of the rows in view and where they are among all of them ([ScrollThumb]), in the colour that stands out
 * from [background]. There is none while every row is in view, nor in a console too short for it to move. [shift] is
 * its x from the rows' right edge, in pixels: zero, or less to draw it left of that edge.
 *
 * Dragging it scrolls the rows, and stops a fling of them. [onFollow] is told false as a drag starts, and as it ends
 * whether the console follows new rows again: it does if the drag left the rows at their end, though rows have come
 * since, which are then scrolled to. A thumb that goes while a finger holds it (the script took the bar away, or
 * every row fits now) ends the drag the same way.
 *
 * It is nothing to TalkBack, which scrolls the list with its own actions and reads the rows, as without the bar.
 */
@Composable
private fun BoxScope.ConsoleScrollBar(listState: LazyListState, background: Color, shift: () -> Int, onFollow: (Boolean) -> Unit) {
    val density = LocalDensity.current
    val minSize = with(density) { SCROLL_THUMB_MIN.toPx() }
    val thumb by remember(listState, minSize) {
        derivedStateOf {
            val info = listState.layoutInfo
            val rows = info.visibleItemsInfo
            rows.firstOrNull()?.let { top ->
                val rowHeight = rows.sumOf { it.size }.toFloat() / rows.size
                ScrollThumb.of(info.totalItemsCount, top.index, -top.offset, rowHeight, info.viewportSize.height, !listState.canScrollForward, minSize)
            }
        }
    }
    val shown = thumb ?: return
    // Null with no finger dragging the thumb, else whether the drag has left the rows at their end
    val held = remember { mutableStateOf<Boolean?>(null) }
    val follow by rememberUpdatedState(onFollow)

    // The end of a drag, if there is one: what [held] was
    fun release(): Boolean? = held.value?.also { atEnd ->
        held.value = null
        follow(atEnd || !listState.canScrollForward)
    }
    // Leaving the composition, as the thumb does above when it goes, ends no drag by itself
    DisposableEffect(Unit) { onDispose { release() } }
    val colour = (if (ScrollThumb.whiteOn(background.luminance())) Color.White else Color.Black).copy(alpha = SCROLL_THUMB_ALPHA)
    Box(
        Modifier.align(Alignment.TopEnd)
            .offset { IntOffset(shift(), shown.offset.roundToInt()) }
            .size(SCROLL_THUMB_TOUCH, with(density) { shown.size.toDp() })
            .draggable(
                // The drag's state calls the lambda of the latest composition, so this is the thumb as it is now
                rememberDraggableState {
                    listState.dispatchRawDelta(it * shown.rowsPerPixel)
                    held.value = !listState.canScrollForward
                },
                Orientation.Vertical,
                onDragStarted = {
                    held.value = !listState.canScrollForward
                    follow(false)
                    listState.stopScroll()
                },
                // Rows that came while the thumb was held at the end are below the view: the newest is scrolled to
                onDragStopped = { if (release() == true) listState.requestScrollToItem(listState.layoutInfo.totalItemsCount - 1) },
            )
            .testTag(SCROLL_THUMB_TAG),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(Modifier.fillMaxHeight().width(SCROLL_THUMB_WIDTH).background(colour, CircleShape))
    }
}

/**
 * A window's command line: what is typed there goes to the engine with `sendWindowCommandLine`, and a script's
 * [scriptText] replaces it. The engine's emptying of the line after a send is not taken as a script's, as the player
 * may already be typing the next. In a game that keeps a sent command on its input line, this line keeps its own too,
 * selected whole, as desktop Mudlet's does.
 *
 * It is drawn in the colours of a script's [styleSheet], over the theme's surface: a see-through sheet shows that
 * surface here, where on the desktop it shows what is under the console or window the line is in. A console's own
 * line is drawn in the console's [family], as the desktop gives it the console's font when it is made and on
 * `setFont()`, at the theme's size, which is the input field's; a line a script made (`createCommandLine()`), with no
 * [family], in the theme's font. A [styleSheet]'s `font-family` that maps to one of the device's fonts wins over
 * both, as a style sheet's font wins over `setFont()` on the desktop.
 */
@Composable
private fun WindowCommandLine(name: String, scriptText: String?, styleSheet: String, modifier: Modifier = Modifier, family: FontFamily? = null) {
    var text by rememberSaveable(name, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var notSent by remember { mutableStateOf(false) }
    var awaitingClear by remember { mutableStateOf(false) }
    // The line as it was sent and kept
    var kept by rememberSaveable(name) { mutableStateOf<String?>(null) }

    LaunchedEffect(scriptText) {
        if (scriptText == null) return@LaunchedEffect
        // Only the first text after a send can be Mudlet's emptying of the line
        if (!(scriptText.isEmpty() && awaitingClear)) text = TextFieldValue(scriptText, TextRange(scriptText.length))
        awaitingClear = false
        EngineClient.front.windows.commandLineShown(name)
    }

    fun submit() {
        val sent = EngineClient.front.windows.sendWindowCommandLine(name, text.text)
        notSent = !sent
        if (!sent) return
        val clears = EngineClient.front.setting(Settings.clearInputAfterSend)
        kept = text.text.takeUnless { clears || it.isEmpty() }
        text = if (clears) TextFieldValue() else TextFieldValue(text.text, TextRange(0, text.text.length))
        awaitingClear = clears
    }

    // A script's sheet, read as a text box's: its background behind the line, its colour for the line's text, name,
    // cursor, outline while focused, "not sent" and send button, in the error state too, as the theme's error and focus
    // colours could not be read over the sheet's fill; where it gives only one of the two, the other by contrast with
    // it as drawn, not the theme's. A sheet that gives neither leaves the line as with none, on the theme's surface
    val surface = MaterialTheme.colorScheme.surface
    val style = remember(styleSheet, surface) { LabelStyle.parse(styleSheet, LabelStyle.COMMAND_LINE_SELECTORS).paired(surface.toArgb()) }
    val textColour = style.color?.let { Color(it) } ?: Color.Unspecified
    Row(
        modifier.background(surface).drawBehind { drawLabelFrame(style.background, style, 0f, 0f, 1f, null, null) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                notSent = false
            },
            modifier = Modifier.weight(1f).padding(start = 4.dp)
                .keptCommand(text.text == kept && text.selection == TextRange(0, text.text.length), text.text.length, stringResource(R.string.input_kept)),
            label = { Text(stringResource(R.string.window_command_line, name)) },
            supportingText = if (notSent) {
                { Text(stringResource(R.string.input_not_sent), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            } else {
                null
            },
            isError = notSent,
            singleLine = true,
            textStyle = LocalTextStyle.current.merge(TextStyle(fontFamily = style.fontFamily?.let(::fontFamily) ?: family)),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = textColour,
                unfocusedTextColor = textColour,
                focusedLabelColor = textColour,
                unfocusedLabelColor = textColour,
                cursorColor = textColour,
                errorTextColor = textColour,
                errorLabelColor = textColour,
                errorCursorColor = textColour,
                errorSupportingTextColor = textColour,
                focusedBorderColor = textColour,
                errorBorderColor = textColour,
            ),
        )
        IconButton(::submit) {
            Icon(painterResource(R.drawable.ic_send), stringResource(R.string.window_command_send, name), tint = textColour.takeOrElse { LocalContentColor.current })
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
            IconButton({ EngineClient.front.windows.closeUserWindow(window.name) }, enabled = EngineClient.enginePid > 0) {
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
