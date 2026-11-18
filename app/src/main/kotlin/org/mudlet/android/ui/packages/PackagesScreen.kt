package org.mudlet.android.ui.packages

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mudlet.android.R
import org.mudlet.android.protocol.InstalledPackage
import org.mudlet.android.protocol.PackageInstallResult
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.PackageNotice
import org.mudlet.android.ui.PackagesClient
import org.mudlet.android.ui.ScriptWindowsClient.ImageRequest
import org.mudlet.android.ui.game.engineImage
import org.mudlet.android.ui.settings.MAX_WIDTH
import java.io.File
import java.util.UUID

/** The package manager's list, for tests to scroll. */
const val PACKAGES_TAG = "packages"

private const val DESCRIPTION_LINES = 2
private const val ICON_PX = 96

/**
 * The package manager of [game], as desktop's Package Manager: the installed packages in the order installed, each
 * with its icon, title, version, author and description, which a tap shows whole; a file picked with the system's
 * picker or a URL installs one, and Remove, once confirmed, removes one.
 */
@Composable
internal fun PackagesScreen(packages: PackagesClient, game: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var urlOpen by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    // Asked again once the engine is back, as what was asked of the last link is not answered
    LaunchedEffect(packages.attaches) { packages.ask() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copy = withContext(Dispatchers.IO) { copyPicked(context, uri) }
            if (copy == null) {
                host.showSnackbar(resources.getString(R.string.packages_unreadable), withDismissAction = true)
            } else {
                packages.install(copy.path)
            }
        }
    }
    PackageNotices(packages, host)

    MudletScaffold(
        stringResource(R.string.packages_title, game),
        onBack = onBack,
        actions = {
            // Any type: a .mpackage has none that Android knows
            IconButton({ pick.launch(arrayOf("*/*")) }) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.packages_install_file)) }
            IconButton({ urlOpen = true }) { Icon(painterResource(R.drawable.ic_lan), stringResource(R.string.packages_install_url)) }
        },
        snackbarHost = { SnackbarHost(host) },
    ) { padding ->
        val listed = packages.packages
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(PACKAGES_TAG), horizontalAlignment = Alignment.CenterHorizontally) {
            items(emptyList<String>(), key = { "installing:$it" }) { path ->
                Column(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(stringResource(R.string.packages_installing, path.substringAfterLast('/')))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            when {
                listed == null -> item { Text(stringResource(R.string.packages_loading), Modifier.padding(16.dp)) }
                listed.isEmpty() -> item { Text(stringResource(R.string.packages_none), Modifier.padding(16.dp)) }
                else -> items(listed, key = { it.name }) { PackageRow(it) { packages.uninstall(it.name) } }
            }
        }
    }

    if (urlOpen) {
        UrlDialog(onDismiss = { urlOpen = false }) { url ->
            urlOpen = false
            packages.installFrom(url)
        }
    }
    removing?.let { name ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.packages_remove_title, name)) },
            text = { Text(stringResource(R.string.packages_remove_text)) },
            confirmButton = {
                TextButton({
                    removing = null
                    packages.uninstall(name)
                }) { Text(stringResource(R.string.packages_remove)) }
            },
            dismissButton = { TextButton({ removing = null }) { Text(stringResource(R.string.packages_cancel)) } },
        )
    }
}

@Composable
private fun PackageRow(item: InstalledPackage, onRemove: () -> Unit) {
    var expanded by rememberSaveable(item.name) { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.packages_expanded else R.string.packages_collapsed)
    // Read as one row; only a description can be shown whole, and a row with none is not read as a disabled button
    val reading = if (item.description.isEmpty()) {
        Modifier.semantics(mergeDescendants = true) {}
    } else {
        Modifier.clickable { expanded = !expanded }.semantics { stateDescription = state }
    }
    Row(
        Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().then(reading).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val icon = engineImage(item.icon.takeIf { it.isNotEmpty() }?.let { ImageRequest(it, ICON_PX, ICON_PX) })
        if (icon != null) {
            Image(icon, contentDescription = null, Modifier.size(40.dp))
        } else {
            Icon(painterResource(R.drawable.ic_inventory_2), contentDescription = null, Modifier.size(40.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.title.ifEmpty { item.name }, Modifier.width(24.dp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val facts = listOfNotNull(
                null,
                item.version.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.packages_version, it) },
                item.author.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.packages_author, it) },
            )
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (item.description.isNotEmpty()) {
                Text(
                    item.description,
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onRemove) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.packages_remove_named, item.name)) }
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onInstall: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    val usable = url.trim().let { it.startsWith("https://") || it.startsWith("http://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.packages_install_url)) },
        text = {
            OutlinedTextField(
                url,
                { url = it },
                label = { Text(stringResource(R.string.packages_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        },
        confirmButton = { TextButton({ onInstall(url.trim()) }, enabled = usable) { Text(stringResource(R.string.packages_install)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.packages_cancel)) } },
    )
}

/** Shows [packages]' notices one at a time on [host], each taken off once shown. */
@Composable
private fun PackageNotices(packages: PackagesClient, host: SnackbarHostState) {
    val resources = LocalResources.current
    val notice = packages.notices.firstOrNull()
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        val text = when (notice) {
            is PackageNotice.Installed -> when (notice.result) {
                PackageInstallResult.INSTALLED -> resources.getString(R.string.packages_installed, notice.file)
                PackageInstallResult.INSTALLED_WITH_ERRORS -> resources.getString(R.string.packages_installed_with_errors, notice.file, notice.error)
                PackageInstallResult.ALREADY_INSTALLED -> resources.getString(R.string.packages_already_installed, notice.file)
                PackageInstallResult.FAILED -> resources.getString(R.string.packages_install_failed, notice.file, notice.error)
            }

            is PackageNotice.Refused -> resources.getString(
                when (notice.request) {
                    "uninstallPackage" -> R.string.packages_refused_remove
                    "installPackage" -> R.string.packages_refused_install
                    else -> R.string.packages_refused_list
                },
                notice.reason,
            )

            PackageNotice.NotSent -> resources.getString(R.string.profiles_error_not_attached)
        }
        val long = notice is PackageNotice.Refused || (notice is PackageNotice.Installed && notice.result != PackageInstallResult.INSTALLED)
        host.showSnackbar(text, withDismissAction = true, duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short)
        packages.shown(notice)
    }
}

/** Where picked package files are copied for the engine, which runs as this app and cannot read a content:// URI. */
private fun pickedPackagesDir(context: Context) = File(context.cacheDir, "packages")

/** The folder of the picked package's copy at [path], which can go once the engine has answered its install. */
internal fun pickedPackage(context: Context, path: String): File? = File(path).parentFile?.takeIf { it.parentFile == pickedPackagesDir(context) }

// Under the name it was picked by, as Mudlet names an .xml package after its file, in a folder of its own, so that
// the same file picked again while the first is installing does not overwrite or delete it
private fun copyPicked(context: Context, uri: Uri): File? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: return null
    val dir = File(pickedPackagesDir(context), UUID.randomUUID().toString()).apply { mkdirs() }
    val copy = File(dir, name)
    context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } } ?: return null
    copy
}.onFailure { Log.w(TAG, "Could not copy the picked package", it) }.getOrNull()

private const val TAG = "PackagesScreen"
