package org.mudlet.android.ui.game

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.semantics.traversalIndex
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
import androidx.compose.ui.text.withLink
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
import org.mudlet.android.protocol.Edges
import org.mudlet.android.protocol.Gauge
import org.mudlet.android.protocol.HAlign
import org.mudlet.android.protocol.LabelContent
import org.mudlet.android.protocol.LabelFill
import org.mudlet.android.protocol.LabelStyle
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
import org.mudlet.android.ui.ScriptWindowsClient
import org.mudlet.android.ui.ScriptWindowsClient.ImageRequest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ScriptLabel"

// A clickable label's touch bounds are never smaller than this, around it
private val MIN_TOUCH_SIZE = 48.dp

// Qt's default label font, where neither the label's style sheet nor its window sets one: 10 pt
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
 * name is left out. Of a [gauge]'s three labels, its back is read as the gauge's progress, and the other two not at all.
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

/** This rectangle grown about its centre to at least [min] pixels each way. */
private fun PxRect.grownTo(min: Float): PxRect {
    val w2 = max(w, min)
    val h2 = max(h, min)
    return PxRect(x - (w2 - w) / 2, y - (h2 - h) / 2, w2, h2)
}

/** Lays its content out [w] by [h] pixels at [x], [y] in its parent, whatever the parent's size. */
private fun Modifier.placeIn(x: Float, y: Float, w: Float, h: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints.fixed(w.roundToInt().coerceAtLeast(0), h.roundToInt().coerceAtLeast(0)))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
}

@Composable
private fun rememberLabelText(content: LabelContent?, window: ScriptWindow, scale: Float): LabelText {
    val styleSheet = content?.styleSheet.orEmpty()
    val style = remember(styleSheet) { LabelStyle.parse(styleSheet) }
    val basePx = style.fontSizePx ?: window.fontSize.takeIf { it > 0 }?.let { (it * LabelStyle.PX_PER_PT).toFloat() } ?: DEFAULT_FONT_PX
    val text = content?.text.orEmpty()
    val rich = remember(text, basePx) { if (text.isBlank()) null else RichText.parse(text, basePx) }
    return remember(rich, style, scale) { LabelText(style, rich, scale) }
}

/** A label's style sheet, its text if it has any, and the scale it is drawn at. */
private class LabelText(val style: LabelStyle, val rich: RichText?, val scale: Float) {
    val plain: String = rich?.plain.orEmpty()
    val links: List<Pair<String, String>> = rich?.links.orEmpty()
}

/**
 * What a screen reader is told of the label, as docs/phase-f.md's "Touch and TalkBack" says: a button its click and
 * right-click, which send the tap's and the long press's `pointer` events to the label's centre, and its links as
 * actions; a gauge its progress. Null for a label read by its text, as drawn; nothing set for one left out.
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
            ScriptWindowsClient.followLabelLink(context, href)
            true
        }
    }
    val plain = text.plain
    val accessibleName = content?.accessibleName.orEmpty()
    val button = content?.accessibleButton == true
    val name = window.name

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

    if (gauge != null) {
        if (gauge.back.key != window.key) return {}
        val gaugeText = gauge.text?.let { ScriptWindowsClient.state.labels[it.name] }?.text?.let { RichText.parse(it, DEFAULT_FONT_PX).plain }
        val state = gaugeText?.ifBlank { null } ?: stringResource(R.string.gauge_percent, gauge.percent)
        return {
            contentDescription = accessibleName.ifEmpty { gauge.name }
            progressBarRangeInfo = ProgressBarRangeInfo(gauge.percent.toFloat(), 0f..100f)
            stateDescription = state
            if (button) asButton()
            inOrder()
        }
    }
    if (button) {
        val label = accessibleName.ifEmpty { plain }.ifEmpty { tooltip }
        if (label.isEmpty()) {
            LaunchedEffect(name) { Log.w(TAG, "the clickable label $name has no accessible name, text or tool tip: setLabelAccessibleName() gives it one") }
        }
        return {
            contentDescription = label.ifEmpty { name }
            asButton()
            if (linkActions.isNotEmpty()) customActions = linkActions
            inOrder()
        }
    }
    return when {
        // Its text, links and all, read as the text draws it
        accessibleName.isEmpty() && plain.isNotEmpty() -> null

        accessibleName.isNotEmpty() -> {
            {
                contentDescription = accessibleName
                if (linkActions.isNotEmpty()) customActions = linkActions
                inOrder()
            }
        }

        // Only a colour or an image: decoration
        else -> {
            {}
        }
    }
}

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
    ScriptWindowsClient.pointer(label, PointerType.ENTER, x, y)
    ScriptWindowsClient.pointer(label, PointerType.PRESS, x, y, button, button)
    ScriptWindowsClient.pointer(label, PointerType.RELEASE, x, y, button, 0)
}

private enum class Touch { TAP, DRAG, LONG_PRESS, CANCELLED }

/**
 * The player's touches on the label [name] as a mouse's, by docs/phase-f.md's "Labels": a tap is the left button's
 * press and release, a second tap soon after a double click, a long press the right button's (and [onLongPress]), and
 * a drag the left button held as it moves. Nothing is consumed, so the label's links still see their taps.
 */
private suspend fun PointerInputScope.labelGestures(name: String, mapping: () -> (Offset) -> Pair<Double, Double>, onLongPress: () -> Unit) {
    val client = ScriptWindowsClient
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
 * What a label draws, as Qt's QLabel would at [sx] by [sy] screen pixels to its own: its style sheet's margin,
 * background, border and padding, then its raster image or movie at its own size, or its SVG fitted under its text.
 */
@Composable
private fun LabelBody(window: ScriptWindow, content: LabelContent?, text: LabelText, sx: Float, sy: Float, modifier: Modifier = Modifier) {
    val style = text.style
    val density = LocalDensity.current
    val scale = min(sx, sy)
    val fill = style.background ?: window.bg.takeIf { it != -1 }?.let {
        LabelFill.Solid(windowColour(it, Color.Transparent).copy(alpha = window.bgAlpha / 255f).toArgb())
    }
    val borderImage = engineImage(style.borderImage?.let { ImageRequest(it) })
    val backgroundImage = engineImage(style.backgroundImage?.let { ImageRequest(it) })
    val border = style.drawnBorderWidth * scale
    val inner = Edges(
        style.padding.top + style.drawnBorderWidth,
        style.padding.right + style.drawnBorderWidth,
        style.padding.bottom + style.drawnBorderWidth,
        style.padding.left + style.drawnBorderWidth,
    )
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
            .edgePadding(style.margin, sx, sy, density)
            .drawBehind {
                drawLabelFrame(fill, style, border, style.borderRadius * scale, scale, backgroundImage, borderImage)
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
        } else if (text.rich != null) {
            LabelTextView(text, text.rich, style, Modifier.fillMaxWidth().align(alignment))
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

private fun Modifier.edgePadding(edges: Edges, sx: Float, sy: Float, density: Density): Modifier = with(density) {
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
    // Kept while it is shown
    DisposableEffect(request) {
        request?.let(ScriptWindowsClient::imageShown)
        onDispose { request?.let(ScriptWindowsClient::imageHidden) }
    }
    // A new engine instance is asked again
    LaunchedEffect(request, EngineClient.attachCount) { request?.let(ScriptWindowsClient::requestImage) }
    return request?.let { ScriptWindowsClient.images[it] }
}

private fun DrawScope.drawLabelFrame(
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

/** The label's rich text, unwrapped as QLabel draws it, with its links followed when tapped. */
@Composable
private fun LabelTextView(text: LabelText, rich: RichText, style: LabelStyle, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scale = text.scale
    val toSp: (Float) -> TextUnit = { px -> with(density) { (px * scale).toSp() } }
    val annotated = remember(rich, style, scale, density) {
        buildAnnotatedString {
            for (block in rich.blocks) {
                withStyle(ParagraphStyle(textAlign = textAlign(block.align ?: style.hAlign))) {
                    for (span in block.spans) {
                        val href = span.style.href
                        if (href == null) {
                            withStyle(spanStyle(span.style, toSp)) { append(span.text) }
                        } else {
                            val link = LinkAnnotation.Clickable(href, linkInteractionListener = { ScriptWindowsClient.followLabelLink(context, href) })
                            withLink(link) {
                                withStyle(spanStyle(span.style, toSp, link = true)) { append(span.text) }
                            }
                        }
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
private fun fontFamily(name: String): FontFamily? {
    val n = name.lowercase()
    return when {
        "mono" in n || "courier" in n || "consol" in n || "fixed" in n -> FontFamily.Monospace
        "sans" in n -> FontFamily.SansSerif
        "serif" in n || "times" in n || "georgia" in n -> FontFamily.Serif
        "cursive" in n || "comic" in n -> FontFamily.Cursive
        else -> null
    }
}
