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
import org.mudlet.android.suggest.PinnedCommands
import org.mudlet.android.suggest.SuggestFile
import org.mudlet.android.suggest.SuggesterRegistry
import org.mudlet.android.suggest.SuggestionSession
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ProfileSession
import java.io.File

/**
 * Each profile's command suggestions and pinned commands (`:suggest`, docs/architecture.md, "Command suggestions")
 * for the UI process's life: one [SuggestionSession] and one [PinnedCommands] per profile, loaded the first time the
 * game screen shows that profile, and kept in the app's private storage as `files/suggest/<profile>.txt` and
 * `files/suggest/<profile>.pins`. Main thread only.
 */
object CommandSuggestions {
    private const val TAG = "MudletSuggest"

    // Its one thread runs every suggestion's work, loading and saving included, off the main thread
    private val worker by lazy { ExecutorWorker("suggestions") { Log.e(TAG, "a suggestion task failed: ${it.javaClass.name}") } }
    private val registry = SuggesterRegistry()
    private val sessions = HashMap<String, SuggestionSession>()
    private val pins = HashMap<String, PinnedCommands>()

    /** The profile whose suggestions the input line shows: the front profile's [ProfileSession.suggestionsProfile]. */
    val profile: String get() = EngineClient.front.suggestionsProfile

    /** Where the profiles' suggestions and pinned commands are saved. */
    fun dir(context: Context) = File(context.filesDir, "suggest")

    fun forProfile(context: Context, profile: String): SuggestionSession = sessions.getOrPut(profile) {
        SuggestionSession(registry.forProfile(profile), SuggestFile.forProfile(dir(context), profile), worker, ::log).apply { open() }
    }

    fun pinsForProfile(context: Context, profile: String): PinnedCommands = pins.getOrPut(profile) {
        PinnedCommands(SuggestFile.pinsForProfile(dir(context), profile), worker, ::log).apply { open() }
    }

    /** Gives [state] the lines of [profile]'s console that came since the last call, leaving out the app's markers. */
    internal fun linesShown(profile: ProfileSession, state: SuggestionState) {
        val added = profile.linesAdded
        val first = added - profile.lines.size
        val from = maxOf(profile.linesSuggested, first)
        val shown = buildList((added - from).toInt()) {
            for (n in from until added) {
                val line = profile.lines[(n - first).toInt()]
                if (!line.marker) add(line.text)
            }
        }
        profile.linesSuggested = added
        state.linesShown(shown)
    }

    /** Saves what each profile learned since its last save, as the app leaves the front and may be killed. */
    fun saveAll() = sessions.values.forEach(SuggestionSession::save)

    // Never a command or a line: SuggestionSession gives none
    private fun log(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
    }
}

/**
 * The input line's suggestions and pinned commands for one profile, for the game screen: what was last answered, the
 * pins, and what to tell them.
 */
@Stable
class SuggestionState internal constructor(internal val session: SuggestionSession, internal val pins: PinnedCommands) {
    /** The suggestions for the input line as it was a moment ago; it shows their ghost text only if it still is so. */
    var answer by mutableStateOf<SuggestionSession.Answer?>(null)
        internal set

    /** The answer to the latest [search], or null before the first. */
    var searchAnswer by mutableStateOf<SuggestionSession.SearchAnswer?>(null)
        internal set

    /** The pinned commands, in the order they were pinned: empty until the profile's file has loaded. */
    var pinned by mutableStateOf(emptyList<String>())
        internal set

    // While the game hides input nothing is pinned, though nothing offers a pin then either
    private var hidden = false

    fun inputChanged(text: String, cursor: Int) = session.inputChanged(text, cursor)

    fun hiddenChanged(hidden: Boolean) {
        this.hidden = hidden
        session.hiddenChanged(hidden)
    }

    fun search(query: String) = session.search(query)

    fun forget(command: String) = session.forget(command)

    /** How many commands can be pinned. */
    val maxPins get() = pins.max

    /** Whether one more command can be pinned. */
    val canPin get() = pinned.size < maxPins

    fun pin(command: String) {
        if (!hidden) pins.pin(command)
    }

    fun unpin(command: String) = pins.unpin(command)

    fun sent(command: String) = session.sent(command)

    fun sentHidden() = session.sentHidden()

    fun linesShown(lines: List<String>) = session.linesShown(lines)
}

/** [profile]'s suggestions while the caller is composed; leaving saves what was learned. */
@Composable
fun rememberSuggestionState(profile: String): SuggestionState {
    val context = LocalContext.current.applicationContext
    val state = remember(profile) { SuggestionState(CommandSuggestions.forProfile(context, profile), CommandSuggestions.pinsForProfile(context, profile)) }
    DisposableEffect(state) {
        val main = Handler(Looper.getMainLooper())
        var active = true
        val listener: (SuggestionSession.Answer) -> Unit = { answer -> main.post { if (active) state.answer = answer } }
        val searchListener: (SuggestionSession.SearchAnswer) -> Unit = { answer -> main.post { if (active) state.searchAnswer = answer } }
        val pinsListener: (List<String>) -> Unit = { pinned -> main.post { if (active) state.pinned = pinned } }
        state.session.listener = listener
        state.session.searchListener = searchListener
        state.pins.listener = pinsListener
        onDispose {
            active = false
            if (state.session.listener === listener) state.session.listener = null
            if (state.session.searchListener === searchListener) state.session.searchListener = null
            if (state.pins.listener === pinsListener) state.pins.listener = null
            state.session.save()
        }
    }
    return state
}

/**
 * The suggestions for the front profile, kept up to date with its [ProfileSession]: whether input is hidden, and the
 * game's lines as they come. The caller tells it what the input line sends.
 */
@Composable
fun rememberEngineSuggestions(): SuggestionState {
    val profile = EngineClient.front
    val state = rememberSuggestionState(profile.suggestionsProfile)
    LaunchedEffect(profile, state) { snapshotFlow { profile.hiddenInput }.collect(state::hiddenChanged) }
    LaunchedEffect(profile, state) { snapshotFlow { profile.linesAdded }.collect { CommandSuggestions.linesShown(profile, state) } }
    return state
}
