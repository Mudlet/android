package org.mudlet.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mudlet.android.data.PackageRepository
import org.mudlet.android.data.RepositoryFetch
import org.mudlet.android.data.RepositoryPackage
import org.mudlet.android.protocol.InstalledPackage
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Where a package of the repository stands against the profile's packages. */
internal enum class RepositoryState {
    /** Not installed, or a package of its name by another author is, which installing it replaces, as on desktop. */
    AVAILABLE,

    /** Installed, at the repository's version or a later one. */
    INSTALLED,

    /** Installed, and the repository has a newer version. */
    UPDATE,
}

/** How far the repository's index has come. */
internal enum class IndexState { UNREAD, LOADING, READY, UNAVAILABLE }

/**
 * Mudlet's package repository for one profile, as desktop's Package Manager shows it (docs/architecture.md, "The package
 * manager"): the index kept in the profile's folder ([indexFile]), read each time the package manager opens and
 * downloaded when there is none to show, or on [refresh]; the icons, decoded by [decode]; and installs, each downloaded into a folder
 * of its own in [downloads] and handed to [packages], which removes an installed package of the same name first. Its state
 * is Compose state, read and changed on the main thread; files and the network are used on [network] and [iconThreads],
 * whose results come back through [main]. Nothing of an answer is logged; [log] hears only why a step failed.
 */
internal class PackageRepositoryClient<Icon : Any>(
    private val indexFile: () -> File?,
    private val downloads: () -> File,
    private val packages: PackagesClient,
    private val decode: (ByteArray) -> Icon?,
    private val main: (() -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
    private val network: Executor = NETWORK,
    private val iconThreads: Executor = ICONS,
) {
    /** The index's packages in desktop's order, by name; null until one is read or downloaded. */
    var index by mutableStateOf<List<RepositoryPackage>?>(null)
        private set

    var state by mutableStateOf(IndexState.UNREAD)
        private set

    /** Whether the index is being downloaded. */
    var refreshing by mutableStateOf(false)
        private set

    private var reading = false

    // How many downloaded indexes have been shown
    private var refreshes = 0
    private val fetching = mutableStateListOf<String>()

    // The file each package downloaded was handed to [packages] in, by name
    private val handed = mutableStateMapOf<String, String>()
    private val decoded = mutableStateMapOf<String, Icon>()
    private val iconsAsked = HashSet<String>()
    private var byName: Map<String, RepositoryPackage> = emptyMap()

    /** The packages being downloaded to install, by name. */
    val downloading: List<String> get() = fetching

    /** Whether the package [name] is being downloaded, or installed from its download. */
    fun busy(name: String): Boolean = name in fetching || handed[name]?.let { it in packages.installing } == true

    /** The icons downloaded so far, by package name. */
    val icons: Map<String, Icon> get() = decoded

    /**
     * Reads the index kept in the profile's folder, as desktop does each time its Package Manager opens, so that what mpkg
     * downloaded since shows; downloads it if there is none or it cannot be read, and nothing is shown yet.
     */
    fun open() {
        if (reading || refreshing) return
        val file = indexFile()
        if (file == null) {
            state = IndexState.UNAVAILABLE
            return
        }
        reading = true
        if (index == null) state = IndexState.LOADING
        val since = refreshes
        network.execute {
            val read = try {
                if (file.isFile) PackageRepository.parse(file.readText()) else null
            } catch (e: IOException) {
                log("could not read the kept index: ${e.javaClass.simpleName}")
                null
            }
            main {
                reading = false
                when {
                    // A refresh finished while it was read, with what replaced it
                    refreshes != since -> Unit

                    read != null -> show(read)

                    // Not said: the views say the repository is not available, and Installed needs none of it
                    index == null -> refresh(tell = false)
                }
            }
        }
    }

    /**
     * Downloads the index into the profile's folder and shows it; on a failure the one shown stays, and [packages] tells
     * of it when [tell].
     */
    fun refresh(tell: Boolean = true) {
        if (refreshing) return
        val file = indexFile()
        if (file == null) {
            state = IndexState.UNAVAILABLE
            return
        }
        refreshing = true
        if (index == null) state = IndexState.LOADING
        val via = fetch
        network.execute {
            val downloaded = try {
                val bytes = PackageRepository.bytes(via, PackageRepository.INDEX_URL, PackageRepository.INDEX_TIMEOUT_MS, PackageRepository.MAX_INDEX_BYTES)
                PackageRepository.parse(bytes.toString(Charsets.UTF_8)).also { parsed ->
                    if (parsed == null) {
                        log("the download is not a package index")
                    } else {
                        keep(file, bytes)
                    }
                }
            } catch (e: IOException) {
                log("could not download the index: ${e.javaClass.simpleName}")
                null
            }
            main {
                refreshing = false
                if (downloaded != null) {
                    refreshes++
                    show(downloaded)
                } else {
                    if (index == null) state = IndexState.UNAVAILABLE
                }
            }
        }
    }

    // Shown even if it cannot be kept: what the player sees is then newer than what mpkg reads, never older
    private fun keep(file: File, bytes: ByteArray) {
        try {
            PackageRepository.save(file, bytes)
        } catch (e: IOException) {
            log("could not keep the index: ${e.javaClass.simpleName}")
        }
    }

    private fun show(list: List<RepositoryPackage>) {
        // The last of a name, as desktop's lookup keeps it; listed once, as a name is a row's key
        byName = list.associateBy { it.name }
        index = byName.values.sortedWith(compareBy(PackageRepository.ORDER) { it.name })
        state = IndexState.READY
        // An icon that failed is tried again with the new index
        iconsAsked.retainAll(decoded.keys)
    }

    /** The index's package [name], or null. */
    fun entry(name: String): RepositoryPackage? = byName[name]

    /** Where [entry] stands against [installed], the profile's packages as the engine last listed them. */
    fun standing(entry: RepositoryPackage, installed: List<InstalledPackage>?): RepositoryState {
        val mine = installed?.firstOrNull { PackageRepository.matches(it, entry) } ?: return RepositoryState.AVAILABLE
        return if (PackageRepository.hasUpdate(mine, entry)) RepositoryState.UPDATE else RepositoryState.INSTALLED
    }

    /** The packages of [installed] the index has a newer version of: desktop's Updates view, in its order. */
    fun updates(installed: List<InstalledPackage>?): List<InstalledPackage> = installed.orEmpty().filter { mine -> entry(mine.name)?.let { PackageRepository.hasUpdate(mine, it) } == true }.sortedWith(compareBy(PackageRepository.ORDER) { it.name })

    /**
     * Downloads the package [name] and installs it, as desktop's Install and Update do: an installed package of that name
     * is removed first. A name the index has no file for is said, and the index downloaded again, as desktop does.
     */
    fun install(name: String) {
        if (busy(name)) return
        val entry = entry(name)
        val url = entry?.let(PackageRepository::fileUrl)
        val fileName = entry?.let(PackageRepository::fileName)
        if (url == null || fileName == null) {
            packages.tell(PackageNotice.NotInRepository(name))
            refresh()
            return
        }
        fetching.add(name)
        val via = fetch
        network.execute {
            val folder = File(downloads(), UUID.randomUUID().toString())
            val file = File(folder, fileName)
            val saved = try {
                if (!folder.mkdirs()) throw IOException("could not create a folder for the download")
                file.outputStream().use { via.get(url, PackageRepository.PACKAGE_TIMEOUT_MS, PackageRepository.MAX_PACKAGE_BYTES, it) }
                true
            } catch (e: IOException) {
                log("could not download a package: ${e.javaClass.simpleName}")
                folder.deleteRecursively()
                false
            }
            main {
                fetching.remove(name)
                if (!saved) {
                    packages.tell(PackageNotice.DownloadFailed(name))
                    return@main
                }
                handed[name] = file.path
                if (packages.packages?.any { it.name == name } == true) packages.replace(name, file.path) else packages.install(file.path)
            }
        }
    }

    /** Downloads the icon of the package [name], once, when a row shows it. */
    fun iconShown(name: String) {
        if (name in decoded || !iconsAsked.add(name)) return
        val url = entry(name)?.let(PackageRepository::iconUrl) ?: return
        val via = fetch
        iconThreads.execute {
            val icon = try {
                decode(PackageRepository.bytes(via, url, PackageRepository.ICON_TIMEOUT_MS, PackageRepository.MAX_ICON_BYTES))
            } catch (e: IOException) {
                log("could not download an icon: ${e.javaClass.simpleName}")
                null
            }
            main { if (icon != null) decoded[name] = icon }
        }
    }

    companion object {
        /** How the repository is reached: GitHub, but for the tests, which must never reach it. */
        @Volatile
        var fetch: RepositoryFetch = PackageRepository.HTTPS

        // Two each, so that a slow icon never holds up the index or an install
        private val NETWORK: Executor = threads("PackageRepository")
        private val ICONS: Executor = threads("PackageRepositoryIcons")

        private fun threads(name: String): Executor = Executors.newFixedThreadPool(2) { Thread(it, name).apply { isDaemon = true } }
    }
}
