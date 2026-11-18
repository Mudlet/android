package org.mudlet.android.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.KeyBinding
import org.mudlet.android.protocol.MapCorner
import org.mudlet.android.protocol.PxRect
import org.mudlet.android.protocol.RefusedCertificate
import org.mudlet.android.ui.CertificateFacts
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MudletTopBar
import org.mudlet.android.ui.ProfileSession
import org.mudlet.android.ui.ScriptWindowsClient
import kotlin.reflect.KClass

/**
 * The console with the windows the game's scripts draw around it, the input line, and the row below it, top to bottom,
 * under a title bar whose title is the game ([GameTitle]): the front game's name and connection, which opens the game
 * sheet ([GameSheet]) with Reconnect, Disconnect, the open games and "Open another game", which goes back to the profile
 * list ([onBack]), where Connect opens one more beside those open. While the player's own Disconnect, or no connection
 * yet, leaves the game disconnected, the bar's first button connects it again, where the bar has room for it; its menu ([GameMenuButton]) has
 * Reconnect, Disconnect (Stop while connecting), the search of the text, the profile's packages ([onOpenPackages]) and
 * the settings. While a connection the player did not end is being dealt with, a banner under the title bar says so,
 * with what to do about it ([ConnectionBanner]). The row below the input line holds, once the game has played a sound
 * in this session or while anything is muted, desktop Mudlet's Sound button ([SoundButton]), then the quick bar's
 * pinned commands, and takes no room with neither. TalkBack reads the game's title first, then the title bar's
 * buttons, the banner, the console, the input line and that row, then the script windows and their switcher, then the
 * button bars. The keyboard pushes them up, so the console shrinks above it. The input line's suggestions and the
 * quick bar's pinned commands come from the profile the engine runs ([rememberEngineSuggestions]), which learns from
 * what is sent. The input line's History button, or Ctrl+R on a hardware keyboard, opens the command history
 * ([HistorySheet]); while the game hides input there is no history and the row has no pinned commands. Why the engine
 * could not send something shows over the console's foot, where the keyboard does not hide it. The title bar's map
 * button swaps the map and the console ([FullScreenMap]), until Back or the button again, and for the game switched
 * to: with the setting, the map is faint behind the text ([BackgroundMap]) and the text faint behind the map in front
 * ([textBehindMap]); without it there is no map behind the text and the map in front covers the console. The title bar
 * and the banner stay above the map in front, and the input line and the row below it, to type and pick commands in.
 * The title bar's scripts button opens the profile's script library ([onOpenScripts]); a long press on a line of the
 * console offers to make a trigger from it ([onMakeTrigger], with the line's text). The first time it shows, and after
 * Help's "Take a UI tour", a short tour points out the console, the input line, the scripts and the menu with the
 * settings ([GameTourHost]), once no dialog over the screen asks something more urgent ([tourHeld]).
 *
 * All of it is the front profile's ([EngineClient.front]), and what the screen keeps of a profile, such as the typed
 * line and where the console is scrolled to, waits for it while another is in front. When another game comes to the
 * front, TalkBack says its name and state and the focus goes to its input line. A swipe on the title, or Ctrl+Tab and
 * Ctrl+Shift+Tab on a hardware keyboard, brings the next or the previous game, and the console, or the map in front,
 * slides across with it ([GameSwipe]). Each change of the front game's connection is said once, with the game's name
 * first, so Mudlet's retries after a drop are not each read out. Desktop's Alt shortcuts open the screens they open
 * there ([desktopShortcut]), and a hardware key a key binding of the profile takes goes to the engine.
 *
 * Below the row is the movement pad ([MovementPad]), unless the player has put it away, a keyboard is in use, the game
 * hides input or the screen is too small ([movementPadShown]). A tap on a key walks that way ([MovementKey.press]).
 */
@Composable
fun GameScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScripts: () -> Unit,
    onOpenPackages: () -> Unit,
    onMakeTrigger: (String) -> Unit,
    tourHeld: Boolean = false,
) {
    val front = EngineClient.front
    val kept = rememberSaveableStateHolder()
    val open = EngineClient.sessions.map { it.name }
    val shown = remember { HashSet<String>() }
    // A profile that closed takes what was kept of it along
    LaunchedEffect(front.name, open) {
        shown.add(front.name)
        val closed = shown.filter { it !in open && it != front.name }
        closed.forEach(kept::removeState)
        shown.removeAll(closed.toSet())
    }
    // Not the profile's: a switch keeps the map in front (docs/multi-profile.md, "Interactions")
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    val before = remember { Last<String>() }
    val switched = remember(front.name) {
        val was = before.value
        before.value = front.name
        was != null && was != front.name && front.name.isNotEmpty()
    }
    // Not the profile's either: a slide carries on across the switch it makes
    val scope = rememberCoroutineScope()
    val swipe = remember { GameSwipe(scope) }
    ScriptWebPageOpener()
    GameTourHost(tourHeld) {
        kept.SaveableStateProvider(front.name) {
            ProfileGameScreen(front, switched, mapOpen, { mapOpen = it }, swipe, onBack, onOpenSettings, onOpenScripts, onOpenPackages, onMakeTrigger)
        }
    }
}

// What a composition last saw, which changing does not compose again
private class Last<T> {
    var value: T? = null
}

@Composable
private fun ProfileGameScreen(
    c: ProfileSession,
    switched: Boolean,
    mapOpen: Boolean,
    onMapOpen: (Boolean) -> Unit,
    swipe: GameSwipe,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScripts: () -> Unit,
    onOpenPackages: () -> Unit,
    onMakeTrigger: (String) -> Unit,
) {
    val suggestions = rememberEngineSuggestions()
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val search = rememberSaveable(saver = ConsoleSearch.Saver) { ConsoleSearch() }
    var gamesOpen by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notSent = stringResource(R.string.input_not_sent)
    val context = LocalContext.current
    val passwords = remember { PasswordStore(context) }
    val resources = LocalResources.current
    val hidden = c.hiddenInput
    val boundKeysDown = remember { HashSet<Int>() }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    var caret by rememberSaveable { mutableStateOf(false) }
    var typedInCaret by remember { mutableStateOf<String?>(null) }

    // Null: the focus went elsewhere, a tapped text box say, which keeps it
    fun leaveCaret(typed: String?) {
        caret = false
        if (typed == null) return
        if (typed.isEmpty()) c.windows.focusInput = true else typedInCaret = typed
    }
    // Hidden input closes the history for good: it does not open again when the password field goes
    LaunchedEffect(hidden) { if (hidden) historyOpen = false }
    // The map in front covers the text, which no keyboard focus then reaches
    LaunchedEffect(mapOpen) { if (mapOpen) caret = false }
    // The map behind the text is not there to be moved or picked in: once the map in front closes, not as a rotation
    // remakes it, it shows no room and follows the player again
    LaunchedEffect(mapOpen) {
        if (!mapOpen) {
            c.appMap.rooms.close()
            if (!c.appMap.mover.following) c.appMap.mover.followPlayer()
        }
    }
    val sendError = c.sendError
    if (sendError != null) {
        val message = stringResource(R.string.send_error, sendError)
        LaunchedEffect(sendError) {
            c.sendErrorShown()
            // The newest reason replaces one still showing; launched apart, as sendErrorShown ends this effect
            snackbars.currentSnackbarData?.dismiss()
            scope.launch { snackbars.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long) }
        }
    }

    val games = EngineClient.sessions
    val state = ConnectionState.of(c)
    val stateText = state.text()
    val name = c.name.ifEmpty { stringResource(R.string.game_none) }
    // What Reconnect opens again while no game is open
    val reconnectName = c.name.ifEmpty { EngineClient.lastProfile.orEmpty() }
    val canReconnect = EngineClient.enginePid > 0 && EngineClient.canReconnect
    val canDisconnect = c.name.isNotEmpty() && state.canDisconnect
    val index = games.indexOf(c)
    val previous = games.getOrNull(index - 1)?.name
    val next = if (index >= 0) games.getOrNull(index + 1)?.name else null
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val switchedText = stringResource(R.string.game_state, name, stateText)
    LaunchedEffect(Unit) { if (switched) Announcer.announce(switchedText, assertive = false) }
    val heard = remember { Last<KClass<out ConnectionState>>() }
    val transitionText = stringResource(R.string.connection_announce, name, stateText)
    LaunchedEffect(state::class) {
        val was = heard.value
        heard.value = state::class
        if (was != null && was != state::class) Announcer.announce(transitionText, assertive = false)
    }

    fun reconnect() = EngineClient.reconnect(passwords::get) { error ->
        scope.launch { snackbars.showSnackbar(resources.getString(R.string.connection_reconnect_refused, error), withDismissAction = true) }
    }

    fun disconnect() {
        if (!c.disconnect()) {
            scope.launch { snackbars.showSnackbar(resources.getString(R.string.connection_disconnect_refused, resources.getString(R.string.profiles_error_not_attached)), withDismissAction = true) }
        }
    }
    val onReconnect = if (canReconnect) ::reconnect else null
    val connectWanted = state == ConnectionState.Disconnected && canReconnect
    val commandsShown = scriptCommandsShown()
    val onDisconnect = if (canDisconnect) ::disconnect else null

    fun close(game: String) = EngineClient.closeProfile(game) { error ->
        if (error != null) {
            scope.launch { snackbars.showSnackbar(resources.getString(R.string.game_close_refused, error), withDismissAction = true) }
        } else if (EngineClient.sessions.isEmpty()) {
            onBack()
        }
    }

    val cornerHidden = stringResource(R.string.app_map_corner_hidden, stringResource(R.string.settings_map_in_corner))
    val cornerUndo = stringResource(R.string.app_map_corner_undo)

    fun hideMapInCorner() {
        Settings.mapInCorner.set(false)
        snackbars.currentSnackbarData?.dismiss()
        scope.launch {
            if (snackbars.showSnackbar(cornerHidden, cornerUndo, withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                Settings.mapInCorner.set(true)
            }
        }
    }

    // A command from the quick bar or the history, sent as the input line sends
    fun sendNow(command: String) {
        if (c.sendCommand(command)) suggestions.sent(command) else scope.launch { snackbars.showSnackbar(notSent) }
    }

    fun walk(key: MovementKey) {
        when (val press = key.press(c.windows.keyBindings)) {
            is MovementPress.Command -> sendNow(press.line)
            is MovementPress.Binding -> if (!c.windows.pressKey(press.key, press.text)) scope.launch { snackbars.showSnackbar(notSent) }
        }
    }

    // Says where the switch is, as the pad is on from the start and the player may never have seen the setting
    fun hideMovementPad() {
        Settings.movementPad.set(false)
        scope.launch {
            val undone = snackbars.showSnackbar(
                resources.getString(R.string.movement_pad_hidden),
                resources.getString(R.string.movement_pad_undo),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (undone == SnackbarResult.ActionPerformed) Settings.movementPad.set(true)
        }
    }

    Scaffold(
        topBar = {
            BoxWithConstraints {
                // Back, the title and each button at 48 dp, and the bar's 4 dp at each end: without that room the title
                // bar squeezes Back and the title under 48 dp, so Connect gives way to the menu's Reconnect
                val buttons = 4 + (if (commandsShown) 1 else 0)
                val connectShown = connectWanted && maxWidth >= 4.dp + 48.dp * (2 + buttons) + 4.dp
                MudletTopBar(name, onBack, titleContent = {
                    GameTitle(
                        name,
                        state,
                        index,
                        games.size,
                        othersUnread = games.sumOf { if (it === c) 0L else it.unread },
                        othersAttention = games.any { it !== c && it.attention },
                        onShowGames = { gamesOpen = true },
                        onReconnect = onReconnect,
                        onDisconnect = onDisconnect,
                        previous = previous,
                        next = next,
                        onSwitch = EngineClient::setFront,
                        swipe = swipe,
                        scriptsStopped = c.scriptsStopped,
                        stateShown = !connectShown,
                    )
                }) {
                    if (connectShown) {
                        FilledTonalIconButton(::reconnect) { Icon(painterResource(R.drawable.ic_link), stringResource(R.string.connection_connect_to, reconnectName)) }
                    }
                    ScriptCommandsButton()
                    IconButton(onOpenScripts, Modifier.tourTarget(TourTarget.SCRIPTS)) {
                        Icon(painterResource(R.drawable.ic_auto_fix_high), stringResource(R.string.scripts_open, c.name))
                    }
                    MapButton(mapOpen, onMapOpen)
                    GameMenuButton(
                        c.name,
                        name,
                        reconnectName,
                        connecting = state is ConnectionState.Connecting,
                        onReconnect = onReconnect,
                        onDisconnect = onDisconnect,
                        onSearch = { searching = true },
                        onOpenPackages = onOpenPackages,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.tourTarget(TourTarget.SETTINGS),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().onSizeChanged { screen = it }.padding(padding).consumeWindowInsets(padding).imePadding().onPreviewKeyEvent { event ->
                // First, as desktop's beats the profile-switching shortcuts and a key binding on the same key
                if (!mapOpen && HardwareKeys.caretKeyMatches(c.setting(Settings.caretKey), qtKey(event.nativeKeyEvent))) {
                    if (event.type == KeyEventType.KeyDown) if (caret) leaveCaret("") else caret = true
                    return@onPreviewKeyEvent true
                }
                desktopShortcut(event)?.let { shortcut ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (shortcut) {
                            DesktopShortcut.SCRIPTS -> onOpenScripts()
                            DesktopShortcut.SETTINGS -> onOpenSettings()
                            DesktopShortcut.MAP -> onMapOpen(!mapOpen)
                            DesktopShortcut.CONNECT -> onBack()
                            DesktopShortcut.DISCONNECT -> if (canDisconnect) disconnect()
                            DesktopShortcut.RECONNECT -> if (canReconnect) reconnect()
                            DesktopShortcut.CLOSE -> if (c.name.isNotEmpty()) close(c.name)
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                // A binding comes next, as desktop's command line claims a bound profile-switching shortcut
                // The caret's keys are its own; one that types goes to the input line on the desktop, whose bindings run
                val typesFromCaret = caret && HardwareKeys.typesFromCaret(event.nativeKeyEvent.metaState, event.nativeKeyEvent.unicodeChar)
                if ((!caret || typesFromCaret || event.type == KeyEventType.KeyUp) && sentToKeyBindings(event, c.windows, boundKeysDown)) {
                    if (typesFromCaret && event.type == KeyEventType.KeyDown) leaveCaret("")
                    return@onPreviewKeyEvent true
                }
                val ctrlR = event.key == Key.R && event.isCtrlPressed && !hidden
                val ctrlTab = event.key == Key.Tab && event.isCtrlPressed
                val ctrlF = event.key == Key.F && event.isCtrlPressed
                if (ctrlF && event.type == KeyEventType.KeyDown) searching = true
                // Both halves of the press, so the focused field types no "r" and the focus stays where it is
                if (ctrlR && event.type == KeyEventType.KeyDown) historyOpen = true
                if (ctrlTab && event.type == KeyEventType.KeyDown) {
                    val forward = !event.isShiftPressed
                    val target = if (forward) next else previous
                    if (target != null && EngineClient.setFront(target)) swipe.arrive(left = forward != rtl)
                }
                ctrlR || ctrlTab || ctrlF
            },
        ) {
            Column(Modifier.weight(1f).fillMaxWidth().graphicsLayer { translationX = swipe.offset * size.width }) {
                ConnectionBanner(state, name, onReconnect = onReconnect, onStop = if (c.name.isNotEmpty()) ::disconnect else null, Modifier.fillMaxWidth())
                if (searching) {
                    ConsoleSearchBar(search, c.lines, c.linesAdded, onClose = {
                        searching = false
                        search.queryChanged("")
                    }, Modifier.fillMaxWidth())
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val textSizeSp = Settings.textSize.value
                    val colors = ConsoleColors.of(c.status["consoleColors"])
                    val behind = Settings.mapBehindText.value
                    var backProgress by remember { mutableFloatStateOf(0f) }
                    val textBehind = if (mapOpen && behind) textBehindMap(c.appMap, colors) else null
                    // Under the map in front: faint with the setting, or drawn on for a predictive back gesture to show,
                    // but neither read by TalkBack nor reached by a hardware keyboard's focus
                    val covered = if (!mapOpen) Modifier else Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false }
                    val faint = if (textBehind == null) {
                        Modifier
                    } else {
                        Modifier.background(textBehind.ground).graphicsLayer { alpha = textBehind.alpha().let { it + (1f - it) * backProgress } }
                    }
                    // One map on the screen: none behind the text or in its corner while a script's mapper window is
                    // drawn, nor drawn there while the map is in front
                    val inCorner = !behind && !mapOpen && Settings.mapInCorner.value && c.appMap.map?.bitmap != null
                    val mapCorner: (@Composable (MapCorner, PxRect, Modifier) -> Unit)? = if (inCorner) {
                        { place, console, modifier -> AppMapCorner(place, console, onOpen = { onMapOpen(true) }, onHide = ::hideMapInCorner, modifier) }
                    } else {
                        null
                    }
                    ScriptWindowsArea(textSizeSp, Modifier.fillMaxSize().then(covered).then(faint), mapCorner) { consoleModifier, mapperDrawn ->
                        Console(
                            c.lines,
                            c.tail,
                            c.linesAdded,
                            textSizeSp = textSizeSp,
                            colors = colors,
                            onTextSize = Settings.textSize::set,
                            onScreenSize = EngineClient::setScreenSize,
                            consoleModifier.tourTarget(TourTarget.CONSOLE),
                            links = c.links,
                            onMakeTrigger = onMakeTrigger,
                            searchQuery = if (searching) search.query else "",
                            searchFound = search.found.takeIf { searching },
                            blinkText = c.setting(Settings.blinkText),
                            timeStamps = c.timeStampsShown,
                            behindText = { if (!mapperDrawn) BackgroundMap(colors, behind, inFront = mapOpen) },
                            caret = caret,
                            onCaretLeft = ::leaveCaret,
                        )
                    }
                    if (mapOpen) {
                        FullScreenMap(
                            onClose = { onMapOpen(false) },
                            textBehind = textBehind != null,
                            onBackProgress = { backProgress = it },
                            Modifier.fillMaxSize(),
                        )
                    }
                    SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
                }
                InputLine(
                    hiddenInput = hidden,
                    history = c.history,
                    commandLine = c.commandLine,
                    onCommandLineShown = c::commandLineShown,
                    onSend = { line -> c.sendCommand(line).also { if (it) suggestions.sent(line) } },
                    onSendHidden = { secret -> c.sendHidden(secret).also { if (it) suggestions.sentHidden() } },
                    onDismissHidden = c::dismissHidden,
                    onComplete = c::complete,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp).tourTarget(TourTarget.INPUT_LINE),
                    suggestions = suggestions.answer,
                    onInputChanged = suggestions::inputChanged,
                    picked = picked,
                    onPickedShown = { picked = null },
                    takeFocus = c.windows.focusInput,
                    onFocusTaken = c.windows::inputFocusTaken,
                    mapInFront = mapOpen,
                    focusOnStart = switched,
                    clearAfterSend = c.setting(Settings.clearInputAfterSend),
                    highlightHistory = c.setting(Settings.highlightHistory),
                    onHistory = { historyOpen = true },
                    typed = typedInCaret,
                    onTypedShown = { typedInCaret = null },
                )
            }
            if (c.replay.playing) {
                ReplayBar(c.replay, onPaused = c::setReplayPaused, onFaster = c::changeReplaySpeed, onStop = c::stopReplay, Modifier.fillMaxWidth())
            }
            QuickBar(if (hidden) emptyList() else suggestions.pinned, onSend = ::sendNow, Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                val sounds = c.sounds
                if (sounds.played || sounds.scriptsMuted || sounds.gameMuted) {
                    SoundButton(sounds.scriptsMuted && sounds.gameMuted, onToggle = { c.toggleAllSoundsMuted() })
                }
            }
            if (movementPadShown(hidden, screen)) {
                MovementPad(onPress = ::walk, onHide = ::hideMovementPad, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            }
        }
    }
    if (historyOpen && !hidden) {
        HistorySheet(suggestions, onPick = { picked = it }, onSend = ::sendNow, onDismiss = { historyOpen = false })
    }
    if (gamesOpen) {
        GameSheet(
            c,
            name,
            games,
            canReconnect,
            onReconnect = ::reconnect,
            onDisconnect = ::disconnect,
            onSwitch = {
                // First: this game's screen is saved as it leaves the front, and an open sheet would show again on its return
                gamesOpen = false
                EngineClient.setFront(it)
            },
            onClose = ::close,
            onOpenAnother = onBack,
            onDismiss = { gamesOpen = false },
        )
    }
    LaunchedEffect(c.replayRefusal) {
        val reason = c.replayRefusal ?: return@LaunchedEffect
        c.replayRefusalShown()
        // Launched apart, as replayRefusalShown ends this effect
        scope.launch { snackbars.showSnackbar(resources.getString(R.string.game_play_replay_refused, reason), withDismissAction = true) }
    }
    LaunchedEffect(c.secureOfferRefusal) {
        val reason = c.secureOfferRefusal ?: return@LaunchedEffect
        c.secureOfferRefusalShown()
        // Launched apart, as secureOfferRefusalShown ends this effect
        scope.launch { snackbars.showSnackbar(resources.getString(R.string.secure_offer_refused, reason), withDismissAction = true) }
    }
    if (c.secureOffer && !c.secureOfferPutOff) {
        fun answer(accept: Boolean) {
            if (!c.answerSecureOffer(accept)) {
                scope.launch { snackbars.showSnackbar(resources.getString(R.string.secure_offer_refused, resources.getString(R.string.profiles_error_not_attached)), withDismissAction = true) }
            }
        }
        // Back is "not now": desktop has only Yes and No, and No turns the offer off for good
        AlertDialog(
            onDismissRequest = c::putOffSecureOffer,
            confirmButton = { TextButton({ answer(true) }) { Text(stringResource(R.string.secure_offer_accept)) } },
            dismissButton = { TextButton({ answer(false) }) { Text(stringResource(R.string.secure_offer_decline)) } },
            title = { Text(stringResource(R.string.secure_offer_title)) },
            text = { Text(stringResource(R.string.secure_offer_text)) },
        )
    }
    val refused = c.refusedCertificate
    if (refused != null && !c.refusedCertificatePutOff) {
        RefusedCertificateDialog(
            refused,
            onAccept = {
                if (c.acceptRefusedCertificate()) {
                    reconnect()
                } else {
                    scope.launch { snackbars.showSnackbar(resources.getString(R.string.certificate_not_accepted, resources.getString(R.string.profiles_error_not_attached)), withDismissAction = true) }
                }
            },
            onOpenSettings = {
                c.putOffRefusedCertificate()
                onOpenSettings()
            },
            onDismiss = c::putOffRefusedCertificate,
        )
    }
}

// Desktop shows these facts only in the profile's preferences; the app asks as the connection ends, and the choice is
// still the game's settings, so it is remembered and can be undone where desktop keeps it
@Composable
private fun RefusedCertificateDialog(certificate: RefusedCertificate, onAccept: () -> Unit, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (certificate.others) {
                TextButton(onOpenSettings) { Text(stringResource(R.string.certificate_open_settings)) }
            } else {
                TextButton(onAccept) { Text(stringResource(R.string.certificate_accept)) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.certificate_cancel)) } },
        title = { Text(stringResource(R.string.certificate_refused_title)) },
        text = {
            val gap = Modifier.padding(top = 8.dp)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.certificate_refused_text))
                CertificateFacts(certificate)
                if (certificate.others) {
                    Text(stringResource(R.string.certificate_only_ignore_all), gap)
                } else {
                    if (certificate.expired) Text(stringResource(R.string.certificate_turns_on, stringResource(R.string.settings_accept_expired_certificate)), gap)
                    if (certificate.selfSigned) Text(stringResource(R.string.certificate_turns_on, stringResource(R.string.settings_accept_self_signed_certificate)), gap)
                    Text(stringResource(R.string.certificate_risk), gap)
                }
            }
        },
    )
}

/**
 * Whether [event] is for a key binding of the profile, which is then pressed: a key down whose Qt key the bindings
 * take, outside a script's text box, and the up of a key whose down was, so the focused field types neither half.
 * [down] holds the Android key codes whose down went to the bindings.
 */
private fun sentToKeyBindings(event: KeyEvent, windows: ScriptWindowsClient, down: MutableSet<Int>): Boolean {
    val native = event.nativeKeyEvent
    if (event.type == KeyEventType.KeyUp) return down.remove(native.keyCode)
    if (event.type != KeyEventType.KeyDown || windows.focusedTextBox != null) return false
    val key = qtKey(native) ?: return false
    if (key !in windows.keyBindings) return false
    // Negative for a dead key, which types nothing yet
    val typed = native.unicodeChar
    // Unsent, as with no link to the engine, the key types as any other
    if (!windows.pressKey(key, if (typed > 0) String(Character.toChars(typed)) else "")) return false
    down += native.keyCode
    return true
}

/** [native] as Qt names it, as the key bindings do. */
private fun qtKey(native: android.view.KeyEvent): KeyBinding? = HardwareKeys.of(native.keyCode, native.metaState, native.getUnicodeChar(native.metaState and android.view.KeyEvent.META_SHIFT_MASK))

private enum class DesktopShortcut { SCRIPTS, SETTINGS, MAP, CONNECT, DISCONNECT, RECONNECT, CLOSE }

/**
 * Which of the main window's shortcuts desktop Mudlet gives off macOS (`mudlet.cpp`) [event] is, for the screens the
 * app has: Alt+E the scripts, Alt+P the settings, Alt+M the map, Alt+C the games, Alt+D, Alt+R and Alt+W disconnect,
 * reconnect and close the game. As a QShortcut there, it beats a key binding on the same key.
 */
private fun desktopShortcut(event: KeyEvent): DesktopShortcut? {
    // The left Alt alone: the right one is AltGr on most layouts, which types
    val leftAlt = event.nativeKeyEvent.metaState and android.view.KeyEvent.META_ALT_LEFT_ON != 0
    if (!leftAlt || event.isCtrlPressed || event.isShiftPressed || event.isMetaPressed) return null
    return when (event.key) {
        Key.E -> DesktopShortcut.SCRIPTS
        Key.P -> DesktopShortcut.SETTINGS
        Key.M -> DesktopShortcut.MAP
        Key.C -> DesktopShortcut.CONNECT
        Key.D -> DesktopShortcut.DISCONNECT
        Key.R -> DesktopShortcut.RECONNECT
        Key.W -> DesktopShortcut.CLOSE
        else -> null
    }
}
