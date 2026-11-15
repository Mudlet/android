package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MudletTopBar

/**
 * The console, the input line, the quick bar and the connection bar, top to bottom, which is also the order TalkBack
 * reads them in. The keyboard pushes them up, so the console shrinks above it. The input line's suggestions and the
 * quick bar's pinned commands come from the profile the engine runs ([rememberEngineSuggestions]), which learns from
 * what is sent. The quick bar's History button, or Ctrl+R on a hardware keyboard, opens the command history
 * ([HistorySheet]). While the game hides input there is neither quick bar nor history.
 */
@Composable
fun GameScreen(onBack: () -> Unit) {
    val c = EngineClient
    val suggestions = rememberEngineSuggestions()
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notSent = stringResource(R.string.input_not_sent)
    val hidden = c.hiddenInput
    // Hidden input closes the history for good: it does not open again when the password field goes
    LaunchedEffect(hidden) { if (hidden) historyOpen = false }

    // A command from the quick bar or the history, sent as the input line sends
    fun sendNow(command: String) {
        if (c.sendCommand(command)) suggestions.sent(command) else scope.launch { snackbar.showSnackbar(notSent) }
    }

    Scaffold(topBar = { MudletTopBar(stringResource(R.string.game_title), onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().onPreviewKeyEvent { event ->
                val ctrlR = event.key == Key.R && event.isCtrlPressed && !hidden
                // Both halves of the press, so the focused field types no "r"
                if (ctrlR && event.type == KeyEventType.KeyDown) historyOpen = true
                ctrlR
            },
        ) {
            Console(c.lines, c.tail, c.linesAdded, onWidth = c::setViewWidth, Modifier.weight(1f).fillMaxWidth())
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
