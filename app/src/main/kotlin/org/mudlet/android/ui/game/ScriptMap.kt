package org.mudlet.android.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.protocol.MapCorner
import org.mudlet.android.protocol.MapFit
import org.mudlet.android.protocol.MapInfo
import org.mudlet.android.protocol.MapPoint
import org.mudlet.android.protocol.MapPointerType
import org.mudlet.android.protocol.MapRoomPlace
import org.mudlet.android.protocol.MapShift
import org.mudlet.android.protocol.MapViewInfo
import org.mudlet.android.protocol.PlacedWindow
import org.mudlet.android.protocol.PxRect
import org.mudlet.android.protocol.RoomEdit
import org.mudlet.android.protocol.RoomInfo
import org.mudlet.android.protocol.ZoomNotches
import org.mudlet.android.protocol.wholeIn
import org.mudlet.android.ui.AppMapClient
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MapMover
import org.mudlet.android.ui.MapRooms
import org.mudlet.android.ui.ScriptWindowsClient
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

// Each tap of a zoom button zooms the map by a quarter, or, with no view to move, turns the wheel this many notches
private const val BUTTON_ZOOM = 1.25
private const val BUTTON_NOTCHES = 3

// TalkBack's moves go a third of the map's narrow side
private const val PAN_FRACTION = 1.0 / 3

// The area and level bar's backing lets a little of the map show through
private const val CONTROLS_ALPHA = 0.85f

// The space round the area and level bar's backing, and inside a map panel's (MapCorner.ROW_DP)
private val CONTROLS_PADDING = 4.dp

// The button over a map in a corner that shows its controls: drawn this large, its icon this large, touched within this
private val CONTROLS_BUTTON_SIZE = 32.dp
private val CONTROLS_ICON_SIZE = 20.dp
private val CONTROLS_BUTTON_TOUCH = 48.dp

// A second tap further than this from the first is a tap of its own, as Android's and Qt's double-tap slop has it
private val DOUBLE_TAP_SLOP = 100.dp

// A tap goes to the room nearest it within this, as a room at a low zoom is smaller than a finger
private val TAP_REACH = 24.dp

// A double tap whose second touch drags this far down zooms in by e, and up as far out
private val DRAG_ZOOM_DISTANCE = 100.dp

// The outline of where a room dragged on an edited map goes
private val GHOST_STROKE = 3.dp

// A drag let go slower than this a second does not glide on
private val MIN_FLING_SPEED = 50.dp

// The button that centres the map on the player: its space from the map's corner, and the room the bar beside it leaves it
private val FOLLOW_PADDING = 8.dp
private val FOLLOW_SPACE = 48.dp + FOLLOW_PADDING * 2

// The area and level bar shows on a map at least this high; the room picked takes at most this much of the map's height
private val PLACE_BAR_LEAST_HEIGHT = 144.dp
private const val ROOM_CARD_FRACTION = 0.5f

// The most of the map's height its info takes, so that a script's long one leaves the map in sight
private const val INFO_FRACTION = 0.4f

// The most of a mapper window its panel takes, above the map, where it has no room beside the window
private const val PANEL_FRACTION = 0.6f

// TalkBack's reach is worked out against a fit at least this large, so a map not laid out yet does not divide by 0
private const val MIN_FIT_SCALE = 0.01f

private enum class Gesture { TAP, PAN, PINCH, DRAG_ZOOM, MOVE_ROOM }

// The room picked, [room], at [place] in the last frame, dragged on an edited map from [from] to [to], points of the map as shown
private data class RoomDrag(val room: RoomInfo, val place: MapRoomPlace, val from: MapPoint, val to: MapPoint)

/** Where a drawn map's touches and buttons go: the mapper window's map ([ScriptWindowsClient]) or the app's ([AppMapClient]). */
internal interface MapSource {
    /** Why the engine refused the last request about this map, until the next one or [dismissRefusal]. */
    val refusal: String?

    /**
     * What the player did on the map at [at], in the pixels of its last frame: a tap selects the room there ([type]
     * press), a double tap walks to it, and a wheel turn of [wheelY] zooms about it. False as for [org.mudlet.android.ui.ProfileSession.send].
     */
    fun pointer(type: MapPointerType, at: MapPoint, wheelY: Int = 0): Boolean

    /** The map viewed only, or edited; the next frame says which it is. False as for [org.mudlet.android.ui.ProfileSession.send]. */
    fun setViewOnly(viewOnly: Boolean): Boolean

    /** The player read [refusal]. */
    fun dismissRefusal()

    /** The map's moves by touch. */
    val mover: MapMover

    /** The map's rooms and areas the player asks about. */
    val rooms: MapRooms
}

// The mapper window's requests, which ScriptWindowsClient sends
private object MapperWindow : MapSource {
    override val refusal: String? get() = EngineClient.front.windows.mapRefusal

    override fun pointer(type: MapPointerType, at: MapPoint, wheelY: Int) = EngineClient.front.windows.mapPointer(type, at, wheelY)

    override fun setViewOnly(viewOnly: Boolean) = EngineClient.front.windows.setMapViewOnly(viewOnly)

    override fun dismissRefusal() = EngineClient.front.windows.dismissMapRefusal()

    override val mover: MapMover get() = EngineClient.front.windows.mapMover

    override val rooms: MapRooms get() = EngineClient.front.windows.mapRooms
}

/**
 * The mapper window (docs/architecture.md, "The map") shown at [placed]'s rect: the engine's last `map`, while it is the
 * window's size, drawn as [MapView] draws it, which the engine is asked to draw at the scale it is shown at, and its
 * [MapPanel], which covers none of it. With room beside the window for the panel, the two are where [corner] says, a
 * window small for the game area drawn larger, and the panel shows on request ([CornerMap]); without, the map fills the
 * window's rect below its panel. One group for TalkBack, the map before its panel, after the game screen's own
 * controls; [modifier] goes on the group.
 */
@Composable
internal fun ScriptMap(placed: PlacedWindow, corner: MapCorner?, density: Density, modifier: Modifier = Modifier) {
    val window = placed.window
    val map = EngineClient.front.windows.map
    // One of another size is from before the mapper changed size (docs/protocol.md, "The map")
    val frame: ImageBitmap? = map?.bitmap?.takeIf { map.w == window.w && map.h == window.h }
    val viewOnly = map?.viewOnly ?: true
    val drawn = map != null
    val info = map?.info?.takeIf { frame != null }
    val group = Modifier.semantics {
        isTraversalGroup = true
        traversalIndex = WINDOWS_TRAVERSAL_INDEX
    }
    if (corner != null) {
        val mapRect = corner.map.from(corner.bounds)
        val fit = remember(window.w, window.h, placed, mapRect) { MapFit(window.w, window.h, placed.wholeIn(PxRect(0f, 0f, mapRect.w, mapRect.h))) }
        // Drawn at the window's pixels and scaled up to the screen's, the map would be blurred, and laid out in them its
        // rooms would be tiny ones magnified, not drawn as the app's own map draws them
        LaunchedEffect(fit.renderScale, density.density) { EngineClient.front.windows.setMapScale(fit.renderScale, density.density) }
        val infoHeight = with(density) { (corner.map.h * INFO_FRACTION).toDp() }
        CornerMap(
            corner,
            density,
            modifier.then(group),
            map = { placedMap -> MapView(MapperWindow, frame, fit, viewOnly, drawn, placedMap.background(CONSOLE_BACKGROUND), panel = true) },
            panel = { aligned -> MapPanel(MapperWindow, frame, fit, viewOnly, drawn, info, infoHeight, aligned) },
        )
    } else {
        val rect = placed.rect
        val height = with(density) { rect.h.toDp() }
        // The buttons zoom about the frame's middle, whichever box it is fitted to
        val whole = remember(window.w, window.h, placed) { MapFit(window.w, window.h, placed.wholeIn(PxRect(0f, 0f, rect.w, rect.h))) }
        Column(Modifier.placeAt(rect, density).then(modifier).background(CONSOLE_BACKGROUND).then(group)) {
            MapPanel(MapperWindow, frame, whole, viewOnly, drawn, info, height * INFO_FRACTION, Modifier.fillMaxWidth().heightIn(max = height * PANEL_FRACTION).readAsOne(1f))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val box = PxRect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                val fit = remember(window.w, window.h, placed, box) { MapFit(window.w, window.h, placed.wholeIn(box)) }
                LaunchedEffect(fit.renderScale, density.density) { EngineClient.front.windows.setMapScale(fit.renderScale, density.density) }
                MapView(MapperWindow, frame, fit, viewOnly, drawn, Modifier.fillMaxSize().background(CONSOLE_BACKGROUND).readAsOne(0f), panel = true)
            }
        }
    }
}

/**
 * A map in [corner], its controls shown only on request (docs/divergences.md, "A script's small mapper"): [map], given
 * what places it at the corner's map, and over its top corner on the side it is drawn against a small button that shows
 * [panel], given what aligns it where the corner puts it, and hides it again. Back, or a touch anywhere else in the game
 * area, hides it too; that touch goes no further, as one outside a menu does. TalkBack reads the map, the button, then
 * the panel, so the map's own actions need no panel; [modifier] goes on what holds the three.
 */
@Composable
internal fun CornerMap(corner: MapCorner, density: Density, modifier: Modifier, map: @Composable (Modifier) -> Unit, panel: @Composable (Modifier) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = open) { open = false }
    val bounds = corner.bounds
    val mapRect = corner.map.from(bounds)
    Box(Modifier.fillMaxSize()) {
        if (open) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        open = false
                    }
                },
            )
        }
        // No touch handler here, so that touches between the map and the panel, and on the room the panel leaves empty,
        // are touches elsewhere
        Box(Modifier.placeAt(bounds, density).then(modifier)) {
            map(Modifier.placeAt(mapRect, density).readAsOne(0f))
            Box(Modifier.placeAt(mapRect, density)) {
                MapControlsButton(open, { open = !open }, Modifier.align(if (corner.right) Alignment.TopEnd else Alignment.TopStart).readAt(1f))
            }
            if (open) {
                Box(Modifier.placeAt(corner.panel.from(bounds), density)) {
                    panel(Modifier.align(corner.panelAlignment).readAsOne(2f))
                }
            }
        }
    }
}

// The small button over a map in a corner that shows its controls while not [open], and hides them while they are
@Composable
private fun MapControlsButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(if (open) R.string.map_controls_hide else R.string.map_controls_show)
    Box(
        modifier.size(CONTROLS_BUTTON_TOUCH).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(CONTROLS_BUTTON_SIZE).background(MaterialTheme.colorScheme.surface.copy(alpha = CONTROLS_ALPHA), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(if (open) R.drawable.ic_close else R.drawable.ic_tune), null, Modifier.size(CONTROLS_ICON_SIZE), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// This rect in the pixels of a node at [origin]'s top left
internal fun PxRect.from(origin: PxRect) = PxRect(x - origin.x, y - origin.y, w, h)

// Where a map panel goes in the panel's rect: against the map and the side of the area it is drawn on
internal val MapCorner.panelAlignment: Alignment get() = when {
    below && right -> Alignment.TopEnd
    below -> Alignment.TopStart
    right -> Alignment.BottomEnd
    else -> Alignment.BottomStart
}

// Read as one, at [index] among what is read with it. By where things are drawn, Compose would read one that a panel
// has scrolled out of its sight first, as it takes that one's bounds to be the window's top left corner
internal fun Modifier.readAsOne(index: Float) = semantics {
    isTraversalGroup = true
    traversalIndex = index
}

// Read at [index] among what is read with it, wherever it is drawn: see readAsOne
internal fun Modifier.readAt(index: Float) = semantics { traversalIndex = index }

/**
 * A map the engine draws, the mapper window's or the app's: [frame] drawn where [fit] puts it, scaled alike both ways,
 * and moved at once by what the player's touches moved it by until the engine draws that ([MapMover]). A tap selects
 * the room nearest it and shows it ([RoomCard]), a double tap walks there, a drag pans the map and glides on when
 * flung, a pinch zooms about the fingers and a double tap that drags zooms about it, each sent to [source]. At its top
 * the frame's map info, [info] ([MapInfoBox]); at its bottom why the engine refused a request, the area and level shown
 * ([MapPlaceBar]) beside a button that centres it on the player while it does not follow them, and the room picked.
 * With [panel], the info, the bar and the button are left to a [MapPanel] beside it.
 * TalkBack reads it as the map, viewed or edited and whether it follows the player, whose actions select or walk to the
 * room in its middle, zoom, move it, show the level above or below and centre it on the player.
 */
@Composable
internal fun MapView(
    source: MapSource,
    frame: ImageBitmap?,
    fit: MapFit,
    viewOnly: Boolean,
    drawn: Boolean,
    modifier: Modifier = Modifier,
    info: MapInfo? = null,
    panel: Boolean = false,
) {
    val touchable = frame != null && EngineClient.enginePid > 0
    val currentFit by rememberUpdatedState(fit.takeIf { touchable })
    val editing by rememberUpdatedState(touchable && !viewOnly)
    var roomDrag by remember { mutableStateOf<RoomDrag?>(null) }
    val mover = source.mover
    val view = source.movableView(frame)
    val movable = view != null
    val followShown = movable && !mover.following
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Offset>()

    val description = stringResource(if (frame != null) R.string.map_description else R.string.map_not_drawn)
    val mode = stringResource(if (viewOnly) R.string.map_viewing else R.string.map_editing)
    val state = if (mover.following) mode else stringResource(R.string.map_not_following, mode)
    val selectLabel = stringResource(R.string.map_select_middle)
    val reach = (with(LocalDensity.current) { TAP_REACH.toPx() } / fit.scale.coerceAtLeast(MIN_FIT_SCALE)).toDouble()
    val side = minOf(fit.frameW, fit.frameH) * PAN_FRACTION
    val pans = listOf(
        R.string.map_pan_up to Offset(0f, 1f),
        R.string.map_pan_down to Offset(0f, -1f),
        R.string.map_pan_left to Offset(1f, 0f),
        R.string.map_pan_right to Offset(-1f, 0f),
    ).map { (label, direction) ->
        CustomAccessibilityAction(stringResource(label)) { mover.move(side * direction.x, side * direction.y, 1.0, fit.centre, fit.centre, now = true) }
    }
    val follow = CustomAccessibilityAction(stringResource(R.string.map_follow)) { mover.followPlayer() }
    val above = view?.levelAbove()
    val below = view?.levelBelow()
    val levelUp = above?.let { CustomAccessibilityAction(stringResource(R.string.map_level_up)) { source.rooms.showLevel(it) } }
    val levelDown = below?.let { CustomAccessibilityAction(stringResource(R.string.map_level_down)) { source.rooms.showLevel(it) } }
    val actions = listOf(
        CustomAccessibilityAction(stringResource(R.string.map_walk_to_middle)) { source.touch(MapPointerType.DOUBLE_CLICK, fit, fit.centre, reach) },
        CustomAccessibilityAction(stringResource(R.string.map_zoom_in)) { source.zoom(fit, 1) },
        CustomAccessibilityAction(stringResource(R.string.map_zoom_out)) { source.zoom(fit, -1) },
    ) + (if (movable) pans else emptyList()) + listOfNotNull(levelUp, levelDown, follow.takeIf { followShown })
    val room = source.rooms.room.takeIf { touchable }
    val linking = source.rooms.linking.takeIf { touchable && !viewOnly }
    val ghost = MaterialTheme.colorScheme.primary
    // An exit waiting for its room is the edited map's: a tap on the map viewed only selects a room
    LaunchedEffect(viewOnly) { if (viewOnly) source.rooms.pickExitRoom(null) }

    BoxWithConstraints(modifier) {
        Canvas(
            Modifier.fillMaxSize().clipToBounds().pointerInput(source) { mapGestures(source, haptics, scope, decay, { editing }, { roomDrag = it }) { currentFit } }.clearAndSetSemantics {
                contentDescription = description
                stateDescription = state
                if (touchable) {
                    onClick(selectLabel) { source.touch(MapPointerType.PRESS, fit, fit.centre, reach) }
                    customActions = actions
                }
            },
        ) {
            if (frame != null) drawFrame(frame, fit, shift = mover.shift)
            roomDrag?.let { drawRoomGhost(it, fit, mover, ghost) }
        }
        if (frame != null && info != null && !panel) MapInfoBox(info, Modifier.align(Alignment.TopStart).heightIn(max = maxHeight * INFO_FRACTION))
        // Under one another, so none takes another's touches: in a short map the bar waits for the room it needs
        val placeBar = view?.takeIf { maxHeight >= PLACE_BAR_LEAST_HEIGHT && !panel }
        val followHere = followShown && !panel
        val roomCardHeight = maxHeight * ROOM_CARD_FRACTION
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            source.refusal?.let { MapRefusal(it, source::dismissRefusal) }
            linking?.let { ExitLinkBanner(it, { source.rooms.pickExitRoom(null) }) }
            Box(Modifier.fillMaxWidth()) {
                placeBar?.let { MapPlaceBar(it, source.rooms, above, below, Modifier.align(Alignment.BottomStart).padding(end = if (followHere) FOLLOW_SPACE else 0.dp)) }
                if (followHere) {
                    SmallFloatingActionButton(
                        { mover.followPlayer() },
                        Modifier.align(Alignment.BottomEnd).padding(FOLLOW_PADDING).minimumInteractiveComponentSize(),
                    ) {
                        Icon(painterResource(R.drawable.ic_my_location), stringResource(R.string.map_follow))
                    }
                }
            }
            room?.let { RoomCard(it, source.rooms, editing = !viewOnly, Modifier.fillMaxWidth().heightIn(max = roomCardHeight)) }
        }
    }
}

/**
 * What a [MapView] of the same arguments leaves out with `panel`, on a backing of its own beside a map in a corner so
 * that none of it covers the map: its [MapButtons], with the switch to editing it unless not [edit], and, while the map
 * does not follow the player, a button that centres it on them, then [more], read after them; the area and level shown
 * ([MapPlaceBar]); and the frame's map info, [info], at most [infoHeight] high ([MapInfoBox]). It scrolls where it has
 * not the room.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MapPanel(
    source: MapSource,
    frame: ImageBitmap?,
    fit: MapFit,
    viewOnly: Boolean,
    drawn: Boolean,
    info: MapInfo?,
    infoHeight: Dp,
    modifier: Modifier = Modifier,
    edit: Boolean = true,
    more: @Composable () -> Unit = {},
) {
    val view = source.movableView(frame)
    Surface(modifier, shape = MaterialTheme.shapes.small) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(CONTROLS_PADDING)) {
            // The button that centres the map goes under the others in a strip narrower than four buttons
            FlowRow(Modifier.readAsOne(0f)) {
                MapButtons(source, frame, fit, viewOnly, drawn, Modifier.readAsOne(0f), edit)
                if (view != null && !source.mover.following) {
                    IconButton({ source.mover.followPlayer() }, Modifier.readAt(1f)) {
                        Icon(painterResource(R.drawable.ic_my_location), stringResource(R.string.map_follow))
                    }
                }
                Box(Modifier.readAsOne(2f)) { more() }
            }
            view?.let { MapPlaceBar(it, source.rooms, it.levelAbove(), it.levelBelow(), Modifier.readAt(1f)) }
            info?.let { MapInfoBox(it, Modifier.heightIn(max = infoHeight).readAt(2f)) }
        }
    }
}

// The view of the map the player can move, while a frame is drawn and an engine attached to take the moves
private fun MapSource.movableView(frame: ImageBitmap?): MapViewInfo? = mover.view.takeIf { frame != null && EngineClient.enginePid > 0 }

private fun MapViewInfo.levelAbove(): Int? = levels.firstOrNull { it > level }

private fun MapViewInfo.levelBelow(): Int? = levels.lastOrNull { it < level }

/**
 * The area [view] shows, a button that lists the map's areas to show another, and while the area has more than one
 * level, the level shown between buttons to the level [above] and [below] it, under the area where they do not fit
 * beside it; each asked of [rooms].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapPlaceBar(view: MapViewInfo, rooms: MapRooms, above: Int?, below: Int?, modifier: Modifier = Modifier) {
    val attached = EngineClient.enginePid > 0
    var listed by remember { mutableStateOf(false) }
    Surface(
        modifier.semantics { isTraversalGroup = true }.padding(CONTROLS_PADDING),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = CONTROLS_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        FlowRow {
            Box {
                TextButton(
                    {
                        listed = true
                        rooms.askAreas()
                    },
                    Modifier.heightIn(min = 48.dp),
                    enabled = attached,
                ) {
                    // As many lines as it takes: a map panel is as narrow as three buttons, and scrolls
                    Text(stringResource(R.string.map_area, view.areaName), Modifier.weight(1f, fill = false))
                    Icon(painterResource(R.drawable.ic_arrow_drop_down), null)
                }
                DropdownMenu(listed, { listed = false }) {
                    val areas = rooms.areas
                    if (areas == null) {
                        DropdownMenuItem({ Text(stringResource(R.string.map_areas_loading)) }, {}, enabled = false)
                    } else {
                        areas.forEach { area ->
                            DropdownMenuItem(
                                { Text(area.name, fontWeight = if (area.id == view.area) FontWeight.Bold else null) },
                                {
                                    listed = false
                                    rooms.showArea(area.id)
                                },
                            )
                        }
                    }
                }
            }
            if (view.levels.size > 1) {
                Row(Modifier.readAsOne(1f), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ below?.let(rooms::showLevel) }, enabled = attached && below != null) {
                        Icon(painterResource(R.drawable.ic_arrow_downward), stringResource(R.string.map_level_down))
                    }
                    Text(stringResource(R.string.map_level, view.level), Modifier.weight(1f, fill = false).readAt(1f), style = MaterialTheme.typography.labelLarge)
                    IconButton({ above?.let(rooms::showLevel) }, Modifier.readAt(2f), enabled = attached && above != null) {
                        Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.map_level_up))
                    }
                }
            }
        }
    }
}

/**
 * The room [info] the player picked on a map: its name, area and number, how far it is, its exits, each a button that
 * shows the room it leads to, and a button that walks there; read out as it comes. While the map is [editing], what can
 * be done to the room too ([RoomEditActions]). It scrolls where it has not the room.
 */
@Composable
private fun RoomCard(info: RoomInfo, rooms: MapRooms, editing: Boolean, modifier: Modifier = Modifier) {
    val attached = EngineClient.enginePid > 0
    val haptics = LocalHapticFeedback.current
    Surface(modifier.padding(4.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        // Another room is shown from its top, its name and close button, not scrolled as far as the last one was
        key(info.id) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row {
                    Column(Modifier.weight(1f).padding(start = 16.dp, top = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                        Text(
                            info.name.ifEmpty { stringResource(R.string.room_unnamed, info.id) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(stringResource(R.string.room_place, info.area, info.id), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(roomDistance(info.steps), style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(rooms::close) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.room_close))
                    }
                }
                if (info.exits.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        info.exits.forEach { exit ->
                            val way = exitWay(exit.direction)
                            val name = exit.toName.ifEmpty { stringResource(R.string.room_unnamed, exit.to) }
                            AssistChip(
                                { rooms.ask(exit.to) },
                                { Text(stringResource(if (exit.locked) R.string.room_exit_locked else R.string.room_exit, way, name), maxLines = 1) },
                                enabled = attached,
                            )
                        }
                    }
                }
                FilledTonalButton(
                    { if (rooms.walk(info.id)) haptics.performHapticFeedback(HapticFeedbackType.Confirm) },
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 48.dp),
                    enabled = attached && info.steps > 0,
                ) {
                    Icon(painterResource(R.drawable.ic_directions_walk), null)
                    Text(stringResource(R.string.room_walk), Modifier.padding(start = 8.dp))
                }
                if (editing) RoomEditActions(info, rooms, Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

/** How far a room [steps] from the player's is, in words: here, so many steps, or no known way for -1. */
@Composable
internal fun roomDistance(steps: Int): String = when {
    steps == 0 -> stringResource(R.string.room_here)
    steps < 0 -> stringResource(R.string.room_no_way)
    else -> pluralStringResource(R.plurals.room_steps, steps, steps)
}

// An exit's way in words: one of the twelve directions as the engine names them, or a special exit's own command
@Composable
internal fun exitWay(direction: String): String {
    val word = when (direction) {
        "n" -> R.string.exit_north
        "ne" -> R.string.exit_northeast
        "nw" -> R.string.exit_northwest
        "e" -> R.string.exit_east
        "w" -> R.string.exit_west
        "s" -> R.string.exit_south
        "se" -> R.string.exit_southeast
        "sw" -> R.string.exit_southwest
        "up" -> R.string.exit_up
        "down" -> R.string.exit_down
        "in" -> R.string.exit_in
        "out" -> R.string.exit_out
        else -> return direction
    }
    return stringResource(word)
}

/** [frame] where [fit] puts it, moved by [shift], in the canvas's pixels. */
internal fun DrawScope.drawFrame(frame: ImageBitmap, fit: MapFit, alpha: Float = 1f, shift: MapShift = MapShift.NONE) {
    val drawn = fit.drawn
    drawImage(
        frame,
        dstOffset = IntOffset((drawn.x + shift.x * fit.scale).roundToInt(), (drawn.y + shift.y * fit.scale).roundToInt()),
        dstSize = IntSize((drawn.w * shift.scale).roundToInt(), (drawn.h * shift.scale).roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Medium,
    )
}

// Zooms in ([direction] 1) or out (-1) about the map's middle, as the zoom buttons do; with no view to move, as the
// wheel does, which the engine zooms about the same middle
private fun MapSource.zoom(fit: MapFit, direction: Int): Boolean {
    val zoom = if (direction > 0) BUTTON_ZOOM else 1 / BUTTON_ZOOM
    return mover.move(0.0, 0.0, zoom, fit.centre, fit.centre, now = true) || pointer(MapPointerType.WHEEL, fit.centre, direction * BUTTON_NOTCHES * ZoomNotches.WHEEL_NOTCH)
}

// [type] at [at], a point of the map as shown: on the room nearest it within [reach] of the map's pixels, or else on
// the point of the last frame under it, while that is on the frame. A press shows the room it is on, or none; while an
// exit waits for its room ([MapRooms.linking]), a press on a room leads it there instead
private fun MapSource.touch(type: MapPointerType, fit: MapFit, at: MapPoint, reach: Double): Boolean {
    val room = mover.roomAt(at, reach)
    if (type == MapPointerType.PRESS && rooms.linking != null) return room != null && rooms.link(room.id)
    val point = room?.let { MapPoint(it.x, it.y) } ?: mover.shift.invert(at)
    val onFrame = room != null || (point.x >= 0 && point.y >= 0 && point.x < fit.frameW && point.y < fit.frameH)
    val sent = onFrame && pointer(type, point)
    if (type == MapPointerType.PRESS) {
        if (room != null) rooms.ask(room.id) else rooms.close()
    }
    return sent
}

/**
 * The player's touches on the map, as the frame [fit] gives, while it gives one: a tap is a press on the room nearest
 * it ([touch]), a second tap soon after and near it a double click there, with a tick; a drag pans the map ([MapMover])
 * and, let go quickly, glides on in [scope] as [decay] slows it; a pinch pans with the fingers and zooms about their
 * middle, and a double tap whose second touch drags zooms about it, in as it goes down. While the map is [editing], a
 * drag that starts on the room picked moves that room by whole places instead, shown with [onDrag] as it goes. Drags are
 * not sent as Mudlet's mouse drags: `moveMap` moves its map instead.
 */
private suspend fun PointerInputScope.mapGestures(
    source: MapSource,
    haptics: HapticFeedback,
    scope: CoroutineScope,
    decay: DecayAnimationSpec<Offset>,
    editing: () -> Boolean,
    onDrag: (RoomDrag?) -> Unit,
    fit: () -> MapFit?,
) {
    val slop = DOUBLE_TAP_SLOP.toPx()
    val reach = TAP_REACH.toPx()
    val dragZoom = DRAG_ZOOM_DISTANCE.toPx()
    val minFling = MIN_FLING_SPEED.toPx()
    val maxFling = viewConfiguration.maximumFlingVelocity
    var lastTapUp = 0L
    var lastTapAt = Offset.Zero
    var glide: Job? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        glide?.cancel()
        onDrag(null)
        val mover = source.mover
        var drag: RoomDrag? = null
        val secondTap = lastTapUp != 0L && down.uptimeMillis - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis && (down.position - lastTapAt).getDistance() <= slop
        var gesture = Gesture.TAP
        var last = down.position
        var upAt = down.uptimeMillis
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, down.position)
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                val lifted = event.changes.firstOrNull()
                if (lifted != null) {
                    upAt = lifted.uptimeMillis
                    // A finger that stopped before it lifted does not fling
                    if (gesture == Gesture.PAN) velocity.addPosition(lifted.uptimeMillis, lifted.position)
                }
                break
            }
            val current = fit()
            if (pressed.size >= 2) {
                gesture = Gesture.PINCH
                if (drag != null) {
                    drag = null
                    onDrag(null)
                }
                if (current == null) continue
                val centroid = event.calculateCentroid()
                val about = current.toMap(centroid.x, centroid.y) ?: continue
                val pan = event.calculatePan()
                mover.move(pan.x / current.scale.toDouble(), pan.y / current.scale.toDouble(), event.calculateZoom().toDouble(), about, current.centre)
                continue
            }
            val pointer = pressed[0]
            when (gesture) {
                // A finger lifted from a pinch: the one left pans on from where it is
                Gesture.PINCH -> {
                    gesture = Gesture.PAN
                    velocity.resetTracking()
                }

                Gesture.TAP -> if ((pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    drag = current?.takeIf { !secondTap && editing() }?.let { source.roomDragAt(it, down.position, reach) }
                    gesture = when {
                        secondTap -> Gesture.DRAG_ZOOM
                        drag != null -> Gesture.MOVE_ROOM
                        else -> Gesture.PAN
                    }
                    if (drag != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }

                Gesture.MOVE_ROOM -> current?.toMap(pointer.position.x, pointer.position.y)?.let { to ->
                    drag = drag?.copy(to = to)
                    onDrag(drag)
                }

                Gesture.PAN -> if (current != null) {
                    val step = pointer.position - last
                    mover.move(step.x / current.scale.toDouble(), step.y / current.scale.toDouble(), 1.0, current.centre, current.centre)
                }

                Gesture.DRAG_ZOOM -> if (current != null) {
                    current.toMap(down.position.x, down.position.y)?.let { about ->
                        mover.move(0.0, 0.0, exp((pointer.position.y - last.y) / dragZoom).toDouble(), about, current.centre)
                    }
                }
            }
            if (gesture == Gesture.PAN) velocity.addPosition(pointer.uptimeMillis, pointer.position)
            last = pointer.position
        }
        val current = fit()
        drag?.let { moved ->
            onDrag(null)
            lastTapUp = 0L
            mover.roomsMoved(moved.from, moved.to)?.let { (east, north) ->
                source.rooms.edit(moved.room.id, RoomEdit(place = Triple(moved.room.x + east, moved.room.y + north, moved.room.z)))
            }
            return@awaitEachGesture
        }
        if (gesture != Gesture.TAP) {
            lastTapUp = 0L
            mover.flush()
            val fling = velocity.calculateVelocity(Velocity(maxFling, maxFling))
            if (gesture == Gesture.PAN && current != null && hypot(fling.x, fling.y) >= minFling) {
                glide = scope.launch { mover.glide(Offset(fling.x, fling.y), decay, current) }
            }
            return@awaitEachGesture
        }
        val at = current?.toMap(down.position.x, down.position.y)
        if (current == null || at == null) {
            lastTapUp = 0L
            return@awaitEachGesture
        }
        val within = (reach / current.scale).toDouble()
        if (secondTap) {
            if (source.touch(MapPointerType.DOUBLE_CLICK, current, at, within)) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            lastTapUp = 0L
        } else {
            source.touch(MapPointerType.PRESS, current, at, within)
            lastTapUp = upAt
            lastTapAt = down.position
        }
    }
}

// The room picked, where a drag that went down at [at], in [fit]'s pixels, is on it within [reach] of them
private fun MapSource.roomDragAt(fit: MapFit, at: Offset, reach: Float): RoomDrag? {
    val room = rooms.room ?: return null
    val point = fit.toMap(at.x, at.y) ?: return null
    val place = mover.roomAt(point, (reach / fit.scale).toDouble())?.takeIf { it.id == room.id } ?: return null
    return RoomDrag(room, place, point, point)
}

// Where [drag] would put its room: the room's cell, outlined in [color], moved by the whole places it was dragged
private fun DrawScope.drawRoomGhost(drag: RoomDrag, fit: MapFit, mover: MapMover, color: Color) {
    val view = mover.view ?: return
    val cell = view.roomSize * mover.shift.scale
    val (east, north) = mover.roomsMoved(drag.from, drag.to) ?: (0 to 0)
    val shown = mover.shift.apply(MapPoint(drag.place.x, drag.place.y))
    val side = (cell * fit.scale).toFloat()
    val x = fit.drawn.x + ((shown.x + east * cell) * fit.scale).toFloat()
    // The frame's y goes down, the map's north up
    val y = fit.drawn.y + ((shown.y - north * cell) * fit.scale).toFloat()
    drawRect(color, Offset(x - side / 2, y - side / 2), Size(side, side), style = Stroke(GHOST_STROKE.toPx()))
}

// The map flung at [velocity], in [fit]'s pixels a second, gliding on as [decay] slows it, until it stops or is cancelled
private suspend fun MapMover.glide(velocity: Offset, decay: DecayAnimationSpec<Offset>, fit: MapFit) {
    var glided = Offset.Zero
    try {
        AnimationState(Offset.VectorConverter, Offset.Zero, velocity).animateDecay(decay) {
            val step = value - glided
            glided = value
            move(step.x / fit.scale.toDouble(), step.y / fit.scale.toDouble(), 1.0, fit.centre, fit.centre)
        }
    } finally {
        flush()
    }
}

/**
 * The buttons of the map [MapView] draws of the same arguments: zoom, about its middle, which works while [frame] is
 * drawn, and with [edit] the switch between viewing ([viewOnly]) and editing it, which works once the engine has drawn
 * the map ([drawn]).
 */
@Composable
internal fun MapButtons(source: MapSource, frame: ImageBitmap?, fit: MapFit, viewOnly: Boolean, drawn: Boolean, modifier: Modifier = Modifier, edit: Boolean = true) {
    val attached = EngineClient.enginePid > 0
    val zoomEnabled = frame != null && attached
    Row(modifier) {
        IconButton({ source.zoom(fit, -1) }, enabled = zoomEnabled) {
            Icon(painterResource(R.drawable.ic_zoom_out), stringResource(R.string.map_zoom_out))
        }
        IconButton({ source.zoom(fit, 1) }, enabled = zoomEnabled) {
            Icon(painterResource(R.drawable.ic_zoom_in), stringResource(R.string.map_zoom_in))
        }
        // On while the map is edited; the engine's next map says whether it now is
        if (edit) {
            IconToggleButton(checked = !viewOnly, onCheckedChange = { source.setViewOnly(!it) }, enabled = drawn && attached) {
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
