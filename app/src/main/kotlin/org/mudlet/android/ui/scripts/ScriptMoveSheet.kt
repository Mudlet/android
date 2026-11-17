package org.mudlet.android.ui.scripts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.ScriptItemNode

/**
 * The sheet that moves the item named [name]: a row for each of [targets], the folders of [listed] it can go in (null
 * for the top level), each with the folders it is in, as names repeat; a tap gives [onPick] the folder's id, 0 for the
 * top level.
 */
@Composable
internal fun MoveSheet(name: String, listed: List<ScriptItemNode>, targets: List<ScriptItemNode?>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val separator = stringResource(R.string.scripts_path_separator)
    val unnamed = stringResource(R.string.scripts_unnamed)
    TitledSheet(stringResource(R.string.scripts_move_title, name), stringResource(R.string.scripts_move_close), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            for (target in targets) {
                val text = if (target == null) {
                    stringResource(R.string.scripts_move_top)
                } else {
                    listed.pathTo(target.id).joinToString(separator) { it.name.ifEmpty { unnamed } }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(target?.id ?: 0) }.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_folder), contentDescription = null, Modifier.padding(end = 16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
