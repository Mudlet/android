package org.mudlet.android.ui.about

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mudlet.android.R
import org.mudlet.android.about.Component
import org.mudlet.android.about.Licences
import org.mudlet.android.ui.MudletScaffold

/**
 * One component of the About screen's list: its version, copyright, links, and the full text of each licence its
 * SPDX expression names, a paragraph to an item, so that TalkBack reads and scrolls through it paragraph by paragraph.
 */
@Composable
fun LicenceScreen(componentId: String, onBack: () -> Unit) {
    val licences = rememberLicences(engineInfo())
    val component = (licences as? LicenceList.Loaded)?.components?.firstOrNull { it.id == componentId }
    val context = LocalContext.current
    // each text's paragraphs, or null for a text the APK lacks (scripts/check-licences.py checks it has them all)
    val texts by produceState<Map<String, List<String>?>?>(null, component) {
        value = component?.let { c ->
            withContext(Dispatchers.IO) {
                c.texts.associateWith { id -> runCatching { Licences.paragraphs(readAsset(context, Licences.textPath(id))) }.getOrNull() }
            }
        }
    }
    MudletScaffold(component?.name ?: stringResource(R.string.about_title), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            when {
                licences == null -> item { Paragraph(stringResource(R.string.about_loading)) }

                licences is LicenceList.Failed -> item { Paragraph(stringResource(R.string.about_load_failed, licences.reason)) }

                component == null -> item { Paragraph(stringResource(R.string.licence_not_found, componentId)) }

                else -> {
                    item { ComponentDetails(component) }
                    licenceTexts(component, texts)
                }
            }
        }
    }
}

@Composable
private fun ComponentDetails(component: Component) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    if (component.version.isNotEmpty()) Paragraph(stringResource(R.string.licence_version, component.version))
    Paragraph(stringResource(R.string.licence_licence, component.licence))
    if (component.copyright.isNotEmpty()) Paragraph(component.copyright)
    if (component.url.isNotEmpty()) LinkButton(stringResource(R.string.licence_website)) { openLink(uriHandler, context, component.url) }
    if (component.source.isNotEmpty()) LinkButton(stringResource(R.string.licence_source)) { openLink(uriHandler, context, component.source) }
}

private fun LazyListScope.licenceTexts(component: Component, texts: Map<String, List<String>?>?) {
    for (id in component.texts) {
        item(key = "heading-$id") { Heading(stringResource(R.string.licence_text_heading, id)) }
        val paragraphs = texts?.get(id)
        when {
            texts == null -> item(key = "loading-$id") { Paragraph(stringResource(R.string.about_loading)) }
            paragraphs == null -> item(key = "missing-$id") { Paragraph(stringResource(R.string.licence_text_missing, id)) }
            else -> itemsIndexed(paragraphs, key = { i, _ -> "$id-$i" }) { _, paragraph -> Paragraph(paragraph) }
        }
    }
}
