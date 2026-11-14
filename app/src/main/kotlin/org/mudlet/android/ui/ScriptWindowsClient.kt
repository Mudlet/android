package org.mudlet.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.MudletSize
import org.mudlet.android.protocol.Placement
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.ScriptWindows

/**
 * The script windows' part of [EngineClient] (docs/phase-f.md): the windows the engine describes, for the game screen
 * to draw, and the requests about them. Main thread only, as EngineClient's state; EngineClient hands it the engine's
 * messages about windows and tells it of each attach.
 */
object ScriptWindowsClient {
    var state by mutableStateOf(ScriptWindows())
        private set

    /** Fitted or stacked, or by the game area's width: the setting docs/phase-f.md's "Layout" offers the player. */
    var layoutMode by mutableStateOf(LayoutMode.AUTO)

    // The size the game screen wants Mudlet's main window to be, and the one this engine instance last got
    private var wantedSize: MudletSize? = null
    private var sentSize: MudletSize? = null

    // Where this engine instance was last told each user window is
    private val sentPlacements = HashMap<String, Placement>()

    internal fun onMessage(message: EngineMessage) {
        state = state.after(message)
    }

    /** A new engine instance knows none of the windows, nor the size or placements the last one was given. */
    internal fun onAttached(sameInstance: Boolean) {
        if (!sameInstance) {
            state = ScriptWindows()
            sentSize = null
            sentPlacements.clear()
        }
        // Also a size that could not be sent while the link was down
        wantedSize?.let(::setMainWindowSize)
    }

    /** Gives Mudlet's main window [size], unless this engine instance has it already; again to a new one. */
    fun setMainWindowSize(size: MudletSize) {
        wantedSize = size
        if (size == sentSize) return
        sentSize = if (EngineClient.send(Requests.setMainWindowSize(size.w, size.h))) size else null
    }

    /** Tells the engine where the user window [name] is, unless it was told already. */
    fun placeUserWindow(name: String, placement: Placement) {
        if (sentPlacements[name] == placement) return
        val sent = EngineClient.send(Requests.placeUserWindow(name, placement.x, placement.y, placement.w, placement.h, placement.floating))
        if (sent) sentPlacements[name] = placement else sentPlacements.remove(name)
    }

    /** The close button of a user window: the engine hides it. False as for [EngineClient.send]. */
    fun closeUserWindow(name: String): Boolean = EngineClient.send(Requests.closeUserWindow(name))

    /** Enter on the command line [name]. False as for [EngineClient.send]. */
    fun sendWindowCommandLine(name: String, text: String): Boolean = EngineClient.send(Requests.sendWindowCommandLine(name, text))

    fun commandLineShown(name: String) {
        state = state.commandLineShown(name)
    }
}
