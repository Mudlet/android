package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MudletTopBar

/**
 * The console with the windows the game's scripts draw around it, the input line and the connection bar, top to
 * bottom. TalkBack reads the console, the input line and the bar first, then the script windows and their switcher.
 * The keyboard pushes them up, so the console shrinks above it.
 */
@Composable
fun GameScreen(onBack: () -> Unit) {
    val c = EngineClient
    Scaffold(topBar = { MudletTopBar(stringResource(R.string.game_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            ScriptWindowsArea(Modifier.weight(1f).fillMaxWidth()) { consoleModifier ->
                Console(c.lines, c.tail, c.linesAdded, onWidth = c::setViewWidth, consoleModifier)
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
