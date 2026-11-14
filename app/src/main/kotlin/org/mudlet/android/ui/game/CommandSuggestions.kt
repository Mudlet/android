package org.mudlet.android.ui.game

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import org.mudlet.android.suggest.ExecutorWorker
import org.mudlet.android.suggest.SuggestFile
import org.mudlet.android.suggest.SuggesterRegistry
import org.mudlet.android.suggest.SuggestionSession
import org.mudlet.android.ui.EngineClient
import java.io.File

/**
 * Each profile's command suggestions (`:suggest`, docs/architecture.md, "Command suggestions") for the UI process's
 * life: one [SuggestionSession] per profile, loaded the first time the game screen shows that profile, and kept in
 * the app's private storage as `files/suggest/<profile>.txt`. Main thread only.
 */
object CommandSuggestions {
    private const val TAG = "MudletSuggest"
    private const val LOADED = " loaded"

    /** The profile libmudlet starts when none was opened (`MUDLET_PROFILE`), until the engine names one. */
    const val DEFAULT_PROFILE = "Android"

    // Its one thread runs every suggestion's work, loading and saving included, off the main thread
    private val worker by lazy { ExecutorWorker("suggestions") { Log.e(TAG, "a suggestion task failed: ${it.javaClass.name}") } }
    private val registry = SuggesterRegistry()
    private val sessions = HashMap<String, SuggestionSession>()

    /** The profile whose suggestions the input line shows: the one the engine last said it loaded. */
    var profile by mutableStateOf(DEFAULT_PROFILE)
        private set

    /** Takes the profile from the engine's status `profile` ("<name> loaded"); its other values change nothing. */
    fun profileStatus(value: String?) {
        if (value != null && value.endsWith(LOADED) && value.length > LOADED.length) profile = value.removeSuffix(LOADED)
    }

    // The number (EngineClient.linesAdded) after the last console line given to a session, so none is given twice
    private var linesSeen = 0L

    /** Where the profiles' suggestions are saved. */
    fun dir(context: Context) = File(context.filesDir, "suggest")

    fun forProfile(context: Context, profile: String): SuggestionSession = sessions.getOrPut(profile) {
        SuggestionSession(registry.forProfile(profile), SuggestFile.forProfile(dir(context), profile), worker, ::log).apply { open() }
    }

    /** Gives [state] the game's lines that came since the last call, leaving out the app's markers. */
    internal fun linesShown(state: SuggestionState) {
        val c = EngineClient
        val added = c.linesAdded
        val first = added - c.lines.size
        for (n in maxOf(linesSeen, first) until added) {
            val line = c.lines[(n - first).toInt()]
            if (!line.marker) state.lineShown(line.text)
        }
        linesSeen = added
    }

    /** Saves what each profile learned since its last save, as the app leaves the front and may be killed. */
    fun saveAll() = sessions.values.forEach(SuggestionSession::save)

    // Never a command or a line: SuggestionSession gives none
    private fun log(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
    }
}

/** The input line's suggestions for one profile, for the game screen: what was last answered, and what to tell it. */
@Stable
class SuggestionState internal constructor(internal val session: SuggestionSession) {
    /** The suggestions for the input line as it was a moment ago; it shows their ghost text only if it still is so. */
    var answer by mutableStateOf<SuggestionSession.Answer?>(null)
        internal set

    fun inputChanged(text: String, cursor: Int) = session.inputChanged(text, cursor)

    fun hiddenChanged(hidden: Boolean) = session.hiddenChanged(hidden)

    fun sent(command: String) = session.sent(command)

    fun sentHidden() = session.sentHidden()

    fun lineShown(line: String) = session.lineShown(line)
}

/** [profile]'s suggestions while the caller is composed; leaving saves what was learned. */
@Composable
fun rememberSuggestionState(profile: String): SuggestionState {
    val context = LocalContext.current.applicationContext
    val state = remember(profile) { SuggestionState(CommandSuggestions.forProfile(context, profile)) }
    DisposableEffect(state) {
        val main = Handler(Looper.getMainLooper())
        var active = true
        val listener: (SuggestionSession.Answer) -> Unit = { answer -> main.post { if (active) state.answer = answer } }
        state.session.listener = listener
        onDispose {
            active = false
            if (state.session.listener === listener) state.session.listener = null
            state.session.save()
        }
    }
    return state
}

/**
 * The suggestions for the profile the engine runs, kept up to date with [EngineClient]: whether input is hidden, and
 * the game's lines as they come. The caller tells it what the input line sends.
 */
@Composable
fun rememberEngineSuggestions(): SuggestionState {
    val c = EngineClient
    val profileStatus = c.status["profile"]
    LaunchedEffect(profileStatus) { CommandSuggestions.profileStatus(profileStatus) }
    val state = rememberSuggestionState(CommandSuggestions.profile)
    LaunchedEffect(state) { snapshotFlow { c.hiddenInput }.collect(state::hiddenChanged) }
    LaunchedEffect(state) { snapshotFlow { c.linesAdded }.collect { CommandSuggestions.linesShown(state) } }
    return state
}
