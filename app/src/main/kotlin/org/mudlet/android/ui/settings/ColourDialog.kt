package org.mudlet.android.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import kotlin.math.roundToInt

/** What a colour is seen with in the dialog's preview: [other], the background it is drawn on or, for a background, the text drawn on it. */
internal class ColourPreview(val other: Int, val isBackground: Boolean)

// Each colour's name: Mudlet's sixteen ANSI colours, then two greys between its black, grey, silver and white
private val PALETTE: Map<Int, Int> by lazy {
    val greys = listOf(0x404040 to R.string.settings_colour_dark_grey, 0xD3D3D3 to R.string.settings_colour_light_grey)
    (Settings.ansiColours.map { it.mudletDefault }.zip(ANSI_COLOUR_LABELS) + greys).toMap()
}

private val HSB_SAVER = listSaver<Hsb, Float>({ listOf(it.hue, it.saturation, it.brightness) }, { Hsb(it[0], it[1], it[2]) })

private val HUES = (0..6).map { rgbColour(Hsb(it * 60f, 1f, 1f).toRgb()) }

private val CHANNELS = listOf(R.string.settings_colour_red_amount to 16, R.string.settings_colour_green_amount to 8, R.string.settings_colour_blue_amount to 0)

/**
 * Colours to tap, with [mudlets] among them; a saturation and brightness square and a hue bar to pick any other by
 * touch; the colour as "#rrggbb" to type; and, folded, a slider each for red, green and blue. [onPick] takes it as OK
 * is pressed. [preview] shows it beside what it is seen with.
 */
@Composable
internal fun ColourDialog(title: String, initial: Int, mudlets: Int, preview: ColourPreview, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var rgb by remember { mutableIntStateOf(initial) }
    // Kept apart from rgb, which loses a grey's hue and black's saturation
    var hsb by rememberSaveable(stateSaver = HSB_SAVER) { mutableStateOf(Hsb.of(initial)) }
    // What is typed, kept apart from rgb while it is not yet a whole colour
    var typed by rememberSaveable { mutableStateOf(Settings.colourName(initial)) }
    var fineTuning by rememberSaveable { mutableStateOf(false) }
    fun take(colour: Int) {
        rgb = colour
        hsb = Hsb.of(colour, hsb)
        typed = Settings.colourName(colour)
    }
    fun takeHsb(colour: Hsb) {
        hsb = colour
        rgb = colour.toRgb()
        typed = Settings.colourName(rgb)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // OK takes only a whole colour, never the last one picked while the typed name is not one
        confirmButton = {
            TextButton({ onPick(rgb) }, enabled = true) { Text(stringResource(R.string.settings_colour_ok)) }
        },
        dismissButton = { TextButton({ onPick(rgb) }) { Text(stringResource(R.string.settings_choice_cancel)) } },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Preview(rgb, preview)
                Heading(stringResource(R.string.settings_colour_quick_picks))
                QuickPicks(rgb, mudlets, ::take)
                Heading(stringResource(R.string.settings_colour_custom))
                SaturationBrightness(hsb, ::takeHsb)
                HueBar(hsb) { takeHsb(hsb.copy(hue = it)) }
                OutlinedTextField(
                    typed,
                    { text ->
                        typed = text
                        Settings.parseColourName(text)?.let {
                            rgb = it
                            hsb = Hsb.of(it, hsb)
                        }
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_colour_name)) },
                    isError = Settings.parseColourName(typed) == null,
                    supportingText = { Text(stringResource(R.string.settings_colour_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                )
                FineTune(fineTuning, { fineTuning = !fineTuning }) {
                    CHANNELS.forEach { (name, shift) ->
                        val amount = (rgb shr shift) and 0xFF
                        val label = stringResource(name)
                        Text(stringResource(R.string.settings_colour_amount, label, amount), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = amount.toFloat(),
                            onValueChange = { take((rgb and (0xFF shl shift).inv()) or (it.roundToInt() shl shift)) },
                            modifier = Modifier.semantics {
                                contentDescription = label
                                stateDescription = amount.toString()
                            },
                            valueRange = 0f..255f,
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun Swatch(rgb: Int, size: Dp = 32.dp, border: Dp = 1.dp) {
    Box(
        Modifier.size(size).clip(MaterialTheme.shapes.small).background(rgbColour(rgb))
            .border(border, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
    )
}

// Hidden from TalkBack, which reads the colour from the hex field, and from the contrast check, as a colour that can
// barely be seen against the other is what the preview is there to show
@Composable
private fun Preview(rgb: Int, preview: ColourPreview) {
    Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
        Swatch(rgb, 48.dp)
        val (text, background) = if (preview.isBackground) preview.other to rgb else rgb to preview.other
        Text(
            stringResource(R.string.settings_text_size_sample),
            Modifier.padding(start = 8.dp).weight(1f).background(rgbColour(background)).padding(8.dp),
            color = rgbColour(text),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
}

/** A radio button for each colour, read by its name; the one that is [rgb] is marked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickPicks(rgb: Int, mudlets: Int, onPick: (Int) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (pick in quickPicks(PALETTE.keys.toList(), mudlets)) {
            val selected = false
            val name = PALETTE[pick]?.let { stringResource(it) }
            val description = when {
                pick != mudlets -> name.orEmpty()
                name != null -> stringResource(R.string.settings_colour_mudlets_named, name)
                else -> stringResource(R.string.settings_colour_mudlets)
            }
            Box(
                Modifier.size(48.dp).selectable(selected, role = Role.RadioButton) { onPick(pick) }.semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Swatch(pick, 40.dp, if (selected) 3.dp else 1.dp)
                if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = if (darkMarkReads(pick)) Color.Black else Color.White)
            }
        }
    }
}

/**
 * Saturation across, brightness down, for the hue [hsb] has. TalkBack, which cannot follow a finger over a square,
 * reads both values and has actions to step each.
 */
@Composable
private fun SaturationBrightness(hsb: Hsb, onChange: (Hsb) -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val current by rememberUpdatedState(hsb)
    val change by rememberUpdatedState(onChange)
    val label = stringResource(R.string.settings_colour_saturation_brightness)
    val state = stringResource(R.string.settings_colour_saturation_brightness_value, (hsb.saturation * 100).roundToInt(), (hsb.brightness * 100).roundToInt())
    val actions = listOf(
        R.string.settings_colour_more_saturated to { h: Hsb -> h.stepSaturation(1) },
        R.string.settings_colour_less_saturated to { h: Hsb -> h.stepSaturation(-1) },
        R.string.settings_colour_brighter to { h: Hsb -> h.stepBrightness(1) },
        R.string.settings_colour_darker to { h: Hsb -> h.stepBrightness(-1) },
    ).map { (name, step) ->
        CustomAccessibilityAction(stringResource(name)) {
            change(step(current))
            true
        }
    }
    val pure = rgbColour(Hsb(hsb.hue, 1f, 1f).toRgb())
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).clip(MaterialTheme.shapes.small)
            .pointerInput(rtl) {
                followTouch { }
            }
            .semantics {
                contentDescription = label
                stateDescription = state
                customActions = actions
            },
    ) {
        drawRect(Brush.horizontalGradient(if (rtl) listOf(pure, Color.White) else listOf(Color.White, pure)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val across = if (rtl) 1 - hsb.saturation else hsb.saturation
        thumb(Offset(across * size.width, (1 - hsb.brightness) * size.height))
    }
}

/** A bar of every hue, which TalkBack reads and changes as a slider. */
@Composable
private fun HueBar(hsb: Hsb, onHue: (Float) -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val change by rememberUpdatedState(onHue)
    val label = stringResource(R.string.settings_colour_hue)
    val degrees = hsb.hue.roundToInt()
    val state = pluralStringResource(R.plurals.settings_colour_hue_value, degrees, degrees)
    Canvas(
        Modifier.fillMaxWidth().height(48.dp)
            .pointerInput(rtl) { followTouch { at -> change(fraction(at.x, size.width, false) * 360f) } }
            .semantics {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(hsb.hue, 0f..360f)
                setProgress { _ -> true }
            },
    ) {
        val bar = 24.dp.toPx()
        drawRoundRect(
            Brush.horizontalGradient(if (rtl) HUES.reversed() else HUES),
            Offset(0f, (size.height - bar) / 2),
            Size(size.width, bar),
            CornerRadius(bar / 2),
        )
        val across = if (rtl) 1 - hsb.hue / 360f else hsb.hue / 360f
        thumb(Offset(across * size.width, size.height / 2))
    }
}

/** A heading that folds away [content], read by TalkBack as one heading button with its state, as the profile editor's are. */
@Composable
private fun FineTune(open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val state = stringResource(if (open) R.string.profile_section_expanded else R.string.profile_section_collapsed)
    val action = stringResource(if (open) R.string.profile_section_hide else R.string.profile_section_show)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClickLabel = action, role = Role.Button, onClick = onToggle)
            .semantics {
                heading()
                stateDescription = state
            }
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_colour_fine_tune), style = MaterialTheme.typography.titleSmall)
            if (!open) {
                Text(stringResource(R.string.settings_colour_fine_tune_summary), maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // A triangle, not a chevron, as it means the same either way round in a right-to-left layout
        Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, Modifier.size(24.dp).rotate(if (open) 180f else 0f))
    }
    if (open) content()
}

private fun rgbColour(rgb: Int) = Color(0xFF000000 or (rgb.toLong() and 0xFFFFFF))

// From 0 at the start of [length] to 1 at its end, the start being the right in a right-to-left layout
private fun fraction(at: Float, length: Int, rtl: Boolean): Float {
    val f = (at / length).coerceIn(0f, 1f)
    return if (rtl) 1 - f else f
}

// Takes the touch as it lands, before the dialog's scrolling can, so a drag up or down moves the thumb, not the dialog
private suspend fun PointerInputScope.followTouch(onAt: (Offset) -> Unit) = awaitEachGesture {
    val down = awaitFirstDown()
    down.consume()
    onAt(down.position)
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) break
        change.consume()
        onAt(change.position)
    }
}

// White on a dark ring, so it shows on every colour
private fun DrawScope.thumb(at: Offset) {
    val radius = 10.dp.toPx()
    drawCircle(Color.Black, radius, at, style = Stroke(4.dp.toPx()))
    drawCircle(Color.White, radius, at, style = Stroke(2.dp.toPx()))
}
