package org.mudlet.android.ui.selftest

import android.content.Context
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.engine.EngineService
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.Requests
import org.mudlet.android.ui.CONSOLE_BACKGROUND
import org.mudlet.android.ui.CONSOLE_FOREGROUND
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.ansiText

private val consoleStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

// The profile the self-test's Connect saves and opens
private const val PROFILE = "Self-test"

/**
 * The developers' screen, in debug builds only: connect to a game, see its coloured output, send commands and Lua,
 * and, behind "Debug", the engine's start times, heartbeat, process ids and buttons that stop or kill either process.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelfTestScreen(onBack: () -> Unit, onKillUi: () -> Unit) {
    val c = EngineClient
    // The front profile's session: the profile's console, statuses and requests
    val p = c.front
    val s = c.engineStatus + p.status
    var host by rememberSaveable { mutableStateOf("aardmud.org") }
    var port by rememberSaveable { mutableStateOf("23") }
    var tls by rememberSaveable { mutableStateOf(false) }
    var command by rememberSaveable { mutableStateOf("") }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var connectError by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The console's character cell, from which the columns and rows that fit go to the engine
    val cell = remember(measurer, density) { measurer.measure("M", consoleStyle).size }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(EngineService.PREFS, Context.MODE_PRIVATE) }
    var platform by remember { mutableStateOf(prefs.getString(EngineService.PREF_PLATFORM, "android") ?: "android") }

    LaunchedEffect(Unit) {
        while (true) {
            c.refreshServiceState()
            delay(2000)
        }
    }
    val tail = p.tail
    val itemCount = p.lines.size + if (tail != null) 1 else 0
    LaunchedEffect(itemCount, tail) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    MudletScaffold(stringResource(R.string.selftest_title), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(6.dp)) {
            val small = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
            if (showDebug) {
                val helloMs = if (c.startToHelloMs >= 0) {
                    stringResource(R.string.selftest_hello_ms, c.startToHelloMs.toString())
                } else {
                    stringResource(R.string.selftest_hello_none)
                }
                Text(
                    stringResource(
                        R.string.selftest_qt_start,
                        s["qtStartMsAfterAppOnCreate"] ?: "?",
                        s["qtStartMsAfterServiceOnCreate"] ?: "?",
                        helloMs,
                    ),
                    style = small,
                )
                Text(
                    stringResource(
                        R.string.selftest_font,
                        s["fontFamily"] ?: "?",
                        s["advanceM"] ?: "?",
                        screen.width.toString(),
                        screen.height.toString(),
                        s["platform"] ?: "?",
                    ),
                    style = small,
                )
                Text(
                    stringResource(
                        R.string.selftest_counts,
                        s["linesReceived"] ?: "0",
                        s["promptsReceived"] ?: "0",
                        s["luaOnLineCalls"] ?: "0",
                        s["notificationsPosted"] ?: "0",
                        c.notifyCount.toString(),
                    ),
                    style = small,
                )
                Text(
                    stringResource(
                        R.string.selftest_heartbeat,
                        s["heartbeatTicks"] ?: "0",
                        s["heartbeatLastGapMs"] ?: "-",
                        s["heartbeatMaxGapMs"] ?: "-",
                    ),
                    style = small,
                )
                Text(stringResource(R.string.selftest_app_state, s["appState"] ?: "?", s["serviceReadyWait"] ?: "?"), style = small)
                Text(stringResource(R.string.selftest_fgs, c.fgsState), style = small)
                Text(
                    stringResource(R.string.selftest_pids, Process.myPid().toString(), c.enginePid.toString(), s["connection"] ?: "?"),
                    style = small,
                )
                Text(
                    stringResource(
                        R.string.selftest_link,
                        c.linkState,
                        c.attachCount.toString(),
                        c.lastAttachMs.toString(),
                        c.engineLosses.toString(),
                        c.lastLossToAttachMs.toString(),
                        c.gapLines.toString(),
                    ),
                    style = small,
                )
                if (s["engineKind"] == "libmudlet") {
                    Text(
                        stringResource(
                            R.string.selftest_libmudlet,
                            s["profile"] ?: "?",
                            s["startMs"] ?: "-",
                            s["profileLoadMs"] ?: "-",
                            s["luaFiles"] ?: "?",
                            s["localLines"] ?: "0",
                            s["errorConsoleLines"] ?: "0",
                            s["restore"] ?: "-",
                        ),
                        style = small,
                    )
                    s["luaError"]?.let {
                        Text(stringResource(R.string.selftest_last_error, it), style = small.copy(color = MaterialTheme.colorScheme.error))
                    }
                }
                if (c.pingResult.isNotEmpty()) Text(stringResource(R.string.selftest_ping, c.pingResult), style = small)
                if (p.lastLuaResult.isNotEmpty()) Text(stringResource(R.string.selftest_lua, p.lastLuaResult), style = small)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    host,
                    { host = it },
                    Modifier.weight(1f),
                    label = { Text(stringResource(R.string.selftest_host)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    port,
                    { port = it },
                    Modifier.weight(0.4f),
                    label = { Text(stringResource(R.string.selftest_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                // One control with its label, so TalkBack reads "TLS" with the box and a tap on the word toggles it
                Row(
                    Modifier.minimumInteractiveComponentSize().toggleable(tls, role = Role.Checkbox) { tls = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(tls, null)
                    Text(stringResource(R.string.selftest_tls))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button({
                    connectError = ""
                    c.saveAndOpenProfile(Profile(PROFILE, host.trim(), port.toIntOrNull() ?: 23, tls, "", false), null) {
                        connectError = it ?: ""
                    }
                }) {
                    Text(stringResource(R.string.selftest_connect))
                }
                Button({ p.disconnect() }) { Text(stringResource(R.string.selftest_disconnect)) }
                OutlinedButton({ showDebug = !showDebug }) {
                    Text(stringResource(if (showDebug) R.string.selftest_hide_debug else R.string.selftest_show_debug))
                }
            }
            if (connectError.isNotEmpty()) {
                Text(connectError, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error))
            }
            if (showDebug) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton({ c.runPings() }) { Text(stringResource(R.string.selftest_ping_many)) }
                    OutlinedButton(onKillUi) { Text(stringResource(R.string.selftest_kill_ui)) }
                    OutlinedButton({ Process.killProcess(Process.myPid()) }) { Text(stringResource(R.string.selftest_kill_ui_process)) }
                    OutlinedButton({ c.stopEngine() }) { Text(stringResource(R.string.selftest_stop_engine)) }
                    OutlinedButton({ c.startEngine() }) { Text(stringResource(R.string.selftest_start_engine)) }
                    OutlinedButton({ c.killEngineProcess() }) { Text(stringResource(R.string.selftest_kill_engine_process)) }
                    OutlinedButton({
                        platform = if (platform == "android") "offscreen" else "android"
                        prefs.edit { putString(EngineService.PREF_PLATFORM, platform) }
                    }) { Text(stringResource(R.string.selftest_qpa_next_start, platform)) }
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().background(CONSOLE_BACKGROUND).onSizeChanged {
                    if (cell.width > 0 && cell.height > 0) {
                        screen = IntSize((it.width / cell.width).coerceIn(1, 65535), (it.height / cell.height).coerceIn(1, 65535))
                        c.setScreenSize(screen.width, screen.height)
                    }
                },
                state = listState,
            ) {
                items(itemCount) { i ->
                    val line = if (i < p.lines.size) p.lines[i] else tail ?: return@items
                    Text(ansiText(line), color = CONSOLE_FOREGROUND, style = consoleStyle)
                }
            }

            val submit = {
                val text = command
                if (text.startsWith("lua ")) {
                    p.send(Requests.runLua(text.removePrefix("lua "), p.newRequestId()))
                } else {
                    p.send(Requests.send(text))
                }
                command = ""
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    command,
                    { command = it },
                    Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.selftest_command)) },
                    placeholder = { Text(stringResource(R.string.selftest_command_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                Button({ submit() }) { Text(stringResource(R.string.selftest_send)) }
            }
        }
    }
}
