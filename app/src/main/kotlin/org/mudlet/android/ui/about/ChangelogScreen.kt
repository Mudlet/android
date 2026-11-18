package org.mudlet.android.ui.about

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.about.ReleaseNotes
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.inBackground

/**
 * What each stable release up to this build said is new, from docs/release-notes.md, as desktop Mudlet's Help >
 * "Show changelog" shows its own: a heading for each version, the newest first, and its lines under it, an item each
 * for TalkBack to read and scroll through.
 */
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // null while it is read; the APK always has the file (the build copies it)
    val releases by produceState<List<ReleaseNotes>?>(null) {
        value = inBackground { ReleaseNotes.parse(readAsset(context, ReleaseNotes.ASSET)) }
    }
    MudletScaffold(stringResource(R.string.changelog_title), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            val shown = releases
            when {
                shown == null -> item { Paragraph(stringResource(R.string.about_loading)) }

                shown.isEmpty() -> item { Paragraph(stringResource(R.string.changelog_none)) }

                else -> for (release in shown) {
                    item(key = release.version) { Heading(stringResource(R.string.changelog_version, release.version)) }
                    items(release.lines) { Paragraph(it) }
                }
            }
        }
    }
}
