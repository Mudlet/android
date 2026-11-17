package org.mudlet.android.ui.scripts

import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import org.mudlet.android.R
import org.mudlet.android.ui.ScriptItemsClient
import org.mudlet.android.ui.ScriptNotice

/**
 * Shows [scripts]' notices one at a time, each taken off once shown: an apply or a delete with its Undo, which a delete
 * is committed without once its snackbar goes, a move, and a refusal with the engine's reason. A new item or folder goes to
 * [onAdded] instead. Only one of these is shown at a time, or each notice would show twice.
 */
@Composable
internal fun ScriptNotices(scripts: ScriptItemsClient, onAdded: (ScriptNotice.Added) -> Unit) {
    val host = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val notice = scripts.notices.firstOrNull()
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        when (notice) {
            is ScriptNotice.Added -> onAdded(notice)

            is ScriptNotice.Applied -> {
                val name = notice.item.node.name.ifEmpty { resources.getString(R.string.scripts_unnamed) }
                if (host.undoable(resources.getString(R.string.scripts_applied, name), resources)) scripts.undo(notice.previous)
            }

            // Committed already, when the screen that offered its Undo went while it waited its turn
            is ScriptNotice.Deleted -> if (notice.key in scripts.deleting) {
                val name = notice.name.ifEmpty { resources.getString(R.string.scripts_unnamed) }
                if (host.undoable(resources.getString(R.string.scripts_deleted, name), resources)) scripts.keep(notice.key) else scripts.commitDelete(notice.key)
            }

            is ScriptNotice.Moved -> {
                val name = notice.name.ifEmpty { resources.getString(R.string.scripts_unnamed) }
                val message = when (val into = notice.into) {
                    null -> resources.getString(R.string.scripts_moved_top, name)
                    else -> resources.getString(R.string.scripts_moved, name, into.ifEmpty { resources.getString(R.string.scripts_unnamed) })
                }
                host.showSnackbar(message, withDismissAction = true)
            }

            is ScriptNotice.Refused -> host.showSnackbar(resources.refusal(notice), withDismissAction = true, duration = SnackbarDuration.Long)

            ScriptNotice.NotSent -> host.showSnackbar(resources.getString(R.string.profiles_error_not_attached), withDismissAction = true)
        }
        scripts.shown(notice)
    }
    SnackbarHost(host)
}

// Whether the player took the Undo
private suspend fun SnackbarHostState.undoable(message: String, resources: Resources): Boolean = showSnackbar(message, resources.getString(R.string.scripts_undo), withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed

private fun Resources.refusal(notice: ScriptNotice.Refused): String = getString(
    when (notice.request) {
        "scriptItems" -> R.string.scripts_refused_list
        "scriptItem" -> R.string.scripts_refused_open
        "addScriptItem" -> R.string.scripts_refused_add
        "editScriptItem" -> R.string.scripts_refused_apply
        "enableScriptItem" -> R.string.scripts_refused_enable
        "moveScriptItem" -> R.string.scripts_refused_move
        else -> R.string.scripts_refused_delete
    },
    notice.reason,
)
