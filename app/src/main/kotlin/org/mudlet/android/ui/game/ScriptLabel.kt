package org.mudlet.android.ui.game

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.protocol.AccessibleValue
import org.mudlet.android.protocol.Edges
import org.mudlet.android.protocol.Gauge
import org.mudlet.android.protocol.HAlign
import org.mudlet.android.protocol.LabelContent
import org.mudlet.android.protocol.LabelFill
import org.mudlet.android.protocol.LabelStyle
import org.mudlet.android.protocol.LiveRegion
import org.mudlet.android.protocol.POINTER_LEFT
import org.mudlet.android.protocol.POINTER_RIGHT
import org.mudlet.android.protocol.PlacedWindow
import org.mudlet.android.protocol.PointerType
import org.mudlet.android.protocol.PxRect
import org.mudlet.android.protocol.RichText
import org.mudlet.android.protocol.ScriptWindow
import org.mudlet.android.protocol.VAlign
import org.mudlet.android.ui.CONSOLE_FOREGROUND
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ScriptWindowsClient.ImageRequest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ScriptLabel"

// A clickable label's touch bounds, and a text box's, are never smaller than this, around it
internal val MIN_TOUCH_SIZE = 48.dp

// Qt's default font, where neither a label's style sheet nor its window sets one: 10 pt
private const val DEFAULT_FONT_PX = 10f * LabelStyle.PX_PER_PT

// The colour Qt draws a link in where the text sets none
private val LINK_COLOUR = Color(0xFF4AA3FF)

private const val TOOLTIP_MILLIS = 4000L

// A background image tiled more often than this is not drawn, as each frame would draw every tile
private const val MAX_TILES = 4096

/**
 * A label (docs/phase-f.md, "Labels"): its [content] drawn at [placed]'s place, scaled from Mudlet's pixels as its
 * window is, and the player's touches sent back to it as `pointer` events. A clickable one is a button, read by its
 * accessible name, and touched within 48 dp around it; one with text is read as text; one with only an image and no
 * name is left out; one a script gave an accessible value is a progress bar. Of a [gauge]'s three labels, its back is
 * read as the gauge's progress, and the other two not at all.
 * A label with a live region has TalkBack read it again each time what it reads changes.
 */
@Composable
internal fun ScriptLabel(placed: PlacedWindow, content: LabelContent?, gauge: Gauge?, density: Density, modifier: Modifier = Modifier) {
    val window = placed.window
    val rect = placed.rect
    val full = placed.full
    // Screen pixels per Mudlet pixel, across and down
    val sx = if (window.w > 0) full.w / window.w else 1f
    val sy = if (window.h > 0) full.h / window.h else 1f
    val clickable = content?.accessibleButton == true
    val touch = if (clickable) rect.grownTo(with(density) { MIN_TOUCH_SIZE.toPx() }) else rect
    val text = rememberLabelText(content, window, min(sx, sy))
    var showTip by remember { mutableStateOf(false) }
    val tooltip = remember(content?.toolTip) { content?.toolTip?.let { RichText.parse(it, DEFAULT_FONT_PX).plain }.orEmpty() }

    // Where a touch at a point in the touch bounds is in the label's own pixels, inside it as the engine requires
    val toMudlet: (Offset) -> Pair<Double, Double> = { at ->
        val x = (touch.x + at.x - full.x) / sx
        val y = (touch.y + at.y - full.y) / sy
        x.toDouble().coerceIn(0.0, (window.w - 1).coerceAtLeast(0).toDouble()) to
            y.toDouble().coerceIn(0.0, (window.h - 1).coerceAtLeast(0).toDouble())
    }
    val currentToMudlet by rememberUpdatedState(toMudlet)
    // A tool tip set after the label was first drawn is shown too
    val currentTooltip by rememberUpdatedState(tooltip)
    val centre = (window.w / 2.0) to (window.h / 2.0)

    val reading = labelReading(window, content, gauge, text, tooltip, centre)
    // A click-through label lets the touch reach what is under it
    val touches = if (content?.clickThrough == true) {
        Modifier
    } else {
        Modifier.pointerInput(window.name) {
            labelGestures(window.name, { currentToMudlet }, onLongPress = { if (currentTooltip.isNotEmpty()) showTip = true })
        }
    }

    Box(modifier.placeAt(touch, density).readAs(reading).then(touches)) {
        Box(Modifier.placeIn(rect.x - touch.x, rect.y - touch.y, rect.w, rect.h).clipToBounds()) {
            LabelBody(window, content, text, sx, sy, Modifier.placeIn(full.x - rect.x, full.y - rect.y, full.w, full.h))
        }
        if (showTip) {
            Popup(popupPositionProvider = AboveOrBelow, onDismissRequest = { showTip = false }) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.inverseSurface) {
                    Text(
                        tooltip,
                        Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
            LaunchedEffect(Unit) {
                delay(TOOLTIP_MILLIS)
                showTip = false
            }
        }
    }
}

/** Has TalkBack told of a change of this node as [region] asks: after what it is saying, at once, or not at all. */
private fun SemanticsPropertyReceiver.liveAs(region: LiveRegion?) {
    when (region) {
        LiveRegion.POLITE -> liveRegion = LiveRegionMode.Polite
        LiveRegion.ASSERTIVE -> liveRegion = LiveRegionMode.Assertive
        LiveRegion.OFF, null -> {}
    }
}

/**
 * Has the [Announcer] say [text] each time it changes to one not blank while [region] is live, as the desktop says a
 * live label's new text (`TLabel::announceTextChange()`); not the text it had when first drawn.
 */
@Composable
private fun AnnounceChanges(text: String, region: LiveRegion) {
    var before by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (text != before && text.isNotBlank() && region != LiveRegion.OFF) {
            Announcer.announce(text, assertive = region == LiveRegion.ASSERTIVE)
        }
        before = text
    }
}

/** This rectangle grown about its centre to at least [min] pixels each way. */
internal fun PxRect.grownTo(min: Float): PxRect {
    val w2 = max(w, min)
    val h2 = max(h, min)
    return PxRect(x - (w2 - w) / 2, y - (h2 - h) / 2, w2, h2)
}

/** Lays its content out [w] by [h] pixels at [x], [y] in its parent, whatever the parent's size. */
internal fun Modifier.placeIn(x: Float, y: Float, w: Float, h: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints.fixed(w.roundToInt().coerceAtLeast(0), h.roundToInt().coerceAtLeast(0)))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
}

@Composable
private fun rememberLabelText(content: LabelContent?, window: ScriptWindow, scale: Float): LabelText {
    val styleSheet = content?.styleSheet.orEmpty()
    val style = remember(styleSheet) { LabelStyle.parse(styleSheet) }
    val basePx = fontPx(style, window)
    val text = content?.text.orEmpty()
    val rich = remember(text, basePx) { if (text.isBlank()) null else RichText.parse(text, basePx) }
    val linksFollowable = content?.linksFollowable != false
    return remember(rich, style, scale, linksFollowable) { LabelText(style, rich, scale, linksFollowable) }
}

/** The size of a label's or text box's text in Mudlet's pixels: its style sheet's, else its window's font's, else Qt's. */
internal fun fontPx(style: LabelStyle, window: ScriptWindow): Float = style.fontSizePx ?: window.fontSize.takeIf { it > 0 }?.let { (it * LabelStyle.PX_PER_PT).toFloat() } ?: DEFAULT_FONT_PX

/** A label's style sheet, its text if it has any, the scale it is drawn at, and whether its links are followed. */
private class LabelText(val style: LabelStyle, val rich: RichText?, val scale: Float, val linksFollowable: Boolean) {
    val plain: String = rich?.plain.orEmpty()

    /** The links a screen reader is offered as actions. */
    val links: List<Pair<String, String>> = if (linksFollowable) rich?.links.orEmpty() else emptyList()
}

/**
 * What a screen reader is told of the label, as docs/phase-f.md's "Touch and TalkBack" says: a button its click and
 * right-click, which send the tap's and the long press's `pointer` events to the label's centre, and its links as
 * actions; a gauge its progress, the value Geyser gave its back label or else its front label's width; a label with a
 * value, as a progress bar. A live label is one node, blank or not, live as its live region asks, which reads its text,
 * or its name with its text as its state; a live gauge's text label, or live progress bar, has the [Announcer] say its
 * new text.
 * Null for a label read by its text, as drawn; nothing set for one left out.
 */
@Composable
private fun labelReading(
    window: ScriptWindow,
    content: LabelContent?,
    gauge: Gauge?,
    text: LabelText,
    tooltip: String,
    centre: Pair<Double, Double>,
): (SemanticsPropertyReceiver.() -> Unit)? {
    val context = LocalContext.current
    val rightClickLabel = stringResource(R.string.label_right_click)
    val linkActions = text.links.map { (linkText, href) ->
        CustomAccessibilityAction(stringResource(R.string.label_follow_link, linkText.ifEmpty { href })) {
            EngineClient.front.windows.followLabelLink(context, window.name, href)
            true
        }
    }
    val plain = text.plain
    val accessibleName = content?.accessibleName.orEmpty()
    val button = content?.accessibleButton == true
    val name = window.name
    val live = content?.liveRegion?.takeIf { it != LiveRegion.OFF }
    val value = content?.accessibleValue

    fun SemanticsPropertyReceiver.asButton() {
        role = Role.Button
        onClick {
            click(name, centre, POINTER_LEFT)
            true
        }
        onLongClick(rightClickLabel) {
            click(name, centre, POINTER_RIGHT)
            true
        }
    }

    fun SemanticsPropertyReceiver.inOrder() {
        traversalIndex = WINDOWS_TRAVERSAL_INDEX
    }

    // The text, where the name it is read by does not already say it
    fun textBesides(readAs: String) = plain.takeIf { it.isNotBlank() && !it.trim().equals(readAs.trim(), ignoreCase = true) }

    // A live label read by its name says its new text as its state, as the desktop says a live label's new text,
    // name or not; not where the name already says it
    fun SemanticsPropertyReceiver.liveText(readAs: String) {
        if (live != null) textBesides(readAs)?.let { stateDescription = it }
    }

    if (gauge != null) {
        if (gauge.back.key != window.key) return {}
        val labels = EngineClient.front.windows.state.labels
        val textLabel = gauge.text?.let { labels[it.name] }
        val gaugeText = textLabel?.text?.let { RichText.parse(it, DEFAULT_FONT_PX).plain }
        // The value Geyser gave the back label, out of the gauge's maximum; else the one the front label's width shows
        val progress = value ?: AccessibleValue(gauge.percent.toDouble(), 0.0, 100.0)
        val state = gaugeText?.ifBlank { null } ?: stringResource(R.string.gauge_percent, progress.percent)
        // A live node would be read again at each change of the value too, so the text label's new text is announced
        // instead: on the desktop only it speaks, the back and front having no text
        if (textLabel != null && gaugeText != null) AnnounceChanges(gaugeText, textLabel.liveRegion)
        return {
            contentDescription = accessibleName.ifEmpty { gauge.name }
            progressBarRangeInfo = progress.rangeInfo()
            stateDescription = state
            if (button) asButton()
            inOrder()
        }
    }
    // A button's name and a progress bar's: its name, else its text, else its tool tip
    val readAs = accessibleName.ifEmpty { plain }.ifEmpty { tooltip }
    // A label with a value, as the desktop reads one that is not a button, is a progress bar read by its name, with its
    // percentage as its state, after its text where the name does not say it; not live, as a live node is read again
    // at each change of the value, so its new text is said
    if (value != null && !button) {
        val percent = stringResource(R.string.gauge_percent, value.percent)
        val state = textBesides(readAs)?.let { stringResource(R.string.label_text_and_value, it, percent) } ?: percent
        if (live != null) AnnounceChanges(plain, live)
        return {
            contentDescription = readAs.ifEmpty { name }
            progressBarRangeInfo = value.rangeInfo()
            stateDescription = state
            if (linkActions.isNotEmpty()) customActions = linkActions
            inOrder()
        }
    }
    if (button) {
        if (readAs.isEmpty()) {
            LaunchedEffect(name) { Log.w(TAG, "the clickable label $name has no accessible name, text or tool tip: setLabelAccessibleName() gives it one") }
        }
        return {
            contentDescription = readAs.ifEmpty { name }
            if (accessibleName.isNotEmpty()) liveText(accessibleName)
            asButton()
            if (linkActions.isNotEmpty()) customActions = linkActions
            liveAs(live)
            inOrder()
        }
    }
    // A live label keeps one node, blank or not, so its first text is a change TalkBack hears; its links are then
    // actions, as a named label's are. Blank, it holds no text, so it is not a stop that says nothing
    if (live != null && accessibleName.isEmpty()) {
        return {
            if (plain.isNotEmpty()) this.text = AnnotatedString(plain)
            if (linkActions.isNotEmpty()) customActions = linkActions
            liveAs(live)
            inOrder()
        }
    }
    return when {
        // Its text, links and all, read as the text draws it
        accessibleName.isEmpty() && plain.isNotEmpty() -> null

        accessibleName.isNotEmpty() -> {
            {
                contentDescription = accessibleName
                liveText(accessibleName)
                if (linkActions.isNotEmpty()) customActions = linkActions
                liveAs(live)
                inOrder()
            }
        }

        // Only a colour or an image: decoration
        else -> {
            {}
        }
    }
}

/** As a screen reader reads it: [AccessibleValue.value] from its minimum to its maximum. */
private fun AccessibleValue.rangeInfo() = ProgressBarRangeInfo(value.toFloat(), min.toFloat()..max.toFloat())

/** The label read as [reading] says, or by its own text, after the game screen's own controls. */
private fun Modifier.readAs(reading: (SemanticsPropertyReceiver.() -> Unit)?): Modifier = if (reading == null) {
    semantics {
        isTraversalGroup = true
        traversalIndex = WINDOWS_TRAVERSAL_INDEX
    }
} else {
    clearAndSetSemantics(reading)
}

// A click of [button] on [label] at [at], in its pixels, as a tap or long press sends it
private fun click(label: String, at: Pair<Double, Double>, button: Int) {
    val (x, y) = at
    EngineClient.front.windows.pointer(label, PointerType.ENTER, x, y)
    EngineClient.front.windows.pointer(label, PointerType.PRESS, x, y, button, button)
    EngineClient.front.windows.pointer(label, PointerType.RELEASE, x, y, button, 0)
}

private enum class Touch { TAP, DRAG, LONG_PRESS, CANCELLED }

/**
 * The player's touches on the label [name] as a mouse's, by docs/phase-f.md's "Labels": a tap is the left button's
 * press and release, a second tap soon after a double click, a long press the right button's (and [onLongPress]), and
 * a drag the left button held as it moves. Nothing is consumed, so the label's links still see their taps.
 */
private suspend fun PointerInputScope.labelGestures(name: String, mapping: () -> (Offset) -> Pair<Double, Double>, onLongPress: () -> Unit) {
    val client = EngineClient.front.windows
    var lastTap = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val toMudlet = mapping()
        val (x, y) = toMudlet(down.position)
        client.pointer(name, PointerType.ENTER, x, y)
        var touch = Touch.LONG_PRESS
        var last = down.position
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitChange(down.id)
                if (change == null) {
                    touch = Touch.CANCELLED
                    break
                }
                last = change.position
                if (!change.pressed) {
                    touch = Touch.TAP
                    break
                }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    touch = Touch.DRAG
                    break
                }
            }
        }
        when (touch) {
            Touch.TAP -> {
                if (lastTap != 0L && down.uptimeMillis - lastTap <= viewConfiguration.doubleTapTimeoutMillis) {
                    client.pointer(name, PointerType.DOUBLE_CLICK, x, y, POINTER_LEFT, POINTER_LEFT)
                    lastTap = 0L
                } else {
                    client.pointer(name, PointerType.PRESS, x, y, POINTER_LEFT, POINTER_LEFT)
                    lastTap = down.uptimeMillis
                }
                client.pointer(name, PointerType.RELEASE, x, y, POINTER_LEFT, 0)
            }

            Touch.DRAG -> {
                lastTap = 0L
                client.pointer(name, PointerType.PRESS, x, y, POINTER_LEFT, POINTER_LEFT)
                var at = toMudlet(last)
                client.pointer(name, PointerType.MOVE, at.first, at.second, 0, POINTER_LEFT)
                while (true) {
                    val change = awaitChange(down.id) ?: break
                    at = toMudlet(change.position)
                    if (!change.pressed) break
                    if (change.positionChanged()) client.pointer(name, PointerType.MOVE, at.first, at.second, 0, POINTER_LEFT)
                }
                client.pointer(name, PointerType.RELEASE, at.first, at.second, POINTER_LEFT, 0)
            }

            Touch.LONG_PRESS -> {
                lastTap = 0L
                client.pointer(name, PointerType.PRESS, x, y, POINTER_RIGHT, POINTER_RIGHT)
                onLongPress()
                while (awaitChange(down.id)?.pressed == true) {
                    // The finger stays down; the right button is let go when it lifts
                }
                client.pointer(name, PointerType.RELEASE, x, y, POINTER_RIGHT, 0)
            }

            Touch.CANCELLED -> lastTap = 0L
        }
    }
}

// The next change to the pointer [id], or null once it is gone
private suspend fun AwaitPointerEventScope.awaitChange(id: PointerId): PointerInputChange? = awaitPointerEvent().changes.firstOrNull { it.id == id }

/** A tool tip's place: above its label, or below it where there is no room above. */
private object AboveOrBelow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - popupContentSize.height
        return IntOffset(x, if (above >= 0) above else anchorBounds.bottom)
    }
}

/**
 * What a label draws, as Qt's QLabel would at [sx] by [sy] screen pixels to its own: its own colour, then its style
 * sheet's margin, background, border and padding, then its raster image or movie at its own size, or its SVG fitted
 * under its text.
 */
@Composable
private fun LabelBody(window: ScriptWindow, content: LabelContent?, text: LabelText, sx: Float, sy: Float, modifier: Modifier = Modifier) {
    val style = text.style
    val density = LocalDensity.current
    val scale = min(sx, sy)
    // The colour `setBackgroundColor()` gave the label, where its sheet draws no box of its own: Qt fills the whole
    // label with it then, its margin too, and draws the sheet's box without it
    val ownColour = window.bg.takeIf { it != -1 && !style.drawsBox }?.let { windowColour(it, Color.Transparent).copy(alpha = window.bgAlpha / 255f) }
    val borderImage = engineImage(style.borderImage?.let { ImageRequest(it) })
    val backgroundImage = engineImage(style.backgroundImage?.let { ImageRequest(it) })
    val border = style.drawnBorderWidth * scale
    val inner = style.padding + style.drawnBorderWidth
    val alignment = BiasAlignment(
        when (style.hAlign) {
            HAlign.CENTER -> 0f
            HAlign.RIGHT -> 1f
            else -> -1f
        },
        when (style.vAlign) {
            VAlign.TOP -> -1f
            VAlign.BOTTOM -> 1f
            else -> 0f
        },
    )

    Box(
        modifier
            .then(if (ownColour != null) Modifier.background(ownColour) else Modifier)
            .edgePadding(style.margin, sx, sy, density)
            .drawBehind {
                drawLabelFrame(style.background, style, border, style.borderRadius * scale, scale, backgroundImage, borderImage)
            }
            .edgePadding(inner, sx, sy, density),
    ) {
        val raster = content?.let(::rasterRequest)
        if (content != null && content.svg && content.image.isNotEmpty()) LabelSvg(content)
        if (raster != null) {
            engineImage(raster)?.let { image ->
                Box(Modifier.fillMaxSize().wrapContentSize(alignment, unbounded = true)) {
                    with(density) {
                        Image(
                            image,
                            contentDescription = null,
                            modifier = Modifier.size((image.width * sx).toDp(), (image.height * sy).toDp()),
                            contentScale = ContentScale.FillBounds,
                        )
                    }
                }
            }
        } else {
            LabelTextView(window.name, text, Modifier.fillMaxWidth().align(alignment))
        }
    }
}

// The image or movie frame shown in place of the label's text
private fun rasterRequest(content: LabelContent): ImageRequest? = when {
    content.movie.isNotEmpty() -> {
        val sized = content.movieW != null && content.movieH != null
        ImageRequest(content.movie, content.movieW.takeIf { sized }, content.movieH.takeIf { sized }, content.movieFrame.takeIf { content.moviePaused && it >= 0 })
    }

    content.image.isNotEmpty() && !content.svg -> ImageRequest(content.image)

    else -> null
}

/** [this] with [width] more on each side, as a border adds to a padding. */
internal operator fun Edges.plus(width: Float): Edges = Edges(top + width, right + width, bottom + width, left + width)

internal fun Modifier.edgePadding(edges: Edges, sx: Float, sy: Float, density: Density): Modifier = with(density) {
    absolutePadding(
        left = (edges.left * sx).coerceAtLeast(0f).toDp(),
        top = (edges.top * sy).coerceAtLeast(0f).toDp(),
        right = (edges.right * sx).coerceAtLeast(0f).toDp(),
        bottom = (edges.bottom * sy).coerceAtLeast(0f).toDp(),
    )
}

/** The engine's image for [request], asked for once of each engine instance; null until it comes, or for no request. */
@Composable
internal fun engineImage(request: ImageRequest?): ImageBitmap? {
    val session = EngineClient.front
    val client = session.windows
    // Kept while it is shown
    DisposableEffect(request, client) {
        request?.let(client::imageShown)
        onDispose { request?.let(client::imageHidden) }
    }
    // A new engine instance is asked again
    LaunchedEffect(request, client, session.attaches) { request?.let(client::requestImage) }
    return request?.let { client.images[it] }
}

/** A label's background, its images and its border, as its style sheet sets them; a text box's too, with no images. */
internal fun DrawScope.drawLabelFrame(
    fill: LabelFill?,
    style: LabelStyle,
    border: Float,
    radius: Float,
    scale: Float,
    backgroundImage: ImageBitmap?,
    borderImage: ImageBitmap?,
) {
    val corner = CornerRadius(radius)
    when (fill) {
        is LabelFill.Solid -> drawRoundRect(Color(fill.argb), cornerRadius = corner)

        is LabelFill.Gradient -> drawRoundRect(
            Brush.linearGradient(
                *fill.stops.map { (at, argb) -> at to Color(argb) }.toTypedArray(),
                start = Offset(fill.x1 * size.width, fill.y1 * size.height),
                end = Offset(fill.x2 * size.width, fill.y2 * size.height),
            ),
            cornerRadius = corner,
        )

        null -> {}
    }
    if (backgroundImage != null) {
        // Tiled from the top left, each tile at its own size
        val tileW = backgroundImage.width * scale
        val tileH = backgroundImage.height * scale
        if (tileW >= 1f && tileH >= 1f && (size.width / tileW) * (size.height / tileH) <= MAX_TILES) {
            val dst = IntSize(tileW.roundToInt(), tileH.roundToInt())
            var y = 0f
            while (y < size.height) {
                var x = 0f
                while (x < size.width) {
                    drawImage(backgroundImage, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = dst)
                    x += tileW
                }
                y += tileH
            }
        }
    }
    if (borderImage != null) drawImage(borderImage, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    if (border > 0f) {
        drawRoundRect(
            Color(style.borderColor ?: style.color ?: CONSOLE_FOREGROUND.toArgb()),
            topLeft = Offset(border / 2, border / 2),
            size = Size((size.width - border).coerceAtLeast(0f), (size.height - border).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((radius - border / 2).coerceAtLeast(0f)),
            style = Stroke(border),
        )
    }
}

/**
 * The label's SVG, drawn under its text as Qt draws it: fitted to the label keeping its shape, centred, turned by its
 * rotation and sheared about the centre, and tinted. The engine draws it at the size it is shown, once its own size
 * says what that is.
 */
@Composable
private fun LabelSvg(content: LabelContent) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val natural = engineImage(ImageRequest(content.image)) ?: return@BoxWithConstraints
        val fit = min(constraints.maxWidth.toFloat() / natural.width, constraints.maxHeight.toFloat() / natural.height)
        if (fit <= 0f || fit.isInfinite() || fit.isNaN()) return@BoxWithConstraints
        val w = (natural.width * fit).roundToInt().coerceIn(1, 4096)
        val h = (natural.height * fit).roundToInt().coerceIn(1, 4096)
        val image = engineImage(ImageRequest(content.image, w, h)) ?: natural
        val tint = content.svgTint.takeIf { it != -1 }?.let {
            ColorFilter.tint(windowColour(it, Color.Transparent).copy(alpha = content.svgTintAlpha / 255f), BlendMode.SrcIn)
        }
        Canvas(Modifier.fillMaxSize()) {
            val topLeft = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt())
            val pivot = center
            withTransform({
                rotate(content.svgRotation.toFloat(), pivot)
                if (content.svgShearX != 0.0 || content.svgShearY != 0.0) {
                    translate(pivot.x, pivot.y)
                    transform(
                        Matrix().apply {
                            values[Matrix.SkewX] = content.svgShearX.toFloat()
                            values[Matrix.SkewY] = content.svgShearY.toFloat()
                        },
                    )
                    translate(-pivot.x, -pivot.y)
                }
            }) {
                drawImage(image, dstOffset = topLeft, dstSize = IntSize(w, h), colorFilter = tint)
            }
        }
    }
}

/** The label's rich text, unwrapped as QLabel draws it, with its links followed when tapped, where they are followed. */
@Composable
private fun LabelTextView(label: String, text: LabelText, modifier: Modifier = Modifier) {
    val rich = text.rich ?: return
    val style = text.style
    val context = LocalContext.current
    val density = LocalDensity.current
    val scale = text.scale
    val toSp: (Float) -> TextUnit = { px -> with(density) { (px * scale).toSp() } }
    // Keyed on the whole of what the label's text is drawn from, so a change to any part of it, as to whether its links
    // are followed, redraws it
    val annotated = remember(label, text, density) {
        buildAnnotatedString {
            for (block in rich.blocks) {
                withStyle(ParagraphStyle(textAlign = textAlign(block.align ?: style.hAlign))) {
                    for (span in block.spans) {
                        val href = span.style.href
                        val link = if (href != null && text.linksFollowable) LinkAnnotation.Clickable(href, linkInteractionListener = { EngineClient.front.windows.followLabelLink(context, label, href) }) else null
                        if (link != null) pushLink(link)
                        // Drawn as a link whether or not it is followed, as QLabel draws one whatever its text interaction
                        withStyle(spanStyle(span.style, toSp, link = href != null)) { append(span.text) }
                        if (link != null) pop()
                    }
                }
            }
        }
    }
    val base = TextStyle(
        color = style.color?.let { Color(it) } ?: CONSOLE_FOREGROUND,
        fontSize = toSp(style.fontSizePx ?: (rich.blocks.firstOrNull()?.spans?.firstOrNull()?.style?.sizePx ?: DEFAULT_FONT_PX)),
        fontWeight = style.fontWeight?.let { FontWeight(it.coerceIn(1, 1000)) },
        fontStyle = if (style.italic == true) FontStyle.Italic else null,
        fontFamily = style.fontFamily?.let(::fontFamily),
        textAlign = textAlign(style.hAlign),
    )
    Text(annotated, modifier, style = base, softWrap = false, overflow = TextOverflow.Clip)
}

private fun textAlign(align: HAlign?): TextAlign = when (align) {
    HAlign.CENTER -> TextAlign.Center
    HAlign.RIGHT -> TextAlign.Right
    HAlign.JUSTIFY -> TextAlign.Justify
    else -> TextAlign.Left
}

private fun spanStyle(style: RichText.Style, toSp: (Float) -> TextUnit, link: Boolean = false): SpanStyle {
    val decorations = listOfNotNull(
        TextDecoration.Underline.takeIf { style.underline || link },
        TextDecoration.LineThrough.takeIf { style.strike },
    )
    return SpanStyle(
        color = style.color?.let { Color(it) } ?: if (link) LINK_COLOUR else Color.Unspecified,
        background = style.background?.let { Color(it) } ?: Color.Unspecified,
        fontSize = toSp(style.sizePx),
        fontWeight = if (style.bold) FontWeight.Bold else null,
        fontStyle = if (style.italic) FontStyle.Italic else null,
        fontFamily = style.family?.let(::fontFamily),
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}

// The nearest of the device's fonts to a family a script names; null for its default
internal fun fontFamily(name: String): FontFamily? {
    val n = name.lowercase()
    return when {
        "mono" in n || "courier" in n || "consol" in n || "fixed" in n -> FontFamily.Monospace
        "sans" in n -> FontFamily.SansSerif
        "serif" in n || "times" in n || "georgia" in n -> FontFamily.Serif
        "cursive" in n || "comic" in n -> FontFamily.Cursive
        else -> null
    }
}
