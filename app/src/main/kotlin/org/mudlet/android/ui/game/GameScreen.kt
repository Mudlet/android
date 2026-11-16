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

/**
 * The console with the windows the game's scripts draw around it, the input line, the quick bar and the connection bar,
 * top to bottom. After the title bar, TalkBack reads the console, the input line, the quick bar and the connection bar,
 * then the script windows and their switcher, then the button bars. The keyboard pushes them up, so the console shrinks
 * above it. The input line's suggestions and the quick bar's pinned commands come from the profile the engine runs
 * ([rememberEngineSuggestions]), which learns from what is sent. The quick bar's History button, or Ctrl+R on a
 * hardware keyboard, opens the command history ([HistorySheet]). While the game hides input there is neither quick bar
 * nor history. Why the engine could not send something shows over the console's foot, where the keyboard does not hide
 * it. The map is behind the console's text while the setting has it there ([BackgroundMap]), and the title bar's map
 * button brings it to the front over all but the title bar and the connection bar ([FullScreenMap]), input line and
 * quick bar included, until Back or the button again. All of it is the front profile's ([EngineClient.front]), and what
 * the screen keeps of a profile, such as the typed line and where the console is scrolled to, waits for it while
 * another is in front.
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
    kept.SaveableStateProvider(front.name) { ProfileGameScreen(front, onBack, onOpenSettings) }
}

@Composable
private fun ProfileGameScreen(c: ProfileSession, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val suggestions = rememberEngineSuggestions()
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var mapOpen by rememberSaveable { mutableStateOf(false) }
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
    // The map behind the text is not there to be moved: once the map in front closes, not as a rotation remakes it, it
    // follows the player again
    LaunchedEffect(mapOpen) { if (!mapOpen && !c.appMap.mover.following) c.appMap.mover.followPlayer() }
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

    // A command from the quick bar or the history, sent as the input line sends
    fun sendNow(command: String) {
        if (c.sendCommand(command)) suggestions.sent(command) else scope.launch { snackbars.showSnackbar(notSent) }
    }

    Scaffold(
        topBar = {
            MudletTopBar(stringResource(R.string.game_title), onBack) {
                ScriptCommandsButton()
                MapButton(mapOpen) { mapOpen = it }
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
                    )
                    if (!hidden) {
                        QuickBar(
                            suggestions.pinned,
                            onHistory = { historyOpen = true },
                            onSend = ::sendNow,
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        )
                    }
                }
                if (mapOpen) {
                    FullScreenMap(onClose = { mapOpen = false }, Modifier.fillMaxSize())
                    SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
                }
            }
            ConnectionBar(
                ConnectionState.of(engineAttached = EngineClient.enginePid > 0, connection = c.status["connection"]),
                canReconnect = EngineClient.canReconnect,
                onReconnect = {
                    EngineClient.reconnect(passwords::get) { error ->
                        scope.launch { snackbars.showSnackbar(resources.getString(R.string.connection_reconnect_refused, error), withDismissAction = true) }
                    }
                },
                onDisconnect = { c.disconnect() },
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
    if (historyOpen && !hidden) {
        HistorySheet(suggestions, onPick = { picked = it }, onSend = ::sendNow, onDismiss = { historyOpen = false })
    }
}
