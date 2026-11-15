package org.mudlet.android.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.MapFit
import org.mudlet.android.protocol.MapPoint
import org.mudlet.android.protocol.MapPointerType
import org.mudlet.android.protocol.PlacedWindow
import org.mudlet.android.protocol.PxRect
import org.mudlet.android.protocol.ZoomNotches
import org.mudlet.android.ui.AppMapClient
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ScriptWindowsClient
import kotlin.math.roundToInt

// Each tap of a zoom button turns the wheel this many notches, zooming the map by about a fifth
private const val BUTTON_NOTCHES = 3

// The buttons' backing lets a little of the map show through
private const val CONTROLS_ALPHA = 0.85f

// The space round the buttons' backing
private val CONTROLS_PADDING = 4.dp

/** How far down the map its buttons reach, from its top: their 48 dp and the space round them. */
internal val MAP_CONTROLS_HEIGHT = 48.dp + CONTROLS_PADDING * 2

// A second tap further than this from the first is a tap of its own, as Android's and Qt's double-tap slop has it
private val DOUBLE_TAP_SLOP = 100.dp

/** Where a drawn map's touches and buttons go: the mapper window's map ([ScriptWindowsClient]) or the app's ([AppMapClient]). */
internal interface MapSource {
    /** Why the engine refused the last request about this map, until the next one or [dismissRefusal]. */
    val refusal: String?

    /**
     * What the player did on the map at [at], in the pixels of its last frame: a tap selects the room there ([type]
     * press), a double tap walks to it, and a wheel turn of [wheelY] zooms about it. False as for [EngineClient.send].
     */
    fun pointer(type: MapPointerType, at: MapPoint, wheelY: Int = 0): Boolean

    /** The map viewed only, or edited; the next frame says which it is. False as for [EngineClient.send]. */
    fun setViewOnly(viewOnly: Boolean): Boolean

    /** The player read [refusal]. */
    fun dismissRefusal()
}

// The mapper window's requests, which ScriptWindowsClient sends
private object MapperWindow : MapSource {
    override val refusal: String? get() = ScriptWindowsClient.mapRefusal

    override fun pointer(type: MapPointerType, at: MapPoint, wheelY: Int) = ScriptWindowsClient.mapPointer(type, at, wheelY)

    override fun setViewOnly(viewOnly: Boolean) = ScriptWindowsClient.setMapViewOnly(viewOnly)

    override fun dismissRefusal() = ScriptWindowsClient.dismissMapRefusal()
}

/**
 * The mapper window (docs/architecture.md, "The map") at [placed]'s place, where [modifier] puts it: the engine's last
 * `map`, while it is the window's size, drawn as [MapView] draws it.
 */
@Composable
internal fun ScriptMap(placed: PlacedWindow, modifier: Modifier = Modifier) {
    val window = placed.window
    val rect = placed.rect
    val full = placed.full
    val map = ScriptWindowsClient.map
    // One of another size is from before the mapper changed size (docs/protocol.md, "The map")
    val frame: ImageBitmap? = map?.bitmap?.takeIf { map.w == window.w && map.h == window.h }
    // In this node's pixels: the window as a whole, of which [rect] may show only a part
    val fit = remember(window.w, window.h, rect, full) { MapFit(window.w, window.h, PxRect(full.x - rect.x, full.y - rect.y, full.w, full.h)) }
    MapView(
        MapperWindow,
        frame,
        fit,
        viewOnly = map?.viewOnly ?: true,
        drawn = map != null,
        modifier = modifier.background(CONSOLE_BACKGROUND).semantics {
            isTraversalGroup = true
            traversalIndex = WINDOWS_TRAVERSAL_INDEX
        },
    )
}

/**
 * A map the engine draws, the mapper window's or the app's: [frame] drawn where [fit] puts it, scaled alike both ways.
 * A tap selects the room under it, a double tap walks there and a pinch zooms about the fingers, each sent to [source]
 * at that point of the frame. In its corner are buttons that zoom about its middle and switch between viewing
 * ([viewOnly]) and editing it, which works once the engine has drawn the map ([drawn]), and at its bottom why the engine
 * refused a request. TalkBack reads it as the map, viewed or edited, whose actions select or walk to the room in its
 * middle and zoom.
 */
@Composable
internal fun MapView(source: MapSource, frame: ImageBitmap?, fit: MapFit, viewOnly: Boolean, drawn: Boolean, modifier: Modifier = Modifier) {
    val touchable = frame != null && EngineClient.enginePid > 0
    val currentFit by rememberUpdatedState(fit.takeIf { touchable })

    val description = stringResource(if (frame != null) R.string.map_description else R.string.map_not_drawn)
    val state = stringResource(if (viewOnly) R.string.map_viewing else R.string.map_editing)
    val selectLabel = stringResource(R.string.map_select_middle)
    val actions = listOf(
        CustomAccessibilityAction(stringResource(R.string.map_walk_to_middle)) { source.pointer(MapPointerType.DOUBLE_CLICK, fit.centre) },
        CustomAccessibilityAction(stringResource(R.string.map_zoom_in)) { source.zoom(fit, 1) },
        CustomAccessibilityAction(stringResource(R.string.map_zoom_out)) { source.zoom(fit, -1) },
    )

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().clipToBounds().pointerInput(source) { mapGestures(source) { currentFit } }.clearAndSetSemantics {
                contentDescription = description
                stateDescription = state
                if (touchable) {
                    onClick(selectLabel) { source.pointer(MapPointerType.PRESS, fit.centre) }
                    customActions = actions
                }
            },
        ) {
            if (frame != null) drawFrame(frame, fit)
        }
        MapControls(
            zoomEnabled = touchable,
            viewOnly = viewOnly,
            toggleEnabled = drawn && EngineClient.enginePid > 0,
            onZoom = { source.zoom(fit, it) },
            onViewOnly = source::setViewOnly,
            Modifier.align(Alignment.TopEnd),
        )
        source.refusal?.let { MapRefusal(it, source::dismissRefusal, Modifier.align(Alignment.BottomStart)) }
    }
}

/** [frame] where [fit] puts it, in the canvas's pixels. */
internal fun DrawScope.drawFrame(frame: ImageBitmap, fit: MapFit, alpha: Float = 1f) {
    val drawn = fit.drawn
    drawImage(
        frame,
        dstOffset = IntOffset(drawn.x.roundToInt(), drawn.y.roundToInt()),
        dstSize = IntSize(drawn.w.roundToInt(), drawn.h.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Medium,
    )
}

// Zooms in ([direction] 1) or out (-1) about the map's middle, as the zoom buttons do
private fun MapSource.zoom(fit: MapFit, direction: Int) = pointer(MapPointerType.WHEEL, fit.centre, direction * BUTTON_NOTCHES * ZoomNotches.WHEEL_NOTCH)

/**
 * The player's touches on the map, sent to [source] at the points of the frame [fit] gives, while it gives one: a tap is
 * a press, a second tap soon after and near it a double click, and a pinch turns the wheel about the middle of the
 * fingers, or the map's middle when that is off it. A drag does nothing, as the map's drags are the GUI's alone.
 */
private suspend fun PointerInputScope.mapGestures(source: MapSource, fit: () -> MapFit?) {
    val slop = DOUBLE_TAP_SLOP.toPx()
    var lastTapUp = 0L
    var lastTapAt = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        var tap = true
        var upAt = down.uptimeMillis
        val notches = ZoomNotches()
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                upAt = event.changes.firstOrNull()?.uptimeMillis ?: upAt
                break
            }
            if (pressed.size >= 2) {
                tap = false
                val turn = notches.add(event.calculateZoom())
                val current = fit()
                if (turn != 0 && current != null) {
                    val centroid = event.calculateCentroid()
                    source.pointer(MapPointerType.WHEEL, current.toFrame(centroid.x, centroid.y) ?: current.centre, turn * ZoomNotches.WHEEL_NOTCH)
                }
            } else if ((pressed[0].position - down.position).getDistance() > viewConfiguration.touchSlop) {
                tap = false
            }
        }
        val at = if (tap) fit()?.toFrame(down.position.x, down.position.y) else null
        if (at == null) {
            lastTapUp = 0L
        } else if (lastTapUp != 0L && down.uptimeMillis - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis && (down.position - lastTapAt).getDistance() <= slop) {
            source.pointer(MapPointerType.DOUBLE_CLICK, at)
            lastTapUp = 0L
        } else {
            source.pointer(MapPointerType.PRESS, at)
            lastTapUp = upAt
            lastTapAt = down.position
        }
    }
}

/** The zoom buttons, which zoom about the map's middle, and the switch between viewing the map and editing it. */
@Composable
private fun MapControls(zoomEnabled: Boolean, viewOnly: Boolean, toggleEnabled: Boolean, onZoom: (Int) -> Unit, onViewOnly: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    // Never squeezed by a small map: they keep their 48 dp, reaching past its left edge
    Surface(
        modifier.wrapContentSize(Alignment.TopEnd, unbounded = true).padding(CONTROLS_PADDING),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = CONTROLS_ALPHA),
        // Not the surface's own colour, which contentColorFor() would not know as it
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row {
            IconButton({ onZoom(-1) }, enabled = zoomEnabled) {
                Icon(painterResource(R.drawable.ic_zoom_out), stringResource(R.string.map_zoom_out))
            }
            IconButton({ onZoom(1) }, enabled = zoomEnabled) {
                Icon(painterResource(R.drawable.ic_zoom_in), stringResource(R.string.map_zoom_in))
            }
            // On while the map is edited; the engine's next map says whether it now is
            IconToggleButton(checked = !viewOnly, onCheckedChange = { onViewOnly(!it) }, enabled = toggleEnabled) {
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.map_edit))
            }
        }
    }
}

/** Why the engine did not do what the map asked, in its words, read out as it comes, until dismissed or the next request. */
@Composable
private fun MapRefusal(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.padding(4.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.inverseSurface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.map_refused, text),
                Modifier.weight(1f, fill = false).padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onDismiss) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.map_refusal_dismiss), tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}
