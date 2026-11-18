package org.mudlet.android.ui.scripts

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.ui.ansiColor

/** The names of ANSI colours 0 to 15, as the desktop's colour trigger dialog gives them. */
private val ANSI_NAMES = listOf(
    R.string.ansi_black, R.string.ansi_red, R.string.ansi_green, R.string.ansi_yellow,
    R.string.ansi_blue, R.string.ansi_magenta, R.string.ansi_cyan, R.string.ansi_white,
    R.string.ansi_light_black, R.string.ansi_light_red, R.string.ansi_light_green, R.string.ansi_light_yellow,
    R.string.ansi_light_blue, R.string.ansi_light_magenta, R.string.ansi_light_cyan, R.string.ansi_light_white,
)

/** The first ANSI colour past the named 16. */
private const val FIRST_NUMBERED = 16

/** A side of a colour pattern: its button's label and its sheet's title. */
private enum class Side(@param:StringRes val button: Int, @param:StringRes val title: Int) {
    TEXT(R.string.scripts_color_text, R.string.scripts_color_text_title),
    BACKGROUND(R.string.scripts_color_background, R.string.scripts_color_background_title),
}

private fun ColorPattern.on(side: Side) = if (side == Side.TEXT) foreground else background

private fun ColorPattern.with(side: Side, color: Int) = if (side == Side.TEXT) copy(foreground = color) else copy(background = color)

/**
 * Colour pattern [number]'s two sides, each a button that opens the sheet picking it, as the desktop editor's two colour
 * buttons do; under them, while both match any colour, why it will not run.
 */
@Composable
internal fun ColorPatternFields(number: Int, pattern: ColorPattern, modifier: Modifier, onPattern: (ColorPattern) -> Unit) {
    var picking by rememberSaveable { mutableStateOf<Side?>(null) }
    Column(modifier) {
        for (side in Side.entries) SideButton(side.button, pattern.on(side)) { picking = side }
        if (pattern.matchesNothing) {
            Text(stringResource(R.string.scripts_color_none), Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    val side = picking ?: return
    ColorSheet(stringResource(side.title, number), pattern.on(side), onDismiss = { picking = null }) { color ->
        picking = null
        onPattern(pattern.with(side, color))
    }
}

@Composable
private fun SideButton(@StringRes label: Int, color: Int, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Swatch(color)
        Text(stringResource(label, colorName(color)), Modifier.weight(1f).padding(start = 12.dp))
    }
}

/** What [color] goes by: any, the game's default, a named one with its number, or its number alone. */
@Composable
private fun colorName(color: Int): String = when (color) {
    ColorPattern.ANY_COLOR -> stringResource(R.string.scripts_color_any)
    ColorPattern.DEFAULT_COLOR -> stringResource(R.string.scripts_color_default)
    in ANSI_NAMES.indices -> stringResource(R.string.scripts_color_named, stringResource(ANSI_NAMES[color]), color)
    else -> stringResource(R.string.scripts_color_number, color)
}

/** A dot in [color] as the console draws it, outlined so black shows on a dark sheet; none for any or the default. */
@Composable
private fun Swatch(color: Int) {
    val fill = if (color in 0..ColorPattern.MAX_ANSI) ansiColor(color) else Color.Transparent
    Box(Modifier.size(24.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).background(fill, CircleShape))
}

/**
 * The sheet that picks a side's colour, [current] selected: any, the game's default, one of the 16 named, or a number
 * from 16 to 255 typed, as the desktop dialog's "More colors" offers them.
 */
@Composable
private fun ColorSheet(title: String, current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    TitledSheet(title, stringResource(R.string.scripts_color_close), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Column(Modifier.selectableGroup()) {
                for (color in listOf(ColorPattern.ANY_COLOR, ColorPattern.DEFAULT_COLOR) + ANSI_NAMES.indices) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(color == current, role = Role.RadioButton) { onPick(color) }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(color == current, onClick = null)
                        Box(Modifier.padding(start = 16.dp)) { Swatch(color) }
                        val name = when (color) {
                            ColorPattern.ANY_COLOR -> stringResource(R.string.scripts_color_any_choice)
                            ColorPattern.DEFAULT_COLOR -> stringResource(R.string.scripts_color_default_choice)
                            else -> colorName(color)
                        }
                        Text(name, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            val numbered = FIRST_NUMBERED..ColorPattern.MAX_ANSI
            var other by rememberSaveable { mutableIntStateOf(current.takeIf { it in numbered } ?: FIRST_NUMBERED) }
            // Not kept over a rotation, as the field's own text is not: while that is no colour, Use is off
            var typed by remember { mutableStateOf(true) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(other)
                NumberField(stringResource(R.string.scripts_color_other), other, numbered, Modifier.weight(1f).padding(horizontal = 16.dp), onInvalid = { typed = false }) {
                    other = it
                    typed = true
                }
                TextButton({ onPick(other) }, enabled = typed) { Text(stringResource(R.string.scripts_color_use)) }
            }
        }
    }
}
