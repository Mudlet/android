package org.mudlet.proto.b

import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun ProtoScreen(activity: MainActivity) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color(0xFF101010))) {
            // Qt's view sits underneath everything at one pixel; it exists only to start Qt.
            val holder = remember { activity.qtHolder() }
            if (holder != null) {
                val onePx = with(LocalDensity.current) { 1.toDp() }
                AndroidView(factory = { holder }, modifier = Modifier.size(onePx))
            }
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp)) {
                StatusPanel(activity)
                Output(Modifier.weight(1f).fillMaxWidth())
                CommandRow()
            }
        }
    }
}

@Composable
private fun Label(text: String, bold: Boolean = false) {
    Text(text, fontSize = 12.sp, color = Color(0xFFDDDDDD), fontWeight = if (bold) FontWeight.Bold else null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusPanel(activity: MainActivity) {
    val s = EngineBridge.status
    var expanded by rememberSaveable { mutableStateOf(true) }
    var host by rememberSaveable { mutableStateOf("aardmud.org") }
    var port by rememberSaveable { mutableStateOf("23") }
    var wakeLock by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Mode ${EngineBridge.hostMode} | Qt: ${EngineBridge.qtState}", bold = true)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightLimit().verticalScroll(rememberScrollState())) {
                Label("Qt started: ${EngineBridge.qtStartedAfterMs?.let { "$it ms after Application.onCreate" } ?: "-"} (QML ${s["qml"] ?: "-"})")
                Label("Font: ${s["font.family"] ?: "-"}, 'M' advance ${s["font.advanceM"] ?: "-"}px @14px, columns ${s["columns"] ?: "-"}")
                Label("Counters: ${s["counters"] ?: "-"}, notifications posted ${EngineBridge.notificationsPosted}, dropped ${EngineBridge.notificationsDropped}")
                Label("Heartbeat (30 s): ${s["heartbeat"] ?: "-"}")
                Label("Service: ${EngineBridge.serviceState}, wake lock ${s["wakelock"] ?: "off"}")
                Label("UI pid ${Process.myPid()} main tid ${Process.myTid()} | Qt ${s["qt.thread"] ?: "-"}")
                Label("Connection: ${s["conn"] ?: "-"}")
                s["lua.result"]?.let { Label("Lua result: $it") }
                s["lua.error"]?.let { Label("Lua error: $it") }
                s["lua.print"]?.let { Label("Lua print: $it") }
                Label("Events:", bold = true)
                for (e in EngineBridge.events.takeLast(12)) Label(e)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(host, { host = it }, Modifier.weight(1f), singleLine = true, label = { Text("Host") })
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, Modifier.width(90.dp), singleLine = true,
                label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button({ EngineBridge.connect(host.trim(), port.toIntOrNull() ?: 23) }) { Text("Connect") }
            OutlinedButton({ EngineBridge.disconnect() }) { Text("Disconnect") }
            OutlinedButton({ expanded = !expanded }) { Text(if (expanded) "Hide status" else "Status") }
            OutlinedButton({ activity.finish() }) { Text("Kill UI") }
            OutlinedButton({ EngineService.stop(activity) }) { Text("Stop engine") }
            OutlinedButton({ activity.restartEngine() }) { Text("Start engine") }
            OutlinedButton({ activity.recreate() }) { Text("Recreate Activity") }
            OutlinedButton({ activity.switchModeAndExit() }) {
                Text("Use ${if (EngineBridge.hostMode == HostMode.ACTIVITY) "SERVICE" else "ACTIVITY"} mode + exit")
            }
            OutlinedButton({ activity.exitProcess() }) { Text("Exit process") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(wakeLock, { wakeLock = it; EngineService.wakeLock(activity, it) })
                Label(" wake lock")
            }
        }
    }
}

private fun Modifier.heightLimit() = this.padding(bottom = 4.dp).heightIn(max = 220.dp)

@Composable
private fun Output(modifier: Modifier) {
    val state = rememberLazyListState()
    val lines = EngineBridge.lines
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1)
    }
    LazyColumn(
        state = state,
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { EngineBridge.setViewWidthPx(it.width) },
    ) {
        items(lines, key = { it.id }) { line ->
            Text(
                line.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = if (line.prompt) Color(0xFF88CCFF) else Ansi.defaultFg,
            )
        }
    }
}

@Composable
private fun CommandRow() {
    var command by remember { mutableStateOf("") }
    val submit = {
        val c = command
        if (c.startsWith("/lua ")) EngineBridge.runLua(c.removePrefix("/lua ")) else EngineBridge.send(c)
        command = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            command, { command = it }, Modifier.weight(1f), singleLine = true,
            label = { Text("Command (/lua code runs Lua)") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        Button({ submit() }) { Text("Send") }
    }
}
