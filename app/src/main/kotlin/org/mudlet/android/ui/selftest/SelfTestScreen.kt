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
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.engine.EngineService
import org.mudlet.android.protocol.RUN_BOLD
import org.mudlet.android.protocol.RUN_CONCEALED
import org.mudlet.android.protocol.RUN_ITALIC
import org.mudlet.android.protocol.RUN_REVERSE
import org.mudlet.android.protocol.RUN_RGB
import org.mudlet.android.protocol.RUN_STRIDE
import org.mudlet.android.protocol.RUN_STRIKE
import org.mudlet.android.protocol.RUN_UNDERLINE
import org.mudlet.android.protocol.Requests
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MudletTopBar
import org.mudlet.android.ui.UiLine

// ANSI black is lifted to dark grey so it stays readable on the black output pane
private val ansi = listOf(
    0xFF5C5C5C, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00, 0xFF2060EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
    0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00, 0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
).map { Color(it) }
private val defaultFg = Color(0xFFC0C0C0)

private fun runColour(code: Int): Color? = when {
    code in 0..15 -> ansi[code]
    code >= RUN_RGB -> Color(0xFF000000.toInt() or (code and 0xFFFFFF))
    else -> null
}

private fun render(line: UiLine): AnnotatedString = buildAnnotatedString {
    if (line.marker) {
        pushStyle(SpanStyle(color = Color(0xFFFFB000)))
        append(line.text)
        pop()
        return@buildAnnotatedString
    }
    append(line.text)
    var i = 0
    while (i + RUN_STRIDE - 1 < line.runs.size) {
        val start = line.runs[i]
        val end = (start + line.runs[i + 1]).coerceAtMost(line.text.length)
        val attributes = line.runs[i + 4]
        var fg = runColour(line.runs[i + 2]) ?: defaultFg
        var bg = runColour(line.runs[i + 3])
        if (bg == null && fg == Color.Black) fg = ansi[0]
        if (attributes and RUN_REVERSE != 0) {
            val swapped = bg ?: Color.Black
            bg = fg
            fg = swapped
        }
        if (attributes and RUN_CONCEALED != 0) fg = bg ?: Color.Transparent
        val decorations = listOfNotNull(
            TextDecoration.Underline.takeIf { attributes and RUN_UNDERLINE != 0 },
            TextDecoration.LineThrough.takeIf { attributes and RUN_STRIKE != 0 },
        )
        addStyle(
            SpanStyle(
                color = fg,
                background = bg ?: Color.Unspecified,
                fontWeight = if (attributes and RUN_BOLD != 0) FontWeight.Bold else null,
                fontStyle = if (attributes and RUN_ITALIC != 0) FontStyle.Italic else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            ),
            start,
            end,
        )
        i += RUN_STRIDE
    }
}

/**
 * The developers' screen, in debug builds only: connect to a game, see its coloured output, send commands and Lua,
 * and, behind "Debug", the engine's start times, heartbeat, process ids and buttons that stop or kill either process.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelfTestScreen(onBack: () -> Unit, onKillUi: () -> Unit) {
    val c = EngineClient
    val s = c.status
    var host by rememberSaveable { mutableStateOf("aardmud.org") }
    var port by rememberSaveable { mutableStateOf("23") }
    var tls by rememberSaveable { mutableStateOf(false) }
    var command by rememberSaveable { mutableStateOf("") }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(EngineService.PREFS, Context.MODE_PRIVATE) }
    var platform by remember { mutableStateOf(prefs.getString(EngineService.PREF_PLATFORM, "android") ?: "android") }

    LaunchedEffect(Unit) {
        while (true) {
            c.refreshServiceState()
            delay(2000)
        }
    }
    val tail = c.tail
    val itemCount = c.lines.size + if (tail != null) 1 else 0
    LaunchedEffect(itemCount, tail) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Scaffold(topBar = { MudletTopBar(stringResource(R.string.selftest_title), onBack) }) { padding ->
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
                        s["columns"] ?: "?",
                        s["viewWidthPx"] ?: "?",
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
                if (c.lastLuaResult.isNotEmpty()) Text(stringResource(R.string.selftest_lua, c.lastLuaResult), style = small)
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
                Button({ c.send(Requests.connect(host.trim(), port.toIntOrNull() ?: 23, tls)) }) {
                    Text(stringResource(R.string.selftest_connect))
                }
                Button({ c.send(Requests.disconnect()) }) { Text(stringResource(R.string.selftest_disconnect)) }
                OutlinedButton({ showDebug = !showDebug }) {
                    Text(stringResource(if (showDebug) R.string.selftest_hide_debug else R.string.selftest_show_debug))
                }
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
                Modifier.weight(1f).fillMaxWidth().background(Color.Black).onSizeChanged { c.setViewWidth(it.width) },
                state = listState,
            ) {
                items(itemCount) { i ->
                    val line = if (i < c.lines.size) c.lines[i] else tail ?: return@items
                    Text(render(line), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = defaultFg)
                }
            }

            val submit = {
                val text = command
                if (text.startsWith("lua ")) {
                    c.send(Requests.runLua(text.removePrefix("lua "), System.nanoTime()))
                } else {
                    c.send(Requests.send(text))
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
