package org.mudlet.android.ui.game

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.MapBehindText
import org.mudlet.android.protocol.MapDownloadState
import org.mudlet.android.protocol.MapFit
import org.mudlet.android.protocol.PxRect
import org.mudlet.android.protocol.WindowKind
import org.mudlet.android.ui.AppMapClient
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ScriptWindowsClient

// How much smaller the map in front is at the end of a back gesture
private const val BACK_SHRINK = 0.1f

// A result's row, a touch target's least, and the results' head, its close button's
private val RESULT_ROW = 48.dp
private val RESULTS_HEAD = 48.dp

// The keyboard and the window resize the map in many small steps: only where it comes to rest is drawn anew
private const val MAP_SIZE_SETTLE_MS = 200L

/**
 * The app's map behind the main console's text (docs/architecture.md, "The map behind the text"), filling the console
 * and following the player: as faint as keeps text in [colors] readable over it ([MapBehindText]), and none where even
 * the console's own background leaves the text too faint. It takes no touches, which go to the console's text, and
 * TalkBack does not read it, as the full-screen map is there to be read.
 */
@Composable
internal fun BoxScope.BackgroundMap(colors: ConsoleColors) {
    val alpha = remember(colors) { MapBehindText.alpha(colors.foreground.toArgb() and 0xFFFFFF, colors.background.toArgb() and 0xFFFFFF) }
    if (alpha <= 0f) return
    var size by remember { mutableStateOf(IntSize.Zero) }
    AskForMap(AppMapClient.Owner.BACKGROUND, size)
    val map = AppMapClient.map
    Canvas(Modifier.matchParentSize().onSizeChanged { size = it }.clearAndSetSemantics {}) {
        val drawn = map ?: return@Canvas
        val frame = drawn.bitmap ?: return@Canvas
        drawFrame(frame, MapFit(drawn.w, drawn.h, PxRect(0f, 0f, this.size.width, this.size.height)), alpha)
    }
}

/** The title bar's button that brings the map to the front, [open], and sends it back. */
@Composable
internal fun MapButton(open: Boolean, onOpen: (Boolean) -> Unit) {
    IconToggleButton(open, onOpen) {
        Icon(painterResource(R.drawable.ic_map), stringResource(R.string.app_map_show))
    }
}

/**
 * The app's map in front, where [modifier] puts it: a search for rooms by name or number, whose results centre the map
 * on a room, and the download of the map the game offers ([MapDownloadBar]), over the map as [MapView] draws it, which a
 * tap selects in and a double tap walks on; with no map drawn, the offer is in its middle ([MapOffer]), under the map's
 * buttons, or in the bar while a search's results would be where it is. Back calls
 * [onClose], after the map shrinks with a predictive back gesture. It covers what is under it, touches and all.
 */
@Composable
internal fun FullScreenMap(onClose: () -> Unit, modifier: Modifier = Modifier) {
    // How far a predictive back gesture has gone: the map shrinks with it, and closes when it is let go
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { gesture ->
        try {
            gesture.collect { backProgress = it.progress }
            onClose()
        } finally {
            backProgress = 0f
        }
    }
    // Its results are of this time the map was in front
    DisposableEffect(Unit) { onDispose { AppMapClient.closeSearch() } }
    val shrunk = Modifier.graphicsLayer {
        val scale = 1f - backProgress * BACK_SHRINK
        scaleX = scale
        scaleY = scale
    }
    Box(modifier.then(shrunk).background(CONSOLE_BACKGROUND)) {
        // Touches between its controls stay off what it covers; under them, so they still take theirs
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
        // While the search is edited its last results are not shown: they are of the text before, and they would sit
        // where the keyboard leaves the map no room
        var editing by remember { mutableStateOf(false) }
        Column(Modifier.matchParentSize()) {
            RoomSearchField(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).onFocusChanged { editing = it.hasFocus })
            val map = AppMapClient.map
            // Results would sit over the offer in the map's middle, so it waits in the bar while there are any
            val searched = AppMapClient.search != null && !editing
            MapDownloadBar(offerHere = map != null || searched, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                var size by remember { mutableStateOf(IntSize.Zero) }
                AskForMap(AppMapClient.Owner.FULL_SCREEN, size)
                // The last frame drawn, whatever size it was asked at, as the engine takes touches at its points
                val fit = remember(map?.w, map?.h, size) { MapFit(map?.w ?: 0, map?.h ?: 0, PxRect(0f, 0f, size.width.toFloat(), size.height.toFloat())) }
                MapView(
                    AppMapClient,
                    map?.bitmap,
                    fit,
                    viewOnly = map?.viewOnly ?: true,
                    drawn = map != null,
                    modifier = Modifier.fillMaxSize().onSizeChanged { size = it },
                )
                // Under the map's buttons, as the results are, whose touches and TalkBack it would take where it covered them
                if (map == null && !searched) {
                    Box(Modifier.matchParentSize().padding(top = MAP_CONTROLS_HEIGHT), contentAlignment = Alignment.Center) {
                        MapOffer(Modifier.padding(16.dp))
                    }
                }
                // Under the map's buttons, whose touches and TalkBack they would take where they covered them, even
                // the strip of a short list that left the buttons' bottom edge out; half the map below them, but at
                // least their head and a whole result. Where the map has not that room, as with a keyboard up for the
                // command line, they wait for it: squeezed, their close button and rows would be cut off
                val below = maxHeight - MAP_CONTROLS_HEIGHT
                val search = AppMapClient.search
                if (search != null && !editing && below >= RESULTS_HEAD + RESULT_ROW) {
                    val results = maxOf(below / 2, RESULTS_HEAD + RESULT_ROW)
                    RoomResults(search, Modifier.align(Alignment.TopStart).padding(top = MAP_CONTROLS_HEIGHT).fillMaxWidth().heightIn(max = results))
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
        AppMapClient.findRooms(text.trim())
    }

    OutlinedTextField(
        text,
        { text = it },
        modifier,
        enabled = attached,
        singleLine = true,
        label = { Text(stringResource(R.string.app_map_search)) },
        placeholder = { Text(stringResource(R.string.app_map_search_hint)) },
        trailingIcon = {
            IconButton(::find, enabled = attached && text.isNotBlank()) {
                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.app_map_search_go))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { find() }),
    )
}

/** The search's results, each a room that a tap centres the map on, under what was found, read out as it comes. */
@Composable
private fun RoomResults(search: AppMapClient.RoomSearch, modifier: Modifier = Modifier) {
    val rooms = search.rooms
    val status = when {
        rooms == null -> stringResource(R.string.app_map_searching)
        rooms.isEmpty() -> stringResource(R.string.app_map_no_rooms, search.text)
        search.more -> stringResource(R.string.app_map_more_rooms)
        else -> null
    }
    Surface(modifier, shape = MaterialTheme.shapes.small, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status != null) {
                    Text(
                        status,
                        Modifier.weight(1f).padding(start = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(AppMapClient::closeSearch) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.app_map_results_close))
                }
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(rooms.orEmpty(), key = { it.id }) { room ->
                    Text(
                        stringResource(R.string.app_map_room, room.name, room.area, room.id),
                        Modifier.fillMaxWidth().heightIn(min = RESULT_ROW).clickable(role = Role.Button) { AppMapClient.showRoom(room.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/**
 * The download of the map the game offers (docs/architecture.md, "Downloading the map"): while it runs, Mudlet's
 * progress and a Cancel button while it can be stopped; once it ended, how, until dismissed; and, where [offerHere],
 * the button that downloads the map, while the game offers one. Nothing while there is none of these.
 */
@Composable
private fun MapDownloadBar(offerHere: Boolean, modifier: Modifier = Modifier) {
    val download = AppMapClient.download
    val end = AppMapClient.downloadEnd
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
        TextButton(AppMapClient::cancelMapDownload, Modifier.heightIn(min = 48.dp), enabled = download.cancelable && EngineClient.enginePid > 0) {
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

// How the last download ended, until dismissed; AppMapClient tells TalkBack as it ends
@Composable
private fun MapDownloadEnd(end: EngineMessage.MapDownload) {
    val text = AppMapClient.endText(end, LocalResources.current) ?: return
    val failed = end.state == MapDownloadState.FAILED
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            Modifier.weight(1f),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(AppMapClient::dismissDownloadEnd) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.map_download_dismiss))
        }
    }
}

// The button that downloads the map from [host], which the game offers
@Composable
private fun DownloadMapButton(host: String, modifier: Modifier = Modifier) {
    Button(AppMapClient::downloadMap, modifier.heightIn(min = 48.dp), enabled = EngineClient.enginePid > 0 && !AppMapClient.downloadAsked) {
        Text(stringResource(R.string.map_download, host))
    }
}

/**
 * With no map drawn: that there is none, and the map the game offers, if it offers one and none is downloading. It
 * scrolls where the map has not the room for it, as with a keyboard up, rather than cut its button off.
 */
@Composable
private fun MapOffer(modifier: Modifier = Modifier) {
    val download = AppMapClient.download
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

/** Whether a window a script opened shows the map already, so it is not drawn behind the text as well. */
internal fun mapperShown(): Boolean = ScriptWindowsClient.state.windows.any { it.kind == WindowKind.MAPPER && it.shown }
