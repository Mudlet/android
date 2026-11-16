package org.mudlet.android.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.mudlet.android.BuildConfig
import org.mudlet.android.R
import org.mudlet.android.about.Component
import org.mudlet.android.about.LicenceGroup
import org.mudlet.android.ui.ListSegment
import org.mudlet.android.ui.MudletScaffold
import org.mudlet.android.ui.SectionHeader

private val GROUP_TITLES = mapOf(
    LicenceGroup.APP to R.string.about_group_app,
    LicenceGroup.NATIVE to R.string.about_group_native,
    LicenceGroup.QT to R.string.about_group_qt,
    LicenceGroup.ANDROID to R.string.about_group_android,
)

/**
 * The app's version and engine, where the source of both is, how Qt's LGPL is met, and the licence of everything
 * the APK bundles (docs/licences.md); a component opens its licence texts through [onOpenComponent]. [selected] is the
 * component whose licence is open beside the list, if one is.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenComponent: (id: String) -> Unit, selected: String? = null) {
    val engine = engineInfo()
    val licences = rememberLicences(engine)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val open = { url: String -> openLink(uriHandler, context, url) }
    MudletScaffold(stringResource(R.string.about_title), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                Heading(stringResource(R.string.app_name), MaterialTheme.typography.headlineSmall)
                Paragraph(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                if (engine.isMudlet) {
                    Paragraph(stringResource(R.string.about_engine_mudlet, engine.version))
                    Paragraph(
                        if (engine.commit.isEmpty()) {
                            stringResource(R.string.about_engine_commit_unknown)
                        } else {
                            stringResource(R.string.about_engine_commit, engine.commit.take(12))
                        },
                    )
                } else {
                    Paragraph(stringResource(R.string.about_engine_standin))
                }
            }
            item {
                Heading(stringResource(R.string.about_free_software_heading))
                Paragraph(stringResource(R.string.about_free_software))
                LinkButton(stringResource(R.string.about_source_app)) { open(appSourceUrl(BuildConfig.SOURCE_COMMIT)) }
                if (engine.isMudlet) LinkButton(stringResource(R.string.about_source_mudlet)) { open(mudletSourceUrl(engine.commit)) }
                Paragraph(stringResource(R.string.about_qt_notice))
                val qt = (licences as? LicenceList.Loaded)?.components?.firstOrNull { it.id == "qt" }
                if (qt != null) LinkButton(stringResource(R.string.about_qt_source, qt.version)) { open(qt.source) }
                LinkButton(stringResource(R.string.about_qt_relink)) { open(appSourceUrl(BuildConfig.SOURCE_COMMIT, "docs/licences.md")) }
            }
            item { Heading(stringResource(R.string.about_licences_heading)) }
            when (licences) {
                null -> item { Paragraph(stringResource(R.string.about_loading)) }
                is LicenceList.Failed -> item { Paragraph(stringResource(R.string.about_load_failed, licences.reason)) }
                is LicenceList.Loaded -> componentList(licences.components, selected, onOpenComponent)
            }
        }
    }
}

private fun LazyListScope.componentList(components: List<Component>, selected: String?, onOpenComponent: (String) -> Unit) {
    components.groupBy { it.group }.forEach { (group, inGroup) ->
        item(key = "group-$group") { SectionHeader(stringResource(GROUP_TITLES.getValue(group))) }
        itemsIndexed(inGroup, key = { _, component -> component.id }) { i, component ->
            val openLabel = stringResource(R.string.about_open_licence)
            ListSegment(i, inGroup.size, Modifier.padding(horizontal = 16.dp), selected = component.id == selected) {
                ListItem(
                    headlineContent = { Text(component.name) },
                    supportingContent = {
                        Text(
                            if (component.version.isEmpty()) {
                                component.licence
                            } else {
                                stringResource(R.string.about_component_version_licence, component.version, component.licence)
                            },
                        )
                    },
                    modifier = Modifier.clickable(onClickLabel = openLabel) { onOpenComponent(component.id) },
                    // The segment under it is the container, tinted when selected
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

@Composable
internal fun Heading(text: String, style: TextStyle = MaterialTheme.typography.titleMedium) {
    Text(text, style = style, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
internal fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** A link as a button: TalkBack reads it as one, and it has a 48 dp target */
@Composable
internal fun LinkButton(label: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.padding(horizontal = 4.dp)) { Text(label) }
}
