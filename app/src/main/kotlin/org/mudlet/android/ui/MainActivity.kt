package org.mudlet.android.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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

// ANSI black is lifted to dark grey so it stays readable on the black output pane
private val ansi = listOf(
    0xFF5C5C5C, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00, 0xFF2060EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
    0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00, 0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
).map { Color(it) }
private val defaultFg = Color(0xFFC0C0C0)

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineClient.init(this)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        EngineClient.refreshServiceState()
        if (EngineClient.engineWanted && EngineClient.fgsState == "not running") EngineClient.startEngine()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SelfTestScreen(onKillUi = { finish() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        EngineClient.attach()
    }

    override fun onStop() {
        EngineClient.detach()
        super.onStop()
    }
}

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
            start, end,
        )
        i += RUN_STRIDE
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelfTestScreen(onKillUi: () -> Unit) {
    val c = EngineClient
    val s = c.status
    var host by rememberSaveable { mutableStateOf("aardmud.org") }
    var port by rememberSaveable { mutableStateOf("23") }
    var command by rememberSaveable { mutableStateOf("") }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
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

    Column(
        Modifier.fillMaxSize().background(Color(0xFF101010)).safeDrawingPadding().imePadding().padding(6.dp),
    ) {
        val small = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = Color(0xFFDDDDDD))
        if (showDebug) {
            Text(
                "Qt up ${s["qtStartMsAfterAppOnCreate"] ?: "?"} ms after Application.onCreate (:engine), " +
                    "${s["qtStartMsAfterServiceOnCreate"] ?: "?"} ms after Service.onCreate; UI start->hello ${if (c.startToHelloMs >= 0) "${c.startToHelloMs} ms" else "- (engine was already up)"}",
                style = small,
            )
            Text(
                "Font ${s["fontFamily"] ?: "?"}, 'M' ${s["advanceM"] ?: "?"} px @14px, columns ${s["columns"] ?: "?"} " +
                    "(${s["viewWidthPx"] ?: "?"} px), QPA ${s["platform"] ?: "?"}",
                style = small,
            )
            Text(
                "Lines ${s["linesReceived"] ?: 0}, prompts ${s["promptsReceived"] ?: 0}, Lua onLine ${s["luaOnLineCalls"] ?: 0}, " +
                    "notifications posted ${s["notificationsPosted"] ?: 0} (UI saw ${c.notifyCount})",
                style = small,
            )
            Text(
                "Heartbeat ticks ${s["heartbeatTicks"] ?: 0}, last gap ${s["heartbeatLastGapMs"] ?: "-"} ms, " +
                    "largest gap ${s["heartbeatMaxGapMs"] ?: "-"} ms",
                style = small,
            )
            Text(
                "Qt appState ${s["appState"] ?: "?"} (Qt::ApplicationState; 1 = Hidden), " +
                    "service-ready handshake ${s["serviceReadyWait"] ?: "?"}",
                style = small,
            )
            Text("FGS (type specialUse): ${c.fgsState}", style = small)
            Text("PIDs: UI ${Process.myPid()}, engine ${c.enginePid}; game: ${s["connection"] ?: "?"}", style = small)
            Text(
                "Link: ${c.linkState}; attaches ${c.attachCount} (last ${c.lastAttachMs} ms), engine losses ${c.engineLosses} " +
                    "(loss->attach ${c.lastLossToAttachMs} ms), lines skipped ${c.gapLines}",
                style = small,
            )
            if (s["engineKind"] == "libmudlet") {
                Text(
                    "libmudlet: ${s["profile"] ?: "?"}, start ${s["startMs"] ?: "-"} ms, loaded after ${s["profileLoadMs"] ?: "-"} ms; " +
                        "Lua files ${s["luaFiles"] ?: "?"}; local lines ${s["localLines"] ?: 0}, error console ${s["errorConsoleLines"] ?: 0}",
                    style = small,
                )
                s["luaError"]?.let { Text("Last error: $it", style = small.copy(color = Color(0xFFFF8080))) }
            }
            if (c.pingResult.isNotEmpty()) Text("Ping: ${c.pingResult}", style = small)
            if (c.lastLuaResult.isNotEmpty()) Text("Lua: ${c.lastLuaResult}", style = small)
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(host, { host = it }, Modifier.weight(1f), label = { Text("Host") }, singleLine = true)
            OutlinedTextField(
                port, { port = it }, Modifier.weight(0.4f), label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button({ c.send(Requests.connect(host.trim(), port.toIntOrNull() ?: 23)) }) { Text("Connect") }
            Button({ c.send(Requests.disconnect()) }) { Text("Disconnect") }
            OutlinedButton({ showDebug = !showDebug }) { Text(if (showDebug) "Hide debug" else "Debug") }
        }
        if (showDebug) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton({ c.runPings() }) { Text("Ping x300") }
            OutlinedButton(onKillUi) { Text("Kill UI") }
            OutlinedButton({ Process.killProcess(Process.myPid()) }) { Text("Kill UI process") }
            OutlinedButton({ c.stopEngine() }) { Text("Stop engine") }
            OutlinedButton({ c.startEngine() }) { Text("Start engine") }
            OutlinedButton({ c.killEngineProcess() }) { Text("Kill engine process") }
            OutlinedButton({
                platform = if (platform == "android") "offscreen" else "android"
                prefs.edit().putString(EngineService.PREF_PLATFORM, platform).apply()
            }) { Text("QPA next start: $platform") }
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
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                command, { command = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("command, or: lua return lineCount") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Button({ submit() }) { Text("Send") }
        }
    }
}
