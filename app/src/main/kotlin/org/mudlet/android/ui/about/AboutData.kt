package org.mudlet.android.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mudlet.android.R
import org.mudlet.android.about.Component
import org.mudlet.android.about.Licences
import org.mudlet.android.engine.R as EngineR

/**
 * The engine the APK carries, as scripts/build-apk.sh staged it (res/values/engine_info.xml in :engine-service):
 * [kind] is "standin" or "mudlet"; for Mudlet, its version and the commit of Mudlet it was built from, if known.
 */
data class EngineInfo(val kind: String, val version: String, val commit: String) {
    val isMudlet get() = kind == "mudlet"
}

@Composable
fun rememberEngineInfo(): EngineInfo {
    val context = LocalContext.current
    return remember {
        EngineInfo(context.getString(EngineR.string.engine_kind), context.getString(EngineR.string.engine_version), context.getString(EngineR.string.engine_commit))
    }
}

/** What reading the licence list gave: null while it is read */
sealed interface LicenceList {
    data class Loaded(val components: List<Component>) : LicenceList
    data class Failed(val reason: String) : LicenceList
}

@Composable
fun rememberLicences(engine: EngineInfo): LicenceList? {
    val context = LocalContext.current
    val list by produceState<LicenceList?>(null, engine) {
        value = withContext(Dispatchers.IO) {
            try {
                LicenceList.Loaded(Licences.parse(Licences.FILES.associateWith { readAsset(context, "${Licences.DIR}/$it") }, engine.kind))
            } catch (e: Exception) {
                // A broken APK: scripts/check-licences.py checks every APK CI builds, so this is not expected
                LicenceList.Failed(e.toString())
            }
        }
    }
    return list
}

fun readAsset(context: Context, path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

/** Opens [url] in the browser, or says why it cannot */
fun openLink(uriHandler: UriHandler, context: Context, url: String) {
    try {
        uriHandler.openUri(url)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.about_no_browser, url), Toast.LENGTH_LONG).show()
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, context.getString(R.string.about_no_browser, url), Toast.LENGTH_LONG).show()
    }
}

const val APP_REPOSITORY = "https://github.com/Mudlet/android"
const val MUDLET_REPOSITORY = "https://github.com/Mudlet/Mudlet"

/** A link to [path] in this repository at the commit the APK was built from, or main when that is not known */
fun appSourceUrl(commit: String, path: String = ""): String {
    val ref = commit.ifEmpty { "main" }
    return if (path.isEmpty()) "$APP_REPOSITORY/tree/$ref" else "$APP_REPOSITORY/blob/$ref/$path"
}

fun mudletSourceUrl(commit: String) = if (commit.isEmpty()) MUDLET_REPOSITORY else "$MUDLET_REPOSITORY/tree/$commit"
