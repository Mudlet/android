package org.mudlet.proto.a

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Single Activity, no Qt: it starts and binds MudletEngineService and renders EngineRepository. */
class MainActivity : ComponentActivity() {
    private var engine by mutableStateOf<MudletEngineService?>(null)
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            engine = (binder as MudletEngineService.LocalBinder).service
            EngineRepository.put("ui.bound", "yes")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
            EngineRepository.put("ui.bound", "lost (process died?)")
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        EngineRepository.put("ui.notifPermission", if (it) "granted" else "denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineRepository.put("ui.pid", Process.myPid().toString())
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        startEngine()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SelfTestScreen(
                    engine = engine,
                    onKillUi = { finish() },
                    onStartEngine = { startEngine() },
                    onStopEngine = { exitQt -> stopEngine(exitQt) },
                )
            }
        }
    }

    override fun onDestroy() {
        unbind()
        super.onDestroy()
    }

    private fun startEngine() {
        EngineRepository.exitQtOnDestroy = false
        val intent = Intent(this, MudletEngineService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun unbind() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        engine = null
    }

    private fun stopEngine(exitQt: Boolean) {
        EngineRepository.exitQtOnDestroy = exitQt
        unbind()
        stopService(Intent(this, MudletEngineService::class.java))
        EngineRepository.put("ui.bound", "no (stopped)")
    }
}

private val ansiPalette = listOf(
    0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00, 0xFF3B78FF, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
    0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00, 0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
).map { Color(it) }

private fun colourise(line: UiLine): AnnotatedString = buildAnnotatedString {
    append(line.text)
    val r = line.runs
    var i = 0
    while (i + 2 < r.size) {
        val fg = r[i + 2]
        val end = (r[i] + r[i + 1]).coerceAtMost(line.text.length)
        if (fg in 0..15 && r[i] < end) addStyle(SpanStyle(color = ansiPalette[fg]), r[i], end)
        i += 3
    }
}

@Composable
private fun SelfTestScreen(
    engine: MudletEngineService?,
    onKillUi: () -> Unit,
    onStartEngine: () -> Unit,
    onStopEngine: (exitQt: Boolean) -> Unit,
) {
    val status by EngineRepository.status.collectAsStateWithLifecycle()
    val lines by EngineRepository.lines.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("aardmud.org") }
    var port by remember { mutableStateOf("23") }
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var outputWidthPx by remember { mutableStateOf(0) }
    LaunchedEffect(engine, outputWidthPx) { if (outputWidthPx > 0) engine?.setViewWidthPx(outputWidthPx) }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

    fun s(key: String) = status[key] ?: "-"
    val appStart = EngineRepository.appOnCreateElapsedMs
    val qtStartedAfter = status["qt.startedBootMs"]?.toLongOrNull()?.let { "${it - appStart} ms" } ?: "-"
    val mainAfter = status["qt.mainEnteredBootMs"]?.toLongOrNull()?.let { "${it - appStart} ms" } ?: "-"

    Column(
        Modifier.fillMaxSize().background(Color(0xFF101010)).safeDrawingPadding().imePadding().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val small = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFCCCCCC), fontSize = 11.sp)
        Text("Qt: main() +$mainAfter, QGuiApplication +$qtStartedAfter after Application.onCreate; " +
            "svc.onCreate +${s("svc.onCreateMs")} ms, super.onCreate took ${s("svc.superOnCreateMs")} ms; " +
            "${s("svc.qtStart")}; Qt ${s("qt.version")} qpa=${s("qpa.platform")} appState=${s("qt.appState")} dpr=${s("qt.dpr")}", style = small)
        Text("Font: ${s("font.family")} (asked ${s("font.requested")}), 'M'=${s("font.advanceM")} px @14px, " +
            "columns=${s("font.columns")} ${status["font.error"] ?: ""}", style = small)
        Text("Lines ${s("count.lines")}, Lua onLine ${s("count.luaOnLine")} (Lua lineCount ${s("count.luaLineCount")}), " +
            "notify ${s("count.notifications")}, tells posted ${s("ui.tellsPosted")}, dropped ${NativeBridge.dropped.get()}", style = small)
        Text("Heartbeat ticks ${s("heartbeat.ticks")}, last gap ${s("heartbeat.lastGapMs")} ms, " +
            "max gap ${s("heartbeat.maxGapMs")} ms", style = small)
        Text("Service ${s("svc.state")}, fgsType ${s("svc.fgsType")} (specialUse=0x40000000), bound ${s("ui.bound")}, " +
            "pids ui=${s("ui.pid")} svc=${s("svc.pid")} qt=${s("qt.pid")} (Qt tid ${s("qt.tid")}), " +
            "notifications ${s("ui.notifPermission")}", style = small)
        Text("Connection: ${s("connection")}  Lua: ${status["lua.error"]?.let { "error $it" } ?: s("lua.result")}",
            style = small)

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(host, { host = it }, Modifier.weight(2f), label = { Text("Host") }, singleLine = true)
            OutlinedTextField(port, { port = it }, Modifier.weight(1f), label = { Text("Port") }, singleLine = true)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { engine?.connectTo(host.trim(), port.toIntOrNull() ?: 23) }, enabled = engine != null) {
                Text("Connect")
            }
            OutlinedButton(onClick = { engine?.disconnectFromGame() }, enabled = engine != null) { Text("Disconnect") }
            OutlinedButton(onClick = onKillUi) { Text("Kill UI") }
            if (engine != null) {
                OutlinedButton(onClick = { onStopEngine(false) }) { Text("Stop engine") }
                OutlinedButton(onClick = { onStopEngine(true) }) { Text("Stop + exit Qt") }
            } else {
                OutlinedButton(onClick = onStartEngine) { Text("Start engine") }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)
                .onSizeChanged { size -> outputWidthPx = size.width },
        ) {
            items(lines, key = { it.id }) { line ->
                Text(
                    colourise(line),
                    color = if (line.isPrompt) Color(0xFFFFD27F) else Color(0xFFC0C0C0),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
            }
        }
        OutlinedTextField(
            command, { command = it }, Modifier.fillMaxWidth(),
            label = { Text("Command (prefix 'lua ' to run Lua)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                val e = engine
                if (e != null) {
                    if (command.startsWith("lua ")) e.runLua(command.removePrefix("lua ")) else e.send(command)
                }
                command = ""
            }),
        )
    }
}
