package org.mudlet.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val SEGMENT_GAP = 2.dp

/**
 * One item of a group, as Material 3 Expressive lists them: items 2 dp apart, each on its own container, the
 * group's first and last rounder at its ends. [index] is the item's place in a group of [count]; [selected] tints it,
 * as the item whose detail is shown beside the list.
 */
@Composable
fun ListSegment(index: Int, count: Int, modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shapes = MaterialTheme.shapes
    val outer = shapes.large.topStart
    val inner = shapes.extraSmall.topStart
    val shape = shapes.large.copy(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.padding(bottom = if (index < count - 1) SEGMENT_GAP else 0.dp).fillMaxWidth(),
        shape = shape,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainer,
    ) { Column(content = content) }
}

/** A section's title above its group, read by TalkBack as a heading. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}
