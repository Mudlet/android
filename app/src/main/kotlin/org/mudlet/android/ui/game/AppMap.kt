package org.mudlet.android.ui.game

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.MapBehindText
import org.mudlet.android.protocol.MapDownloadState
import org.mudlet.android.protocol.MapFit
import org.mudlet.android.protocol.PxRect
import org.mudlet.android.protocol.RoomMatch
import org.mudlet.android.protocol.TextBehindMap
import org.mudlet.android.protocol.matchRange
import org.mudlet.android.protocol.roomAreas
import org.mudlet.android.ui.AppMapClient
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.EngineClient

// How much smaller the map in front is at the end of a back gesture
private const val BACK_SHRINK = 0.1f

// A result's row, room for its two lines, and the results' head, its close button's; they take at most this much of
// the room under the search, leaving some of the map to see where they are over
private val RESULT_ROW = 56.dp
private val RESULTS_HEAD = 48.dp
private const val RESULTS_FRACTION = 0.75f

// The results are filtered by area once they are in at least this many
private const val FILTER_LEAST_AREAS = 3

// The keyboard and the window resize the map in many small steps: only where it comes to rest is drawn anew
private const val MAP_SIZE_SETTLE_MS = 200L

// How long the full-screen map says the map loaded, once it is shown
private const val LOADED_SHOWN_MS = 5_000L

/**
 * The app's map behind the main console's text (docs/architecture.md, "The map behind the text"), filling the console
 * and following the player: as strong as keeps text in [colors] readable over the colours of its frame ([MapBehindText]),
 * easing up to a stronger frame's, and none where even the console's own background leaves the text too faint. It
 * takes no touches, which go to the console's text, and TalkBack does not read it, as the full-screen map is there to
 * be read.
 */
@Composable
internal fun BoxScope.BackgroundMap(colors: ConsoleColors) {
    val foreground = colors.foreground.toArgb() and 0xFFFFFF
    val background = colors.background.toArgb() and 0xFFFFFF
    // Over a map of any colour: before a frame has come, and so that colours which leave the map no room ask for none
    val anyMap = remember(colors) { MapBehindText.alpha(foreground, background) }
    if (anyMap <= 0f) return
    val appMap = EngineClient.front.appMap
    val tones = appMap.tones
    val target = remember(colors, tones) { if (tones == null) anyMap else MapBehindText.alpha(foreground, background, tones) }
    val alpha = remember { Animatable(target) }
    // Fainter at once, as easing down would leave the text too faint over the new frame for a moment; the draw takes the
    // lower too, as the effect snaps only after the new frame's first draw
    LaunchedEffect(target) { if (target < alpha.value) alpha.snapTo(target) else alpha.animateTo(target) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    AskForMap(AppMapClient.Owner.BACKGROUND, size)
    val map = appMap.map
    Canvas(Modifier.matchParentSize().onSizeChanged { size = it }.clearAndSetSemantics {}) {
        val drawn = map ?: return@Canvas
        val frame = drawn.bitmap ?: return@Canvas
        drawFrame(frame, MapFit(drawn.w, drawn.h, PxRect(0f, 0f, this.size.width, this.size.height)), minOf(alpha.value, target))
    }
}

/** How the console shows behind the map in front: drawn at [alpha] over [ground]. */
internal class TextBehind(val ground: Color, val alpha: () -> Float)

/**
 * How the main console shows behind the app's map in front (docs/architecture.md, "The map behind the text"): as
 * strongly as keeps the map's rooms and exits at 3:1 against text in [colors] ([TextBehindMap]), over the map's own
 * background colour, which [FullScreenMap] cuts out of its frame. Easing up to a frame that allows more, at once down to
 * one that allows less, and none before a cut-out frame has come.
 */
@Composable
internal fun textBehindMap(appMap: AppMapClient, colors: ConsoleColors): TextBehind {
    val foreground = colors.foreground.toArgb() and 0xFFFFFF
    val background = colors.background.toArgb() and 0xFFFFFF
    val ground = appMap.overText?.ground
    val target = remember(colors, ground) { TextBehindMap.alpha(ground, foreground, background, CONSOLE_BACKGROUND.toArgb() and 0xFFFFFF) }
    val alpha = remember { Animatable(target) }
    // As BackgroundMap's: fainter at once, and the draw takes the lower until the effect has snapped
    LaunchedEffect(target) { if (target < alpha.value) alpha.snapTo(target) else alpha.animateTo(target) }
    val under = ground?.background?.let { Color(0xFF shl 24 or it) } ?: colors.background
    return TextBehind(under) { minOf(alpha.value, target) }
}

/** The title bar's button that brings the map to the front, [open], and sends it back. */
@Composable
internal fun MapButton(open: Boolean, onOpen: (Boolean) -> Unit) {
    IconToggleButton(open, onOpen) {
        Icon(painterResource(R.drawable.ic_map), stringResource(R.string.app_map_show))
    }
}

/**
 * The app's map in front, where [modifier] puts it: a search for rooms by name or number beside the map's buttons, its
 * results right under it ([RoomResults]), and the download of the map the game offers ([MapDownloadBar]), over the map
 * as [MapView] draws it, which a tap selects in and a double tap walks on; with no map drawn, the offer is in its middle
 * ([MapOffer]), or in the bar while a search's results or a refusal would be where it is. Back closes the results, then
 * the room picked, then calls [onClose], after the map shrinks with a predictive back gesture, whose progress goes to
 * [onBackProgress]. It takes the touches on what is under it. With [textBehind] the map is drawn with its background cut
 * out ([AppMapClient.overText]), for the console's text to show through faintly ([textBehindMap]); otherwise it covers
 * what is under it.
 */
@Composable
internal fun FullScreenMap(onClose: () -> Unit, textBehind: Boolean, onBackProgress: (Float) -> Unit, modifier: Modifier = Modifier) {
    // How far a predictive back gesture has gone: the map shrinks with it, and closes when it is let go
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { gesture ->
        try {
            gesture.collect {
                backProgress = it.progress
                onBackProgress(it.progress)
            }
            onClose()
        } finally {
            backProgress = 0f
            onBackProgress(0f)
        }
    }
    // Its results are of this time the map was in front
    val appMap = EngineClient.front.appMap
    DisposableEffect(appMap) { onDispose { appMap.closeSearch() } }
    // Before AskForMap below asks for the frame, which is cut out as it is decoded
    DisposableEffect(textBehind) {
        AppMapClient.textBehind = textBehind
        onDispose { AppMapClient.textBehind = false }
    }
    val shrunk = Modifier.graphicsLayer {
        val scale = 1f - backProgress * BACK_SHRINK
        scaleX = scale
        scaleY = scale
    }
    Box(modifier.then(shrunk).then(if (textBehind) Modifier else Modifier.background(CONSOLE_BACKGROUND))) {
        // Touches between its controls stay off what it covers; under them, so they still take theirs
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
        // While the search is edited its last results are not shown: they are of the text before, and they would sit
        // where the keyboard leaves the map no room
        var editing by remember { mutableStateOf(false) }
        val search = appMap.search
        val resultsShown = search != null && !editing
        // Registered after the map's own, so each takes Back first while it is shown
        BackHandler(appMap.rooms.room != null) { appMap.rooms.close() }
        BackHandler(resultsShown) { appMap.closeSearch() }
        Column(Modifier.matchParentSize()) {
            val map = appMap.map
            val drawn = map != null
            val viewOnly = map?.viewOnly ?: true
            var size by remember { mutableStateOf(IntSize.Zero) }
            // The last frame drawn, whatever size it was asked at, as the engine takes touches at its points
            val fit = remember(map?.w, map?.h, size) { MapFit(map?.w ?: 0, map?.h ?: 0, PxRect(0f, 0f, size.width.toFloat(), size.height.toFloat())) }
            // The buttons are beside the search, not over the map, where they would hide the room's name the engine
            // draws along its top
            Row(Modifier.fillMaxWidth().background(CONSOLE_BACKGROUND).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RoomSearchField(Modifier.weight(1f).onFocusChanged { editing = it.hasFocus })
                MapButtons(appMap, map?.bitmap, fit, viewOnly, drawn)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.matchParentSize()) {
                    // In the map's middle with no map drawn, but not where it would share the map with the search's
                    // results or a refusal at its bottom: whichever is drawn over the other would take its touches and
                    // TalkBack. With a map drawn, none: the map is there
                    val offerInMap = !drawn && !resultsShown && appMap.refusal == null
                    MapDownloadBar(offerHere = !drawn && !offerInMap, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AskForMap(AppMapClient.Owner.FULL_SCREEN, size)
                        MapView(
                            appMap,
                            (if (textBehind) appMap.overText?.frame else null) ?: map?.bitmap,
                            fit,
                            viewOnly,
                            drawn,
                            modifier = Modifier.fillMaxSize().onSizeChanged { size = it },
                            controls = false,
                        )
                        if (offerInMap) {
                            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                                MapOffer(Modifier.padding(16.dp))
                            }
                        }
                    }
                }
                // Right under the search, over the download bar and the map: at most most of the room under it, but at
                // least their head and a whole result. Where there is not that room, as in a short window, they wait
                // for it: squeezed, their close button and rows would be cut off
                if (search != null && resultsShown && maxHeight >= RESULTS_HEAD + RESULT_ROW) {
                    val results = maxOf(maxHeight * RESULTS_FRACTION, RESULTS_HEAD + RESULT_ROW)
                    RoomResults(search, Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 8.dp).heightIn(max = results))
                }
            }
        }
    }
}

// [owner] asks for the map at [size] of the screen, once it has held for a moment, until it leaves the screen
@Composable
private fun AskForMap(owner: AppMapClient.Owner, size: IntSize) {
    val density = LocalDensity.current.density
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(owner, size, density) {
        if (size == IntSize.Zero) return@LaunchedEffect
        // The first at once, so the map comes as soon as it can
        if (asked) delay(MAP_SIZE_SETTLE_MS)
        AppMapClient.show(owner, AppMapClient.Size.of(size.width, size.height, density))
        asked = true
    }
    DisposableEffect(owner) { onDispose { AppMapClient.hide(owner) } }
}

/** The room search's field: Enter, or its button, asks the engine for the rooms whose name holds the text, or whose number it is. */
@Composable
private fun RoomSearchField(modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val attached = EngineClient.enginePid > 0
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    // The keyboard goes, so that the results have the room it took: on a small screen it leaves none. The field lets go
    // of the focus too, or the keyboard would come back for it when its input starts after this, as it can when the
    // search follows the first letters at once
    fun find() {
        if (text.isBlank()) return
        keyboard?.hide()
        focus.clearFocus()
        EngineClient.front.appMap.findRooms(text.trim())
    }

    OutlinedTextField(
        text,
        { text = it },
        modifier,
        enabled = attached,
        singleLine = true,
        // One line each, or the field beside the map's buttons grows a line taller while it is empty, and the map shrinks
        label = { Text(stringResource(R.string.app_map_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = { Text(stringResource(R.string.app_map_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            IconButton(::find, enabled = attached && text.isNotBlank()) {
                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.app_map_search_go))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { find() }),
    )
}

/**
 * The search's results: how many were found, read out as they come, and a filter by area where they are in many; each
 * a room's name, with what was searched for marked, over its area, number and how far it is, which a tap centres the
 * map on and shows ([AppMapClient.showRoom]), keeping the search's text, beside a button that walks there.
 */
@Composable
private fun RoomResults(search: AppMapClient.RoomSearch, modifier: Modifier = Modifier) {
    val appMap = EngineClient.front.appMap
    val rooms = search.rooms
    val status = when {
        rooms == null -> stringResource(R.string.app_map_searching)
        rooms.isEmpty() -> stringResource(R.string.app_map_no_rooms, search.text)
        search.more -> stringResource(R.string.app_map_results_many, rooms.size)
        else -> pluralStringResource(R.plurals.app_map_results_count, rooms.size, rooms.size)
    }
    val areas = remember(rooms) { roomAreas(rooms.orEmpty(), FILTER_LEAST_AREAS) }
    var area by remember(search) { mutableStateOf<Int?>(null) }
    val shown = rooms.orEmpty().filter { room -> area.let { it == null || it == room.areaId } }
    Surface(modifier, shape = MaterialTheme.shapes.small, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status,
                    Modifier.weight(1f).padding(start = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(appMap::closeSearch) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.app_map_results_close))
                }
            }
            if (search.more) {
                Text(
                    stringResource(R.string.app_map_more_rooms),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (areas.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(area == null, { area = null }, { Text(stringResource(R.string.app_map_all_areas)) })
                    areas.forEach { each ->
                        FilterChip(area == each.id, { area = if (area == each.id) null else each.id }, { Text(each.name, maxLines = 1) })
                    }
                }
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(shown, key = { it.id }) { room -> RoomResult(room, search.text) }
            }
        }
    }
}

// One of the search's results: [room], with [text], what was searched for, marked in its name
@Composable
private fun RoomResult(room: RoomMatch, text: String) {
    val appMap = EngineClient.front.appMap
    val mark = SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, color = MaterialTheme.colorScheme.onTertiaryContainer)
    val name = remember(room.name, text, mark) {
        buildAnnotatedString {
            append(room.name)
            matchRange(room.name, text)?.let { addStyle(mark, it.first, it.last + 1) }
        }
    }
    val place = if (room.steps < 0) {
        stringResource(R.string.app_map_result_place, room.area, room.id)
    } else {
        stringResource(R.string.app_map_result_place_steps, room.area, room.id, roomDistance(room.steps))
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = RESULT_ROW).clickable(role = Role.Button) {
            appMap.showRoom(room.id)
            appMap.rooms.ask(room.id)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(place, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(
            {
                if (appMap.rooms.walk(room.id)) appMap.closeSearch()
            },
            enabled = EngineClient.enginePid > 0 && room.steps > 0,
        ) {
            Icon(painterResource(R.drawable.ic_directions_walk), stringResource(R.string.app_map_walk_to, room.name))
        }
    }
}

/**
 * The download of the map the game offers (docs/architecture.md, "Downloading the map"): while it runs, Mudlet's
 * progress and a Cancel button while it can be stopped; once it ended, how ([MapDownloadEnd]); and, where [offerHere],
 * the button that downloads the map, while the game offers one. Nothing while there is none of these.
 */
@Composable
private fun MapDownloadBar(offerHere: Boolean, modifier: Modifier = Modifier) {
    val download = EngineClient.front.appMap.download
    val end = EngineClient.front.appMap.downloadEnd
    val downloading = download.state == MapDownloadState.DOWNLOADING
    val offer = offerHere && download.offered && !downloading
    if (!downloading && end == null && !offer) return
    Surface(modifier, shape = MaterialTheme.shapes.small, tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (downloading) {
                MapDownloadProgress(download)
            } else if (end != null) {
                MapDownloadEnd(end)
            }
            if (offer) {
                DownloadMapButton(download.host, Modifier.align(Alignment.End))
            }
        }
    }
}

// How far the download has got, in Mudlet's words, with Cancel while it can be stopped
@Composable
private fun MapDownloadProgress(download: EngineMessage.MapDownload) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            download.label.ifEmpty { stringResource(R.string.map_download_running, download.host) },
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(EngineClient.front.appMap::cancelMapDownload, Modifier.heightIn(min = 48.dp), enabled = download.cancelable && EngineClient.enginePid > 0) {
            Text(stringResource(R.string.map_download_cancel))
        }
    }
    val description = stringResource(R.string.map_download_progress)
    // Mudlet guesses the size until the server says it, which the download can pass: then how far is not known
    val fraction = if (download.maximum > 0 && download.value <= download.maximum) download.value.toFloat() / download.maximum else null
    // Clear of Cancel above it by the 10dp Material's indicator reaches out above and below itself for TalkBack, which
    // would otherwise cover Cancel's touch target
    val bar = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp).semantics { contentDescription = description }
    if (fraction != null) LinearProgressIndicator(progress = { fraction }, bar) else LinearProgressIndicator(bar)
}

// How the last download ended, until dismissed, or a load for a moment once shown, as the map shows it too;
// AppMapClient tells TalkBack as it ends
@Composable
private fun MapDownloadEnd(end: EngineMessage.MapDownload) {
    val text = AppMapClient.endText(end, LocalResources.current) ?: return
    val appMap = EngineClient.front.appMap
    if (end.state == MapDownloadState.LOADED) {
        LaunchedEffect(end) {
            delay(LOADED_SHOWN_MS)
            appMap.dismissDownloadEnd(end)
        }
    }
    val failed = end.state == MapDownloadState.FAILED
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            Modifier.weight(1f),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton({ appMap.dismissDownloadEnd(end) }) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.map_download_dismiss))
        }
    }
}

// The button that downloads the map from [host], which the game offers
@Composable
private fun DownloadMapButton(host: String, modifier: Modifier = Modifier) {
    Button(EngineClient.front.appMap::downloadMap, modifier.heightIn(min = 48.dp), enabled = EngineClient.enginePid > 0 && !EngineClient.front.appMap.downloadAsked) {
        Text(stringResource(R.string.map_download, host))
    }
}

/**
 * With no map drawn: that there is none, and the map the game offers, if it offers one and none is downloading. It
 * scrolls where the map has not the room for it, as with a keyboard up, rather than cut its button off.
 */
@Composable
private fun MapOffer(modifier: Modifier = Modifier) {
    val download = EngineClient.front.appMap.download
    if (!download.offered || download.state == MapDownloadState.DOWNLOADING) return
    Surface(modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.map_download_offered, download.host),
                Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            DownloadMapButton(download.host)
        }
    }
}
