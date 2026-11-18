package org.mudlet.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.InstalledPackage
import org.mudlet.android.protocol.PackageInstallResult
import org.mudlet.android.protocol.Requests

/**
 * The profile's packages as the package manager shows them (docs/protocol.md, `packages`): the list as the engine last
 * gave it, the installs it has not answered, and what to tell the player. Requests go with [send], each answer's `id`
 * from [newId]; [finished] is told of each install's file once the engine is done with it. What it holds is Compose
 * state, changed only here. Main thread only.
 */
internal class PackagesClient(
    private val newId: () -> Long,
    private val send: (JSONObject) -> Boolean,
    private val finished: (String) -> Unit = {},
) {
    private var listed by mutableStateOf<List<InstalledPackage>?>(null)
    private val sent = mutableStateListOf<String>()
    private val queued = mutableStateListOf<PackageNotice>()
    private val pending = HashSet<Long>()

    /** The packages in the order installed, as the engine last listed them; null until it first does. */
    val packages: List<InstalledPackage>? get() = listed

    /** The files sent to be installed whose answer has not come. */
    val installing: List<String> get() = sent

    /** What the screen is to tell the player, the oldest first; each is taken off once [shown]. */
    val notices: List<PackageNotice> get() = queued

    /** How many times the engine has been attached to, for the screen to ask again. */
    var attaches by mutableIntStateOf(0)
        private set

    /** Asks for the list. False as for [ProfileSession.send]. */
    fun ask(): Boolean = request { Requests.packages(it) }

    /** Installs the package file at [path], which the engine must be able to read. */
    fun install(path: String): Boolean {
        if (!send(Requests.installPackage(path))) {
            tell(PackageNotice.NotSent)
            finished(path)
            return false
        }
        sent.add(path)
        return true
    }

    /**
     * Installs the package at [url] as desktop does one dropped on it: Mudlet's own Lua installPackage() downloads it and
     * tells the console how it went, and packagesChanged follows if it installed.
     */
    fun installFrom(url: String): Boolean {
        if (!send(Requests.runLua("installPackage(${luaString(url)})", newId()))) {
            tell(PackageNotice.NotSent)
            return false
        }
        return true
    }

    /** Removes the package [name]; the answer is the list without it. */
    fun uninstall(name: String): Boolean = request { Requests.uninstallPackage(name, it) }

    /** Takes [notice] off [notices], once the screen has shown it. */
    fun shown(notice: PackageNotice) {
        queued.remove(notice)
    }

    private fun request(request: (Long) -> JSONObject): Boolean {
        val id = newId()
        if (!send(request(id))) {
            tell(PackageNotice.NotSent)
            return false
        }
        pending.add(id)
        return true
    }

    // The screen shows the first notice until it is taken off, so one that equals the one before it would never show
    private fun tell(notice: PackageNotice) {
        if (queued.lastOrNull() != notice) queued.add(notice)
    }

    /** The engine's answer to [ask] or [uninstall]: false when this did not ask it. */
    fun onPackages(message: EngineMessage.Packages): Boolean {
        if (!pending.remove((message.replyTo as? Number)?.toLong())) return false
        listed = message.packages
        return true
    }

    /** A script, an install or the profile's default packages changed them: asked again once the list has been shown. */
    fun onChanged() {
        if (listed != null) ask()
    }

    /** The answer to an [install]. */
    fun onInstalled(message: EngineMessage.PackageInstalled) {
        if (!sent.remove(message.path)) return
        finished(message.path)
        tell(PackageNotice.Installed(message.path.substringAfterLast('/'), message.result, message.error))
    }

    /** An `error` for [request], one of [REQUESTS], answering [replyTo]: [notices] has the engine's [reason]. */
    fun onRefused(request: String, reason: String, replyTo: Any?) {
        pending.remove((replyTo as? Number)?.toLong())
        // A refused install carries no id: it is the oldest still waiting, as the engine answers in order
        if (request == "installPackage") sent.removeFirstOrNull()?.let(finished)
        tell(PackageNotice.Refused(request, reason))
    }

    /** What the last link did not answer will not be answered; a new engine instance loaded the profile again. */
    fun onAttached(sameInstance: Boolean) {
        pending.clear()
        sent.toList().forEach(finished)
        sent.clear()
        if (!sameInstance) listed = null
        attaches++
    }

    companion object {
        /** The requests sent from here, whose refusal goes to [onRefused]. */
        val REQUESTS = setOf("packages", "installPackage", "uninstallPackage")

        /** [text] as a Lua long string, its level past any closing bracket it holds. */
        fun luaString(text: String): String {
            var level = ""
            while ("]$level]" in text) level += "="
            return "[$level[$text]$level]"
        }
    }
}

/** What the package manager has to tell the player, through a snackbar. */
internal sealed interface PackageNotice {
    /** The answer to installing the file [file]: [error] says why, for all but [PackageInstallResult.INSTALLED]. */
    data class Installed(val file: String, val result: PackageInstallResult, val error: String) : PackageNotice

    /** The engine refused [request] for [reason], in English. */
    data class Refused(val request: String, val reason: String) : PackageNotice

    /** A request could not be sent, as there was no engine to send it to. */
    data object NotSent : PackageNotice
}
