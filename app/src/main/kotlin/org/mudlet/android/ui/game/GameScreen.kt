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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MudletTopBar

/**
 * The console with the windows the game's scripts draw around it, the input line, the quick bar and the connection bar,
 * top to bottom. TalkBack reads the console, the input line, the quick bar and the connection bar first, then the script
 * windows and their switcher. The keyboard pushes them up, so the console shrinks above it. The input line's suggestions
 * and the quick bar's pinned commands come from the profile the engine runs ([rememberEngineSuggestions]), which learns
 * from what is sent. The quick bar's History button, or Ctrl+R on a hardware keyboard, opens the command history
 * ([HistorySheet]). While the game hides input there is neither quick bar nor history. Why the engine could not send
 * something shows over the console's foot, where the keyboard does not hide it.
 */
@Composable
fun GameScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val c = EngineClient
    val suggestions = rememberEngineSuggestions()
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notSent = stringResource(R.string.input_not_sent)
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

    // A command from the quick bar or the history, sent as the input line sends
    fun sendNow(command: String) {
        if (c.sendCommand(command)) suggestions.sent(command) else scope.launch { snackbars.showSnackbar(notSent) }
    }

    Scaffold(
        topBar = {
            MudletTopBar(stringResource(R.string.game_title), onBack) {
                IconButton(onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().onPreviewKeyEvent { event ->
                val ctrlR = event.key == Key.R && event.isCtrlPressed && !hidden
                // Both halves of the press, so the focused field types no "r"
                if (ctrlR && event.type == KeyEventType.KeyDown) historyOpen = true
                ctrlR
            },
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val textSizeSp = Settings.textSize.value
                ScriptWindowsArea(textSizeSp, Modifier.fillMaxSize()) { consoleModifier ->
                    Console(
                        c.lines,
                        c.tail,
                        c.linesAdded,
                        textSizeSp = textSizeSp,
                        colors = ConsoleColors.of(c.status["consoleColors"]),
                        onTextSize = Settings.textSize::set,
                        onScreenSize = c::setScreenSize,
                        consoleModifier,
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
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                suggestions = suggestions.answer,
                onInputChanged = suggestions::inputChanged,
                picked = picked,
                onPickedShown = { picked = null },
            )
            if (!hidden) {
                QuickBar(
                    suggestions.pinned,
                    onHistory = { historyOpen = true },
                    onSend = ::sendNow,
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            ConnectionBar(
                ConnectionState.of(engineAttached = c.enginePid > 0, connection = c.status["connection"]),
                canReconnect = c.lastAddress != null,
                onReconnect = { c.reconnect() },
                onDisconnect = { c.disconnect() },
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
    if (historyOpen && !hidden) {
        HistorySheet(suggestions, onPick = { picked = it }, onSend = ::sendNow, onDismiss = { historyOpen = false })
    }
}
