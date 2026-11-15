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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
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
import org.mudlet.android.protocol.MapPointerType
import org.mudlet.android.protocol.PlacedWindow
import org.mudlet.android.protocol.PxRect
import org.mudlet.android.protocol.ZoomNotches
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ScriptWindowsClient
import kotlin.math.roundToInt

// Each tap of a zoom button turns the wheel this many notches, zooming the map by about a fifth
private const val BUTTON_NOTCHES = 3

/**
 * The mapper window (docs/architecture.md, "The map") at [placed]'s place, where [modifier] puts it: the engine's last
 * `map`, scaled alike both ways to fit, while it is the window's size. A tap selects the room under it, a double tap
 * walks there and a pinch zooms about the fingers, each sent as `mapPointer` at that point of the map. In its corner
 * are buttons that zoom about its middle and switch between viewing and editing it, and at its bottom why the engine
 * refused a request. TalkBack reads it as the map, viewed or edited, whose actions select or walk to the room in its
 * middle and zoom.
 */
@Composable
internal fun ScriptMap(placed: PlacedWindow, modifier: Modifier = Modifier) {
    val client = ScriptWindowsClient
    val window = placed.window
    val rect = placed.rect
    val full = placed.full
    val map = client.map
    // One of another size is from before the mapper changed size (docs/protocol.md, "The map")
    val frame: ImageBitmap? = map?.bitmap?.takeIf { map.w == window.w && map.h == window.h }
    // In this node's pixels: the window as a whole, of which [rect] may show only a part
    val fit = remember(window.w, window.h, rect, full) { MapFit(window.w, window.h, PxRect(full.x - rect.x, full.y - rect.y, full.w, full.h)) }
    val touchable = frame != null && EngineClient.enginePid > 0
    val currentFit by rememberUpdatedState(fit.takeIf { touchable })
    val viewOnly = map?.viewOnly ?: true

    val description = stringResource(if (frame != null) R.string.map_description else R.string.map_not_drawn)
    val state = stringResource(if (viewOnly) R.string.map_viewing else R.string.map_editing)
    val selectLabel = stringResource(R.string.map_select_middle)
    val actions = listOf(
        stringResource(R.string.map_walk_to_middle) to { client.mapPointer(MapPointerType.DOUBLE_CLICK, fit.centre) },
        stringResource(R.string.map_zoom_in) to { zoom(fit, 1) },
        stringResource(R.string.map_zoom_out) to { zoom(fit, -1) },
    ).map { (label, act) -> CustomAccessibilityAction(label, act) }

    Box(
        modifier.background(CONSOLE_BACKGROUND).semantics {
            isTraversalGroup = true
            traversalIndex = WINDOWS_TRAVERSAL_INDEX
        },
    ) {
        Canvas(
            Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) { mapGestures { currentFit } }.clearAndSetSemantics {
                contentDescription = description
                stateDescription = state
                if (touchable) {
                    onClick(selectLabel) { client.mapPointer(MapPointerType.PRESS, fit.centre) }
                    customActions = actions
                }
            },
        ) {
            if (frame != null) {
                val drawn = fit.drawn
                drawImage(
                    frame,
                    dstOffset = IntOffset(drawn.x.roundToInt(), drawn.y.roundToInt()),
                    dstSize = IntSize(drawn.w.roundToInt(), drawn.h.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
        MapControls(
            zoomEnabled = touchable,
            viewOnly = viewOnly,
            toggleEnabled = map != null && EngineClient.enginePid > 0,
            onZoom = { zoom(fit, it) },
            Modifier.align(Alignment.TopEnd),
        )
        client.mapRefusal?.let { MapRefusal(it, Modifier.align(Alignment.BottomStart)) }
    }
}

// Zooms in ([direction] 1) or out (-1) about the map's middle, as the zoom buttons do
private fun zoom(fit: MapFit, direction: Int) = ScriptWindowsClient.mapPointer(MapPointerType.WHEEL, fit.centre, direction * BUTTON_NOTCHES * ZoomNotches.WHEEL_NOTCH)

/**
 * The player's touches on the map, at the points of the frame [fit] gives, while it gives one: a tap is a press, a
 * second tap soon after a double click, and a pinch turns the wheel about the middle of the fingers, or the map's
 * middle when that is off it. A drag does nothing, as the map's drags are the GUI's alone.
 */
private suspend fun PointerInputScope.mapGestures(fit: () -> MapFit?) {
    val client = ScriptWindowsClient
    var lastTapUp = 0L
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
                if (turn != 0) {
                    val centroid = event.calculateCentroid()
                    fit()?.let { it.toFrame(centroid.x, centroid.y) ?: it.centre }?.let { client.mapPointer(MapPointerType.WHEEL, it, turn * ZoomNotches.WHEEL_NOTCH) }
                }
            } else if ((pressed[0].position - down.position).getDistance() > viewConfiguration.touchSlop) {
                tap = false
            }
        }
        val at = if (tap) fit()?.toFrame(down.position.x, down.position.y) else null
        if (at == null) {
            lastTapUp = 0L
        } else if (lastTapUp != 0L && down.uptimeMillis - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis) {
            client.mapPointer(MapPointerType.DOUBLE_CLICK, at)
            lastTapUp = 0L
        } else {
            client.mapPointer(MapPointerType.PRESS, at)
            lastTapUp = upAt
        }
    }
}

/** The zoom buttons, which zoom about the map's middle, and the switch between viewing the map and editing it. */
@Composable
private fun MapControls(zoomEnabled: Boolean, viewOnly: Boolean, toggleEnabled: Boolean, onZoom: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Never squeezed by a small map: they keep their 48 dp, reaching past its left edge
    Surface(
        modifier.wrapContentSize(Alignment.TopEnd, unbounded = true).padding(4.dp),
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
            IconToggleButton(checked = !viewOnly, onCheckedChange = { ScriptWindowsClient.setMapViewOnly(!it) }, enabled = toggleEnabled) {
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.map_edit))
            }
        }
    }
}

/** Why the engine did not do what the map asked, in its words, read out as it comes, until dismissed or the next request. */
@Composable
private fun MapRefusal(text: String, modifier: Modifier = Modifier) {
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
            IconButton(ScriptWindowsClient::dismissMapRefusal) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.map_refusal_dismiss), tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}

private const val CONTROLS_ALPHA = 0.85f
