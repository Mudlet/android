package org.mudlet.android.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.ui.about.openLink
import org.mudlet.android.update.PtbUpdateCheck
import org.mudlet.android.update.PtbUpdates

/**
 * "A newer test build is available", at the top of the profile list, with Download, which opens the `ptb` pre-release's
 * page in the browser, and Dismiss. It stays until answered, unlike a snackbar, which a TalkBack user can miss.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PtbUpdateNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val download = stringResource(R.string.ptb_notice_download_description)
    val dismiss = stringResource(R.string.ptb_notice_dismiss_description)
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
            // Read out as it appears: the answer from GitHub can come while the list is already being read
            Text(
                stringResource(R.string.ptb_notice_text),
                Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(PtbUpdates::dismiss, Modifier.semantics { contentDescription = dismiss }) {
                    Text(stringResource(R.string.ptb_notice_dismiss))
                }
                Button({ openLink(uriHandler, context, PtbUpdateCheck.RELEASE_PAGE) }, Modifier.semantics { contentDescription = download }) {
                    Text(stringResource(R.string.ptb_notice_download))
                }
            }
        }
    }
}
