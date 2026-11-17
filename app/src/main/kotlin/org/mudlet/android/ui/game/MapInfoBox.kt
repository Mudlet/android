package org.mudlet.android.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.MapInfo

/**
 * A map's info box (docs/architecture.md, "The map info"): what the profile's map info contributors say of the room, the
 * built-in ones and a script's `registerMapInfo()`, each in the colour, bold and italic the engine gives it, over the
 * box's own colour, as the desktop paints them. The engine leaves the box out of the map's picture, so here it is text:
 * at the player's font size, still while a finger drags the map under it, and read by TalkBack as one. Each part is
 * trimmed, as the desktop trims it, which drops the line break the built-in ones end with; one of nothing but space is
 * left out.
 */
@Composable
internal fun MapInfoBox(info: MapInfo, modifier: Modifier = Modifier) {
    val lines = info.lines.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
    if (lines.isEmpty()) return
    val description = stringResource(R.string.map_info, lines.joinToString("\n") { it.text })
    Column(
        modifier
            .padding(INFO_MARGIN)
            .clearAndSetSemantics { contentDescription = description }
            .background(Color(info.background), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        for (line in lines) {
            Text(
                line.text,
                color = line.color?.let(::Color) ?: LocalContentColor.current,
                fontWeight = if (line.bold) FontWeight.Bold else null,
                fontStyle = if (line.italic) FontStyle.Italic else null,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private val INFO_MARGIN = 4.dp
