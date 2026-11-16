package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MudletTopBar
import org.mudlet.android.ui.ProfileSession
import kotlin.reflect.KClass

/**
 * The console with the windows the game's scripts draw around it, the input line, and the row below it, top to bottom.
 * The row holds the game pill ([GamePill]), which shows the front game's connection and opens the game sheet
 * ([GameSheet]) with Reconnect, Disconnect, the open games and "Open another game", which goes back to the profile
 * list ([onBack]), where Connect opens one more beside those open; beside the pill, while the game is not connected,
 * one chip with what to do about it ([ConnectionChip]); then the quick bar's History and pinned commands. After the title bar, TalkBack reads the console, the input line and that row, then the script
 * windows and their switcher, then the button bars. The keyboard pushes them up, so the console shrinks above it. The
 * input line's suggestions and the quick bar's pinned commands come from the profile the engine runs
 * ([rememberEngineSuggestions]), which learns from what is sent. The History button, or Ctrl+R on a hardware keyboard,
 * opens the command history ([HistorySheet]). While the game hides input the row has only the pill and its chip, and
 * there is no history. Why the engine could not send something shows over the console's foot, where the keyboard does
 * not hide it. The map is behind the console's text while the setting has it there ([BackgroundMap]), and the title
 * bar's map button brings it to the front over the console and the input line ([FullScreenMap]), until Back or the
 * button again, and for the game switched to; the row stays below it, without History, as the command it picks would
 * go to the covered line.
 *
 * All of it is the front profile's ([EngineClient.front]), and what the screen keeps of a profile, such as the typed
 * line and where the console is scrolled to, waits for it while another is in front. When another game comes to the
 * front, TalkBack says its name and state and the focus goes to its input line; each change of the front game's
 * connection is said once, with the game's name first, so Mudlet's retries after a drop are not each read out.
 */
@Composable
fun GameScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
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
    kept.SaveableStateProvider(front.name) { ProfileGameScreen(front, switched, mapOpen, { mapOpen = it }, onBack, onOpenSettings) }
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
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val suggestions = rememberEngineSuggestions()
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var gamesOpen by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notSent = stringResource(R.string.input_not_sent)
    val context = LocalContext.current
    val passwords = remember { PasswordStore(context) }
    val resources = LocalResources.current
    val hidden = c.hiddenInput
    // Hidden input closes the history for good: it does not open again when the password field goes
    LaunchedEffect(hidden) { if (hidden) historyOpen = false }
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
    val index = games.indexOf(c)
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

    fun close(game: String) = EngineClient.closeProfile(game) { error ->
        if (error != null) {
            scope.launch { snackbars.showSnackbar(resources.getString(R.string.game_close_refused, error), withDismissAction = true) }
        } else if (EngineClient.sessions.isEmpty()) {
            onBack()
        }
    }

    // A command from the quick bar or the history, sent as the input line sends
    fun sendNow(command: String) {
        if (c.sendCommand(command)) suggestions.sent(command) else scope.launch { snackbars.showSnackbar(notSent) }
    }

    Scaffold(
        topBar = {
            MudletTopBar(stringResource(R.string.game_title), onBack) {
                ScriptCommandsButton()
                MapButton(mapOpen, onMapOpen)
                IconButton(onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().onPreviewKeyEvent { event ->
                val ctrlR = event.key == Key.R && event.isCtrlPressed && !hidden && !mapOpen
                // Both halves of the press, so the focused field types no "r"
                if (ctrlR && event.type == KeyEventType.KeyDown) historyOpen = true
                ctrlR
            },
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Drawn on under the map in front, which a predictive back gesture shrinks to show it, but neither read
                // by TalkBack nor reached by a hardware keyboard's focus; the input line lets go of the keyboard
                val covered = if (mapOpen) Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false } else Modifier
                Column(Modifier.fillMaxSize().then(covered)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val textSizeSp = Settings.textSize.value
                        val colors = ConsoleColors.of(c.status["consoleColors"])
                        // One map on the screen: none behind the text while a script's mapper window is drawn
                        ScriptWindowsArea(textSizeSp, Modifier.fillMaxSize()) { consoleModifier, mapperDrawn ->
                            Console(
                                c.lines,
                                c.tail,
                                c.linesAdded,
                                textSizeSp = textSizeSp,
                                colors = colors,
                                onTextSize = Settings.textSize::set,
                                onScreenSize = EngineClient::setScreenSize,
                                consoleModifier,
                            ) { if (Settings.mapBehindText.value && !mapperDrawn) BackgroundMap(colors) }
                        }
                        if (!mapOpen) SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
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
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        suggestions = suggestions.answer,
                        onInputChanged = suggestions::inputChanged,
                        picked = picked,
                        onPickedShown = { picked = null },
                        covered = mapOpen,
                        focusOnStart = switched,
                    )
                }
                if (mapOpen) {
                    FullScreenMap(onClose = { onMapOpen(false) }, Modifier.fillMaxSize())
                    SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
                }
            }
            QuickBar(
                if (hidden) emptyList() else suggestions.pinned,
                onHistory = if (hidden || mapOpen) null else ({ historyOpen = true }),
                onSend = ::sendNow,
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                GamePill(
                    name,
                    state,
                    index,
                    games.size,
                    onShowGames = { gamesOpen = true },
                    onReconnect = if (canReconnect) ::reconnect else null,
                    onDisconnect = if (c.name.isNotEmpty() && state.canDisconnect) ::disconnect else null,
                    previous = games.getOrNull(index - 1)?.name,
                    next = if (index >= 0) games.getOrNull(index + 1)?.name else null,
                    onSwitch = { EngineClient.setFront(it) },
                )
                when (val action = state.action) {
                    null -> {}
                    ConnectionAction.STOP -> if (c.name.isNotEmpty()) ConnectionChip(action, name, onClick = ::disconnect)
                    else -> if (canReconnect) ConnectionChip(action, reconnectName, onClick = ::reconnect)
                }
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
}
