package org.mudlet.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.mudlet.android.protocol.Requests
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.UiLine

/**
 * DEBUG BUILDS ONLY (src/debug): the hook that CI's emulator smoke test, native/tests/android_smoke.py, drives the
 * UI's EngineClient through, as a user's taps would, and reads back what the UI holds, as the screen would show it.
 *
 *     adb shell am broadcast -n org.mudlet.mudlet/org.mudlet.android.debug.SmokeTestReceiver --es cmd <cmd> ...
 *
 * cmd: state; start (the engine service); connect (--es host, --ei port; --ez tls, false if absent); lua (--es code);
 * send (--es text); kill (the :engine process, as the low-memory killer would). Every command answers with the UI's
 * state as base64 JSON in the broadcast's result data: result code 0 if the command was carried out, 1 if not, with
 * "error" saying why.
 */
class SmokeTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EngineClient.init(context)
        val c = EngineClient
        val error: String? = when (val cmd = intent.getStringExtra("cmd")) {
            "state" -> null

            "start" -> {
                c.startEngine()
                null
            }

            "connect" -> {
                val host = intent.getStringExtra("host")
                val port = intent.getIntExtra("port", -1)
                val tls = intent.getBooleanExtra("tls", false)
                if (host == null || port <= 0) "connect needs --es host and --ei port" else sendOrError(Requests.connect(host, port, tls))
            }

            "lua" -> {
                val code = intent.getStringExtra("code")
                if (code == null) "lua needs --es code" else sendOrError(Requests.runLua(code, System.nanoTime()))
            }

            "send" -> {
                val text = intent.getStringExtra("text")
                if (text == null) "send needs --es text" else sendOrError(Requests.send(text))
            }

            "kill" -> if (c.enginePid > 0) {
                c.killEngineProcess()
                null
            } else {
                "no engine attached to kill"
            }

            else -> "unknown cmd: $cmd"
        }
        c.refreshServiceState()
        val state = state().apply { if (error != null) put("error", error) }
        setResult(if (error == null) 0 else 1, Base64.encodeToString(state.toString().toByteArray(), Base64.NO_WRAP), null)
    }

    private fun sendOrError(message: JSONObject): String? = if (EngineClient.send(message)) null else "the UI is not attached to the engine: ${EngineClient.linkState}"

    private fun state(): JSONObject {
        val c = EngineClient
        val lines = JSONArray()
        c.lines.takeLast(LINES).forEach { lines.put(it.toJson()) }
        return JSONObject()
            .put("link", c.linkState)
            .put("uiPid", Process.myPid())
            .put("enginePid", c.enginePid)
            .put("attaches", c.attachCount)
            .put("engineLosses", c.engineLosses)
            .put("fgs", c.fgsState)
            .put("notifyCount", c.notifyCount)
            .put("status", JSONObject(c.status.toMap()))
            .put("lines", lines)
            .put("tail", c.tail?.toJson() ?: JSONObject.NULL)
    }

    private fun UiLine.toJson() = JSONObject()
        .put("text", text)
        .put("runs", JSONArray(runs.toList()))
        .put("prompt", prompt)
        .put("marker", marker)

    private companion object {
        // Enough for the banner and the test's own lines; a broadcast's result travels in one binder transaction
        const val LINES = 60
    }
}
