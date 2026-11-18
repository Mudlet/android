package org.mudlet.android.ui.game

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mudlet.android.R
import org.mudlet.android.ui.EngineClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * A game script's invokeFileDialog(): the app asks the player, with the script's title, whether to choose a file, as
 * the system's picker shows no title of its own; the file chosen there is copied into the folder the script's dialog
 * would have opened at, and its path is the script's answer. Cancel, Back or the picker's own back are a cancel.
 */
@Composable
fun ScriptFileRequest() {
    val context = LocalContext.current.applicationContext
    // Registered before any early return, so a result comes back to an Activity recreated meanwhile
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        ScriptFilePicks.chosen(context, uri)
    }
    val pick = EngineClient.filePick ?: return
    if (ScriptFilePicks.choosing == pick.id) return
    val cancel = { EngineClient.answerFilePick("") }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(R.string.script_file_title)) },
        text = {
            val why = stringResource(R.string.script_file_text)
            Text(if (pick.title.isBlank()) why else "${pick.title}\n\n$why")
        },
        confirmButton = {
            TextButton(onClick = {
                ScriptFilePicks.choosing = pick.id
                try {
                    launcher.launch(arrayOf("*/*"))
                } catch (_: ActivityNotFoundException) {
                    // A device without the system's file picker, as some TV and car builds are
                    ScriptFilePicks.choosing = null
                    Toast.makeText(context, R.string.script_file_no_picker, Toast.LENGTH_LONG).show()
                    cancel()
                }
            }) { Text(stringResource(R.string.script_file_choose)) }
        },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.script_file_cancel)) } },
    )
}

/**
 * The pick whose system picker is open or whose file is being copied, in the app's process rather than the dialog's
 * composition: the Activity stops behind the picker and may be recreated before the copy ends.
 */
internal object ScriptFilePicks {
    private const val TAG = "ScriptFilePicks"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The id of [EngineClient.filePick] while its system picker is open or its file is copied; null otherwise. */
    var choosing by mutableStateOf<Long?>(null)

    fun chosen(context: Context, uri: Uri?) {
        val pick = EngineClient.filePick
        val id = choosing
        // A pick that ended meanwhile, as with a new engine, which asks again for its own
        if (pick == null || pick.id != id) {
            choosing = null
            return
        }
        if (uri == null) {
            choosing = null
            EngineClient.answerFilePick("")
            return
        }
        scope.launch {
            val copied = withContext(Dispatchers.IO) { copy(context, uri, File(pick.into)) }
            choosing = null
            if (EngineClient.filePick?.id != pick.id) return@launch
            if (copied == null) Toast.makeText(context, R.string.script_file_not_copied, Toast.LENGTH_LONG).show()
            EngineClient.answerFilePick(copied?.path.orEmpty())
        }
    }

    // Only the exception's class is logged: the file's name and content are the player's
    private fun copy(context: Context, uri: Uri, into: File): File? = try {
        if (!ScriptFiles.inside(into, context.filesDir)) throw IOException("not the app's folder")
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        val input = resolver.openInputStream(uri) ?: throw IOException("no stream")
        input.use { ScriptFiles.copyInto(into, name, it) }
    } catch (e: IOException) {
        notCopied(e)
    } catch (e: SecurityException) {
        notCopied(e)
    } catch (e: IllegalArgumentException) {
        notCopied(e)
    }

    private fun notCopied(e: Exception): File? {
        Log.w(TAG, "a script's file was not copied: ${e.javaClass.simpleName}")
        return null
    }
}

/** Copying the file a script asked for into the game's folder, never over a file already there. */
internal object ScriptFiles {
    /** The name of a file the system's picker gives none, or one that is no name. */
    const val UNNAMED = "file"

    /** [displayName] as one file's name: its last path part, never empty, "." or "..". */
    fun safeName(displayName: String?): String {
        val last = displayName.orEmpty().replace('\\', '/').substringAfterLast('/').replace("\u0000", "").trim()
        return if (last.isEmpty() || last == "." || last == "..") UNNAMED else last
    }

    /** A file in [dir] that does not exist yet: [name], else "stem (2).ext", "stem (3).ext" and so on, as downloads are named. */
    fun freeFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        var file = File(dir, name)
        var n = 2
        while (file.exists()) file = File(dir, "$stem ($n)$extension").also { n++ }
        return file
    }

    /** Whether [dir] is a folder within [root], the app's own files, the only place a script's file is copied to. */
    fun inside(dir: File, root: File): Boolean = dir.isDirectory && dir.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())

    /** Copies [input] into [dir] under [displayName] made safe, whole or not at all, and returns the file. */
    fun copyInto(dir: File, displayName: String?, input: InputStream): File {
        val part = File.createTempFile("pick", ".part", dir)
        try {
            part.outputStream().use { input.copyTo(it) }
            // Not over a file that came meanwhile: the move refuses one
            return Files.move(part.toPath(), freeFile(dir, safeName(displayName)).toPath()).toFile()
        } finally {
            part.delete()
        }
    }
}

/**
 * A game script's openUrl() and openWebPage(): the page opens, in the app the system gives the link to, only from the
 * game's screen while the app is in front, as on the desktop, where the player sees the window the script runs in; in
 * the background, or from another screen, it is refused and the script gets false, as for a page the desktop could not
 * open. Only web and mail links: a file:, content: or intent: link, or a game's own scheme, is refused. One page opens
 * each time the player comes back to the app, so a script in a loop opens one, not one per call. The link is the
 * script's: it is never logged.
 */
object ScriptWebPages {
    private val schemes = setOf("http", "https", "mailto")

    // Set by the game's screen while it shows; called on the main thread
    internal var opener: ((Uri) -> Boolean)? = null
    private var openedSinceResumed = false

    /** The app came back to the front: the next page may open. */
    fun resumed() {
        openedSinceResumed = false
    }

    /** Whether the page at [url] opened. On the main thread. */
    fun open(url: String): Boolean {
        // An intent filter's scheme is case-sensitive
        val uri = url.toUri().normalizeScheme()
        if (uri.scheme !in schemes || openedSinceResumed) return false
        val opened = opener?.invoke(uri) == true
        if (opened) openedSinceResumed = true
        return opened
    }

    internal fun intent(uri: Uri): Intent = if (uri.scheme == "mailto") {
        Intent(Intent.ACTION_SENDTO, uri)
    } else {
        Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    }
}

private const val WEB_TAG = "ScriptWebPages"

/** Lets a script's page open while this game's screen shows and the app is in front ([ScriptWebPages]). */
@Composable
fun ScriptWebPageOpener() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val open: (Uri) -> Boolean = open@{ uri ->
            if ((activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.CREATED) != true) return@open false
            // Not the exception, whose message names the link
            try {
                activity.startActivity(ScriptWebPages.intent(uri))
                true
            } catch (_: ActivityNotFoundException) {
                Log.w(WEB_TAG, "no app opens a script's page")
                false
            } catch (_: SecurityException) {
                Log.w(WEB_TAG, "the app the system chose for a script's page refused it")
                false
            }
        }
        ScriptWebPages.opener = open
        onDispose {
            if (ScriptWebPages.opener === open) ScriptWebPages.opener = null
        }
    }
}
