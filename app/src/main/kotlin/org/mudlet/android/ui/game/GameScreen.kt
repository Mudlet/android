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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The console, the input line and the connection bar, top to bottom, which is also the order TalkBack reads them in.
 * The keyboard pushes them up, so the console shrinks above it. Why the engine could not send something shows over
 * the console's foot, where the keyboard does not hide it.
 */
@Composable
fun GameScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val c = EngineClient
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
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
    Scaffold(
        topBar = {
            MudletTopBar(stringResource(R.string.game_title), onBack) {
                IconButton(onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Console(
                    c.lines,
                    c.tail,
                    c.linesAdded,
                    textSizeSp = Settings.textSize.value,
                    colors = ConsoleColors.of(c.status["consoleColors"]),
                    onTextSize = Settings.textSize::set,
                    onScreenSize = c::setScreenSize,
                    Modifier.fillMaxSize(),
                )
                SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
            }
            InputLine(
                hiddenInput = c.hiddenInput,
                history = c.history,
                commandLine = c.commandLine,
                onCommandLineShown = c::commandLineShown,
                onSend = c::sendCommand,
                onSendHidden = c::sendHidden,
                onDismissHidden = c::dismissHidden,
                onComplete = c::complete,
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
            )
            ConnectionBar(
                ConnectionState.of(engineAttached = c.enginePid > 0, connection = c.status["connection"]),
                canReconnect = c.lastAddress != null,
                onReconnect = { c.reconnect() },
                onDisconnect = { c.disconnect() },
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
