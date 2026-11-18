package org.mudlet.android.ui.report

import android.content.Context
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.report.CollectedReport
import org.mudlet.android.report.ReportCollector
import org.mudlet.android.report.newIssueUrl
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.about.Heading
import org.mudlet.android.ui.about.Paragraph
import org.mudlet.android.ui.about.openLink
import org.mudlet.android.ui.copyText
import org.mudlet.android.ui.inBackground
import org.mudlet.android.ui.shareText

/**
 * What a problem report holds, shown whole before the player sends it, and the ways to send it: Android's share
 * sheet, the clipboard, or a new GitHub issue in the browser. The app itself sends nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportProblemScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val report by produceState<CollectedReport?>(null) {
        val known = EngineClient.profiles
        value = inBackground { ReportCollector.collect(context.applicationContext, known) }
    }
    val ready = report
    val enabled = ready != null
    val lines = remember(ready) { ready?.text?.lines() }
    MudletScaffold(stringResource(R.string.report_title), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { Paragraph(stringResource(R.string.report_intro)) }
            item {
                FlowRow(Modifier.padding(horizontal = 12.dp)) {
                    Button({ ready?.let { share(context, it.text) } }, Modifier.padding(4.dp), enabled = enabled) {
                        Text(stringResource(R.string.report_share))
                    }
                    OutlinedButton({ ready?.let { copy(context, it.text) } }, Modifier.padding(4.dp), enabled = enabled) {
                        Text(stringResource(R.string.report_copy))
                    }
                    OutlinedButton(
                        {
                            ready?.let {
                                copy(context, it.text)
                                openLink(uriHandler, context, newIssueUrl(it.header))
                            }
                        },
                        Modifier.padding(4.dp),
                        enabled = enabled,
                    ) { Text(stringResource(R.string.report_github)) }
                }
            }
            item { Heading(stringResource(R.string.report_contents_heading)) }
            item {
                // TalkBack says once that the report is ready, as the buttons above it come on
                Text(
                    stringResource(if (ready == null) R.string.report_collecting else R.string.report_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (lines != null) {
                items(lines) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

private fun share(context: Context, text: String) = shareText(
    context,
    text,
    context.getString(R.string.report_share),
    context.getString(R.string.report_no_share),
    subject = context.getString(R.string.report_subject),
)

private fun copy(context: Context, text: String) = copyText(context, context.getString(R.string.report_title), text, context.getString(R.string.report_copied))
