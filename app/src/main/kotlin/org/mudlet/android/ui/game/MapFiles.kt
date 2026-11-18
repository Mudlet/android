package org.mudlet.android.ui.game

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mudlet.android.R
import org.mudlet.android.data.MapBackups
import org.mudlet.android.data.MapFileStaging
import org.mudlet.android.protocol.MapFileState
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MapFileClient
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The full-screen map's menu of map files: load one, save the map to one, or share it (docs/architecture.md, "Map files"). */
@Composable
internal fun MapFileMenu(modifier: Modifier = Modifier) {
    val files = EngineClient.front.appMap.files
    var open by remember { mutableStateOf(false) }
    // Any type: a map's file can come from a game's site or a cloud drive with no type, or a wrong one, of its own
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { files.load(it) } }
    val saveDat = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MapFileStaging.Format.DAT.mimeType)) { uri ->
        uri?.let { files.save(it, MapFileStaging.Format.DAT) }
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MapFileStaging.Format.JSON.mimeType)) { uri ->
        uri?.let { files.save(it, MapFileStaging.Format.JSON) }
    }
    var backups by remember { mutableStateOf(false) }
    val enabled = EngineClient.enginePid > 0 && !files.busy
    if (backups) MapBackupsDialog(EngineClient.front.name, { files.load(it) }) { backups = false }
    Box(modifier) {
        IconButton({ open = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_folder), stringResource(R.string.map_file_menu))
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.map_file_load)) }, {
                open = false
                load.launch(arrayOf("*/*"))
            })
            DropdownMenuItem({ Text(stringResource(R.string.map_file_backups)) }, {
                open = false
                backups = true
            })
            DropdownMenuItem({ Text(stringResource(R.string.map_file_save)) }, {
                open = false
                saveDat.launch("map.${MapFileStaging.Format.DAT.extension}")
            })
            DropdownMenuItem({ Text(stringResource(R.string.map_file_save_json)) }, {
                open = false
                saveJson.launch("map.${MapFileStaging.Format.JSON.extension}")
            })
            DropdownMenuItem({ Text(stringResource(R.string.map_file_share)) }, {
                open = false
                files.share()
            })
        }
    }
}

/** The maps Mudlet saved in [profile]'s map folder, the newest first, to [onLoad] one (docs/architecture.md, "Map files"). */
@Composable
private fun MapBackupsDialog(profile: String, onLoad: (MapBackups.Backup) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val listed by produceState<List<MapBackups.Backup>?>(null, profile) {
        value = withContext(Dispatchers.IO) { MapBackups.list(MapBackups.folder(context.filesDir, profile)) }
    }
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.map_backups_close)) } },
        title = { Text(stringResource(R.string.map_backups_title)) },
        text = {
            val backups = listed
            when {
                backups == null -> {}

                backups.isEmpty() -> Text(stringResource(R.string.map_backups_none))

                else -> {
                    val load = stringResource(R.string.map_backups_load)
                    LazyColumn {
                        items(backups, key = { it.file.name }) { backup ->
                            ListItem(
                                headlineContent = { Text(if (backup.kind == MapBackups.Kind.NAMED) backup.file.name else format.format(backup.time)) },
                                supportingContent = { Text(stringResource(backupKind(backup))) },
                                modifier = Modifier.clickable(onClickLabel = load, role = Role.Button) {
                                    onDismiss()
                                    onLoad(backup)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
        },
    )
}

private fun backupKind(backup: MapBackups.Backup) = when {
    backup.kind == MapBackups.Kind.AUTOSAVE -> R.string.map_backups_autosave
    backup.format == MapFileStaging.Format.JSON -> R.string.map_backups_json
    backup.format == MapFileStaging.Format.XML -> R.string.map_backups_xml
    else -> R.string.map_backups_dat
}

/** How a map file goes, with Cancel while the engine can stop it, then how it ended until dismissed; nothing otherwise. */
@Composable
internal fun MapFileBar(modifier: Modifier = Modifier) {
    val files = EngineClient.front.appMap.files
    val state = files.state ?: return
    Surface(modifier, shape = MaterialTheme.shapes.small, tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (state is MapFileClient.State.Ended) {
                MapFileEnd(state, files::dismiss)
            } else {
                MapFileProgress(state, files)
            }
        }
    }
}

@Composable
private fun MapFileProgress(state: MapFileClient.State, files: MapFileClient) {
    val frame = (state as? MapFileClient.State.Running)?.frame
    val label = frame?.label.orEmpty().ifEmpty {
        stringResource(if (state.job == MapFileClient.Job.LOAD) R.string.map_file_loading else R.string.map_file_saving)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(files::cancel, Modifier.heightIn(min = 48.dp), enabled = frame?.cancelable == true && EngineClient.enginePid > 0) {
            Text(stringResource(R.string.map_file_cancel))
        }
    }
    val description = stringResource(R.string.map_file_progress)
    val fraction = frame?.takeIf { it.maximum > 0 && it.value <= it.maximum }?.let { it.value.toFloat() / it.maximum }
    // Clear of Cancel above it by the 10dp TalkBack's reach for the indicator extends past it, as MapDownloadProgress's is
    val bar = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp).semantics { contentDescription = description }
    if (fraction != null) LinearProgressIndicator(progress = { fraction }, bar) else LinearProgressIndicator(bar)
}

// MapFileClient tells TalkBack as it ends
@Composable
private fun MapFileEnd(ended: MapFileClient.State.Ended, onDismiss: () -> Unit) {
    val failed = ended.outcome == MapFileState.FAILED
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            MapFileClient.endText(ended, LocalResources.current),
            Modifier.weight(1f),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(onDismiss) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.map_file_dismiss))
        }
    }
}
