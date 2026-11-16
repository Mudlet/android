package org.mudlet.android.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.FrameTabStrip
import org.mudlet.android.ui.EngineClient

// Each tab is touched within this, however short Mudlet's header is drawn
private val TAB_MIN_SIZE = 48.dp
private val TAB_INDICATOR = 3.dp

/**
 * An MXP frame's tab header ([FrameTabStrip]), drawn where [WindowLayout][org.mudlet.android.protocol.WindowLayout]
 * puts it: a tab for each of the frame's pages, by its title, the one in front selected. A tap on another has the
 * engine bring that page forward (`selectFrameTab`), and the page then drawn under the tabs is the one in front. Each
 * tab is read as a tab, selected or not, of the frame's tabs; they are off, and read as such, while there is no engine
 * to tell.
 */
@Composable
internal fun ScriptFrameTabs(strip: FrameTabStrip, modifier: Modifier = Modifier) {
    val engine = EngineClient.enginePid > 0
    val description = stringResource(R.string.frame_tabs_description, strip.tabs.pages.first().title)
    val indicator = MaterialTheme.colorScheme.primary
    Row(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant).semantics {
            contentDescription = description
            isTraversalGroup = true
            traversalIndex = WINDOWS_TRAVERSAL_INDEX
        }.horizontalScroll(rememberScrollState()).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        strip.tabs.pages.forEachIndexed { i, page ->
            key(page.name) {
                val selected = i == strip.tabs.current
                Box(
                    Modifier.fillMaxHeight().heightIn(min = TAB_MIN_SIZE).widthIn(min = TAB_MIN_SIZE)
                        .selectable(selected = selected, enabled = engine, role = Role.Tab) {
                            if (!selected) EngineClient.front.windows.selectFrameTab(page.name)
                        }
                        .drawBehind {
                            if (selected) {
                                val h = TAB_INDICATOR.toPx()
                                drawRect(indicator, Offset(0f, size.height - h), Size(size.width, h))
                            }
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        page.title,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
