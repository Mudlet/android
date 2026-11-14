package org.mudlet.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.Requests
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ScriptWindowsClient
import org.mudlet.android.ui.UiLine

/**
 * DEBUG BUILDS ONLY (src/debug): the hook that CI's emulator smoke test, native/tests/android_smoke.py, drives the
 * UI's EngineClient through, as a user's taps would, and reads back what the UI holds, as the screen would show it.
 *
 *     adb shell am broadcast -n org.mudlet.mudlet/org.mudlet.android.debug.SmokeTestReceiver --es cmd <cmd> ...
 *
 * cmd: state; start (the engine service); connect (--es host, --ei port; --ez tls, false if absent; --es profile,
 * "Android" if absent; --es login; --es password), which saves the profile, stores the password as the profile editor
 * does and opens the profile with the password read back, as the profile list's Connect does; lua (--es code); send
 * (--es text); kill (the :engine process, as the low-memory killer would). Every command answers with the UI's
 * state as base64 JSON in the broadcast's result data: result code 0 if the command was carried out, 1 if not, with
 * "error" saying why. A connect's profile requests are answered later: "profileAnswer" in the state is "waiting", "ok"
 * or the engine's reason for refusing one.
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
                if (host == null || port <= 0) "connect needs --es host and --ei port" else connect(context, intent, host, port, tls)
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

    private fun connect(context: Context, intent: Intent, host: String, port: Int, tls: Boolean): String? {
        val profile = Profile(intent.getStringExtra("profile") ?: "Android", host, port, tls, intent.getStringExtra("login") ?: "", false)
        val passwords = PasswordStore(context)
        val password = intent.getStringExtra("password")
        if (password != null && !passwords.set(profile.name, password)) return "the password could not be stored"
        profileAnswer = "waiting"
        EngineClient.saveAndOpenProfile(profile, passwords.get(profile.name)) { profileAnswer = it ?: "ok" }
        return if (profileAnswer == "waiting") null else profileAnswer
    }

    private fun sendOrError(message: JSONObject): String? = notSentError(EngineClient.send(message))

    private fun notSentError(sent: Boolean): String? = if (sent) null else "the UI is not attached to the engine: ${EngineClient.linkState}"

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
            .put("profiles", JSONArray(c.profiles.orEmpty().map { it.toJson() }))
            .put("profileAnswer", profileAnswer)
            .put("windows", windows())
    }

    // The script windows the UI knows of, each with the text of its last rows if it is a console
    private fun windows(): JSONArray {
        val state = ScriptWindowsClient.state
        val windows = JSONArray()
        for (w in state.windows) {
            val rows = state.text[w.name]?.lines.orEmpty().takeLast(WINDOW_ROWS).map { it.text }
            windows.put(JSONObject().put("name", w.name).put("kind", w.kind.wire).put("shown", w.shown).put("rows", JSONArray(rows)))
        }
        return windows
    }

    private fun UiLine.toJson() = JSONObject()
        .put("text", text)
        .put("runs", JSONArray(runs.toList()))
        .put("prompt", prompt)
        .put("marker", marker)

    private fun Profile.toJson() = JSONObject()
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("tls", tls)
        .put("login", login)
        .put("autoReconnect", autoReconnect)

    private companion object {
        // The answer to the last connect's profile requests, which come after its broadcast was answered
        var profileAnswer = ""

        // Enough for the banner and the test's own lines; a broadcast's result travels in one binder transaction
        const val LINES = 60
        const val WINDOW_ROWS = 5
    }
}
