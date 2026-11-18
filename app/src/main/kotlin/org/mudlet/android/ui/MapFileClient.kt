package org.mudlet.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mudlet.android.R
import org.mudlet.android.data.MapFileStaging
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.MapFileOperation
import org.mudlet.android.protocol.MapFileState
import org.mudlet.android.protocol.Requests
import org.mudlet.android.ui.game.Announcer
import java.io.File
import java.io.IOException

/**
 * Loading the map from a file the player picks, and saving it to one or sharing it (docs/architecture.md, "Map
 * files"), through Android's file picker, so the app needs no storage permission. The engine reads and writes only a
 * whole path, so the file passes through the app's cache ([MapFileStaging]). Each [AppMapClient] has one, in the
 * profile's session rather than a composition, as a rotation or Back can come while a large map loads. Main thread
 * only.
 */
internal class MapFileClient(private val app: Context, private val send: (JSONObject) -> Boolean) {
    /** What the player asked for. */
    enum class Job(val operation: MapFileOperation) { LOAD(MapFileOperation.LOAD), SAVE(MapFileOperation.SAVE), SHARE(MapFileOperation.SAVE) }

    sealed interface State {
        val job: Job

        /** The picked file being copied in, the engine not yet answering, or the saved map being copied out. */
        data class Preparing(override val job: Job) : State

        /** The engine's progress, as its last `mapFile` said. */
        data class Running(override val job: Job, val frame: EngineMessage.MapFile) : State

        /** How it ended, until dismissed or the next: [why] a failure, empty otherwise. */
        data class Ended(override val job: Job, val outcome: MapFileState, val why: String) : State
    }

    /** Null until the player first asks for one. */
    var state by mutableStateOf<State?>(null)
        private set

    val busy get() = state.let { it != null && it !is State.Ended }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // What the engine reads or writes, in the cache, and where a save goes once written
    private var staged: File? = null
    private var target: Uri? = null

    /** Copies [uri], a file the player picked, into the cache and has the engine load it in place of the profile's map. */
    fun load(uri: Uri) {
        if (busy) return
        state = State.Preparing(Job.LOAD)
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { stageIn(uri) }
            } catch (e: IOException) {
                end(Job.LOAD, MapFileState.FAILED, why(e))
                return@launch
            } catch (e: SecurityException) {
                end(Job.LOAD, MapFileState.FAILED, why(e))
                return@launch
            }
            staged = file
            ask(Job.LOAD, Requests.loadMapFile(file.path))
        }
    }

    /** Has the engine save the map in [format] to the cache, then copies it to [uri], a file the player chose. */
    fun save(uri: Uri, format: MapFileStaging.Format) {
        if (busy) return
        target = uri
        saveToCache(Job.SAVE, format)
    }

    /** Has the engine save the map to the cache, then offers it to other apps through Android's share sheet. */
    fun share() {
        if (busy) return
        saveToCache(Job.SHARE, MapFileStaging.Format.DAT)
    }

    /** Stops the load or save while the engine says it can. False as for [ProfileSession.send]. */
    fun cancel(): Boolean = send(Requests.cancelMapFile())

    /** The player read how it ended. */
    fun dismiss() {
        if (state is State.Ended) state = null
    }

    /** A `mapFile`: how the load or save the engine runs goes, and its end. */
    internal fun onFrame(frame: EngineMessage.MapFile, resources: Resources) {
        val job = state?.job?.takeIf { busy && it.operation == frame.operation } ?: return
        if (frame.state == MapFileState.RUNNING) {
            state = State.Running(job, frame)
            return
        }
        val file = staged
        staged = null
        when {
            frame.state != MapFileState.DONE || file == null -> {
                file?.delete()
                end(job, frame.state, frame.error, resources)
            }

            job == Job.LOAD -> {
                file.delete()
                end(job, MapFileState.DONE, "", resources)
            }

            job == Job.SHARE -> {
                val problem = offer(file)
                end(job, if (problem == null) MapFileState.DONE else MapFileState.FAILED, problem.orEmpty(), resources)
            }

            else -> copyOut(file, target)
        }
    }

    /** The engine refused [request] for [reason]; true where it was this client's. */
    internal fun onRefused(request: String, reason: String): Boolean {
        if (request == "cancelMapFile") return true
        val job = (state as? State.Preparing)?.job ?: return false
        if (request != if (job == Job.LOAD) "loadMapFile" else "saveMapFile") return false
        staged?.delete()
        staged = null
        end(job, MapFileState.FAILED, reason)
        return true
    }

    /** A UI attached again; a restarted engine ran none of what this one asked, and will say nothing of it. */
    internal fun onAttached(sameInstance: Boolean) {
        val job = state?.job?.takeIf { busy && !sameInstance && staged != null } ?: return
        staged?.delete()
        staged = null
        end(job, MapFileState.FAILED, app.resources.getString(R.string.map_file_engine_restarted))
    }

    private fun saveToCache(job: Job, format: MapFileStaging.Format) {
        state = State.Preparing(job)
        val folder = MapFileStaging.folder(app.cacheDir)
        folder.mkdirs()
        folder.listFiles()?.forEach { it.delete() }
        val file = MapFileStaging.file(folder, format)
        staged = file
        ask(job, Requests.saveMapFile(file.path))
    }

    private fun ask(job: Job, request: JSONObject) {
        if (!send(request)) {
            staged?.delete()
            staged = null
            end(job, MapFileState.FAILED, app.resources.getString(R.string.map_file_engine_restarted))
        }
    }

    private fun stageIn(uri: Uri): File {
        val resolver = app.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val input = resolver.openInputStream(uri) ?: throw IOException("the file could not be opened")
        return input.use { MapFileStaging.stageIn(it, name, MapFileStaging.folder(app.cacheDir)) }
    }

    private fun copyOut(file: File, uri: Uri?) {
        state = State.Preparing(Job.SAVE)
        scope.launch {
            val problem = withContext(Dispatchers.IO) {
                try {
                    val out = uri?.let { app.contentResolver.openOutputStream(it) } ?: throw IOException("the file could not be opened")
                    out.use { o -> file.inputStream().use { it.copyTo(o) } }
                    null
                } catch (e: IOException) {
                    uri?.let { deleteHalfWritten(it) }
                    why(e)
                } catch (e: SecurityException) {
                    uri?.let { deleteHalfWritten(it) }
                    why(e)
                } finally {
                    file.delete()
                }
            }
            target = null
            end(Job.SAVE, if (problem == null) MapFileState.DONE else MapFileState.FAILED, problem.orEmpty())
        }
    }

    private fun deleteHalfWritten(uri: Uri) {
        if (!DocumentsContract.isDocumentUri(app, uri)) return
        try {
            DocumentsContract.deleteDocument(app.contentResolver, uri)
        } catch (e: Exception) {
            // The picker's provider may not delete; the player sees the save failed and can remove the file. Only the
            // exception's class: its message can name the file the player picked, and logcat goes into problem reports
            Log.w(TAG, "could not delete the half-written map file: ${e.javaClass.simpleName}")
        }
    }

    /** Offers [file] through Android's share sheet; why it could not, null once offered. */
    private fun offer(file: File): String? = try {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.mapfiles", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(MapFileStaging.Format.DAT.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        app.startActivity(Intent.createChooser(send, app.getString(R.string.map_file_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) {
        file.delete()
        why(e)
    } catch (e: IllegalArgumentException) {
        file.delete()
        why(e)
    }

    private fun why(e: Exception): String = if (e is MapFileStaging.TooLarge) {
        app.resources.getString(R.string.map_file_too_large, MapFileStaging.MAX_BYTES / (1024 * 1024))
    } else {
        e.message ?: e.toString()
    }

    private fun end(job: Job, outcome: MapFileState, why: String, resources: Resources = app.resources) {
        val ended = State.Ended(job, outcome, why)
        state = ended
        Announcer.announce(endText(ended, resources), assertive = outcome == MapFileState.FAILED)
    }

    companion object {
        /** How [ended] ended, in [resources]' words, as the bar shows it and TalkBack reads it. */
        fun endText(ended: State.Ended, resources: Resources): String = when (ended.outcome) {
            MapFileState.DONE -> resources.getString(
                when (ended.job) {
                    Job.LOAD -> R.string.map_file_loaded
                    Job.SAVE -> R.string.map_file_saved
                    Job.SHARE -> R.string.map_file_shared
                },
            )

            MapFileState.CANCELLED -> resources.getString(if (ended.job == Job.LOAD) R.string.map_file_load_cancelled else R.string.map_file_save_cancelled)

            MapFileState.FAILED, MapFileState.RUNNING ->
                resources.getString(if (ended.job == Job.LOAD) R.string.map_file_load_failed else R.string.map_file_save_failed, ended.why)
        }

        private const val TAG = "MapFiles"
    }
}
