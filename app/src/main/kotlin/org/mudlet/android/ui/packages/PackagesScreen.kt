package org.mudlet.android.ui.packages

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.data.PackageRepository
import org.mudlet.android.data.RepositoryPackage
import org.mudlet.android.protocol.InstalledPackage
import org.mudlet.android.protocol.PackageInstallResult
import org.mudlet.android.ui.IndexState
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.PackageNotice
import org.mudlet.android.ui.PackageRepositoryClient
import org.mudlet.android.ui.PackagesClient
import org.mudlet.android.ui.RepositoryState
import org.mudlet.android.ui.ScriptWindowsClient.ImageRequest
import org.mudlet.android.ui.game.engineImage
import org.mudlet.android.ui.inBackground
import org.mudlet.android.ui.settings.MAX_WIDTH
import java.io.File
import java.util.UUID

/** The package manager's list, for tests to scroll. */
const val PACKAGES_TAG = "packages"

private const val DESCRIPTION_LINES = 2
private const val ICON_PX = 96

/** Desktop's three views of the package manager, each a chip. */
internal enum class PackagesView { INSTALLED, EXPLORE, UPDATES }

/**
 * The package manager of [game], as desktop's Package Manager, with its three views: Installed, the profile's packages in
 * the order installed, each with its icon, title, version, author and description, which a tap shows whole, and Remove,
 * once confirmed; Explore, the packages of Mudlet's package repository ([repository]), each installed with a tap, and
 * marked as installed or with an update; and Updates, the installed packages the repository has a newer version of. A
 * search filters the view shown. A file picked with the system's picker or a URL installs one.
 */
@Composable
internal fun PackagesScreen(packages: PackagesClient, repository: PackageRepositoryClient<ImageBitmap>, game: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var urlOpen by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var view by rememberSaveable { mutableStateOf(PackagesView.INSTALLED) }
    var query by rememberSaveable { mutableStateOf("") }
    // Asked again once the engine is back, as what was asked of the last link is not answered
    LaunchedEffect(packages.attaches) { packages.ask() }
    // Read on opening, as desktop does, so that the Updates chip counts them in any view
    LaunchedEffect(repository) { repository.open() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copy = inBackground { copyPicked(context, uri) }
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
        val updates = repository.updates(listed)
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(PACKAGES_TAG), horizontalAlignment = Alignment.CenterHorizontally) {
            item(key = "search") { SearchField(query) { query = it } }
            item(key = "views") {
                ViewChips(view, updates.size, repository.refreshing.takeIf { view != PackagesView.INSTALLED }, repository::refresh) {
                    // A search is of one view, as on desktop
                    view = it
                    query = ""
                }
            }
            items(packages.installing, key = { "installing:$it" }) { path -> Progress(stringResource(R.string.packages_installing, path.substringAfterLast('/'))) }
            items(repository.downloading, key = { "downloading:$it" }) { name -> Progress(stringResource(R.string.packages_downloading, name)) }
            when (view) {
                PackagesView.INSTALLED -> when {
                    listed == null -> item { Note(stringResource(R.string.packages_loading)) }

                    listed.isEmpty() -> item { Note(stringResource(R.string.packages_none)) }

                    else -> {
                        val found = listed.filter { PackageRepository.found(query, it.name, it.title, it.description, it.author) }
                        if (found.isEmpty()) item { Note(stringResource(R.string.packages_no_match)) }
                        items(found, key = { "installed:${it.name}" }) { InstalledRow(it) { removing = it.name } }
                    }
                }

                PackagesView.EXPLORE -> repositoryRows(repository) { index ->
                    val found = index.filter { PackageRepository.found(query, it.name, it.title, it.description, it.author) }
                    if (found.isEmpty()) item { Note(stringResource(R.string.packages_no_match)) }
                    items(found, key = { "repository:${it.name}" }) { entry ->
                        RepositoryRow(entry, repository.standing(entry, listed), repository, entry.version.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.packages_version, it) })
                    }
                }

                PackagesView.UPDATES -> repositoryRows(repository) {
                    val found = updates.filter { PackageRepository.found(query, it.name, it.title, it.description, it.author) }
                    when {
                        updates.isEmpty() -> item { Note(stringResource(R.string.packages_up_to_date)) }
                        found.isEmpty() -> item { Note(stringResource(R.string.packages_no_match)) }
                    }
                    items(found, key = { "update:${it.name}" }) { mine ->
                        val entry = repository.entry(mine.name) ?: return@items
                        RepositoryRow(entry, RepositoryState.UPDATE, repository, stringResource(R.string.packages_update_versions, mine.version, entry.version))
                    }
                }
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

/** The repository's rows, by [rows], once its index is in; until then, or if it cannot be had, why not. */
private fun LazyListScope.repositoryRows(repository: PackageRepositoryClient<ImageBitmap>, rows: LazyListScope.(List<RepositoryPackage>) -> Unit) {
    val index = repository.index
    when {
        index != null -> {
            if (repository.refreshing) item(key = "refreshing") { Note(stringResource(R.string.packages_repository_refreshing), live = true) }
            rows(index)
        }

        repository.state == IndexState.UNAVAILABLE -> item(key = "unavailable") { Note(stringResource(R.string.packages_repository_unavailable), live = true) }

        else -> item(key = "loading") { Note(stringResource(R.string.packages_repository_loading), live = true) }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        query,
        onQuery,
        Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.packages_search)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton({ onQuery("") }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.settings_search_clear)) }
            }
        },
        singleLine = true,
        shape = CircleShape,
    )
}

/**
 * A chip for each of desktop's views, Updates with how many there are, as desktop's says; then, when [refreshing] is not
 * null (a view of the repository), the button that downloads its index again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewChips(view: PackagesView, updates: Int, refreshing: Boolean?, onRefresh: () -> Unit, onView: (PackagesView) -> Unit) {
    FlowRow(
        Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        for (v in PackagesView.entries) {
            val label = when (v) {
                PackagesView.INSTALLED -> stringResource(R.string.packages_view_installed)
                PackagesView.EXPLORE -> stringResource(R.string.packages_view_explore)
                PackagesView.UPDATES -> if (updates > 0) stringResource(R.string.packages_view_updates_count, updates) else stringResource(R.string.packages_view_updates)
            }
            FilterChip(view == v, { onView(v) }, label = { Text(label) })
        }
        if (refreshing != null) {
            IconButton(onRefresh, enabled = !refreshing) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.packages_refresh)) }
        }
    }
}

@Composable
private fun Progress(text: String) {
    Column(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(text)
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun Note(text: String, live: Boolean = false) {
    val reading = if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
    Text(text, Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().padding(16.dp).then(reading))
}

@Composable
private fun InstalledRow(item: InstalledPackage, onRemove: () -> Unit) {
    val icon = engineImage(item.icon.takeIf { it.isNotEmpty() }?.let { ImageRequest(it, ICON_PX, ICON_PX) })
    val version = item.version.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.packages_version, it) }
    PackageRow(item.name, item.title, version, item.author, item.description, icon, note = null) {
        IconButton(onRemove) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.packages_remove_named, item.name)) }
    }
}

/** A package of the repository, with its icon once downloaded, [version] as the view gives it, and what can be done with it. */
@Composable
private fun RepositoryRow(entry: RepositoryPackage, standing: RepositoryState, repository: PackageRepositoryClient<ImageBitmap>, version: String?) {
    LaunchedEffect(entry.name) { repository.iconShown(entry.name) }
    val busy = repository.busy(entry.name)
    val note = when (standing) {
        RepositoryState.AVAILABLE -> null
        RepositoryState.INSTALLED -> stringResource(R.string.packages_state_installed)
        RepositoryState.UPDATE -> stringResource(R.string.packages_state_update)
    }
    PackageRow(entry.name, entry.title, version, entry.author, entry.description, repository.icons[entry.name], note) {
        when (standing) {
            RepositoryState.AVAILABLE -> IconButton({ repository.install(entry.name) }, enabled = !busy) {
                Icon(painterResource(R.drawable.ic_download), stringResource(R.string.packages_install_named, entry.name))
            }

            RepositoryState.UPDATE -> IconButton({ repository.install(entry.name) }, enabled = !busy) {
                Icon(painterResource(R.drawable.ic_system_update), stringResource(R.string.packages_update_named, entry.name))
            }

            // Its state is read with the row; a mark beside it for the eye
            RepositoryState.INSTALLED -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.padding(12.dp))
        }
    }
}

/**
 * A package's row: its icon, its title or else its name, then its name, [version] and [author], [note] (where it stands
 * against the repository) and the start of its [description], which a tap shows whole; [action] at the end.
 */
@Composable
private fun PackageRow(
    name: String,
    title: String,
    version: String?,
    author: String,
    description: String,
    icon: ImageBitmap?,
    note: String?,
    action: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(name) { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.packages_expanded else R.string.packages_collapsed)
    // Read as one row; only a description can be shown whole, and a row with none is not read as a disabled button
    val reading = if (description.isEmpty()) {
        Modifier.semantics(mergeDescendants = true) {}
    } else {
        Modifier.clickable { expanded = !expanded }.semantics { stateDescription = state }
    }
    Row(
        Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().then(reading).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, Modifier.size(40.dp))
        } else {
            Icon(painterResource(R.drawable.ic_inventory_2), contentDescription = null, Modifier.size(40.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title.ifEmpty { name }, fontWeight = FontWeight.Bold)
            val facts = listOfNotNull(
                name.takeIf { title.isNotEmpty() },
                version,
                author.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.packages_author, it) },
            )
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (note != null) Text(note, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (description.isNotEmpty()) {
                Text(
                    description,
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action()
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

            is PackageNotice.IndexFailed -> resources.getString(if (notice.cached) R.string.packages_index_failed_cached else R.string.packages_index_failed)

            is PackageNotice.DownloadFailed -> resources.getString(R.string.packages_download_failed, notice.name)

            is PackageNotice.NotInRepository -> resources.getString(R.string.packages_not_in_repository, notice.name)
        }
        val long = when (notice) {
            is PackageNotice.Installed -> notice.result != PackageInstallResult.INSTALLED
            PackageNotice.NotSent -> false
            else -> true
        }
        host.showSnackbar(text, withDismissAction = true, duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short)
        packages.shown(notice)
    }
}

/**
 * Where picked package files are copied, and the repository's downloaded, for the engine, which runs as this app and cannot
 * read a content:// URI; each in a folder of its own.
 */
internal fun pickedPackagesDir(context: Context) = File(context.cacheDir, "packages")

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

/** A repository package's icon, decoded to about the size its row shows it at; null if it is not an image. */
internal fun decodeIcon(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_PX) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}
