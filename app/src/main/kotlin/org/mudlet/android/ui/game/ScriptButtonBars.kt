package org.mudlet.android.ui.game

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mudlet.android.R
import org.mudlet.android.protocol.BarButton
import org.mudlet.android.protocol.BarCell
import org.mudlet.android.protocol.BarSide
import org.mudlet.android.protocol.ButtonBarLayout
import org.mudlet.android.protocol.ButtonState
import org.mudlet.android.protocol.ButtonStyle
import org.mudlet.android.protocol.Edges
import org.mudlet.android.protocol.LabelFill
import org.mudlet.android.protocol.LabelStyle
import org.mudlet.android.protocol.PlacedBar
import org.mudlet.android.protocol.WindowLayout
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.ScriptWindowsClient
import org.mudlet.android.ui.ScriptWindowsClient.ImageRequest

// Button bars are read after the script windows and before the last announcement (docs/architecture.md, "Button bars
// and commands")
internal const val BARS_TRAVERSAL_INDEX = WINDOWS_TRAVERSAL_INDEX + 1f

// A button, and an empty place of a bar's grid, is never smaller than this
private val MIN_BUTTON_SIZE = 48.dp
private val ICON_SIZE = 24.dp

// A button's room around its content and around itself, in the sheet's pixels, where its style sheet sets none
private val DEFAULT_PADDING = Edges(0f, 12f, 0f, 12f)
private val DEFAULT_MARGIN = Edges(2f, 2f, 2f, 2f)
private val EMPTY_STYLE = LabelStyle()

// A strip at the side of the game area takes no more of its width than this, and scrolls instead
private val MAX_STRIP_WIDTH = 160.dp

// The rows above and below the game area each take no more of its height than this share, and scroll instead
private const val MAX_ROW_SHARE = 0.25f

/**
 * The game area with the profile's button bars around it (docs/architecture.md, "Button bars and commands"): rows
 * above and below [content], and strips at its sides, as [ButtonBarLayout] places them. [content] is drawn with the
 * modifier that gives it what the bars leave, so Mudlet's main window is that, as the GUI's bars take their room from
 * its main console, and with whether the whole area is fitted. A tap on a button sends `pressButton`; the engine's
 * next `buttonBars` shows what it did.
 */
@Composable
fun ScriptButtonBars(modifier: Modifier = Modifier, content: @Composable (Modifier, fitted: Boolean) -> Unit) {
    val bars = EngineClient.front.windows.buttonBars
    BoxWithConstraints(modifier) {
        val fitted = WindowLayout.fitted(ScriptWindowsClient.layoutMode, maxWidth.value)
        val sides = remember(bars, fitted) { ButtonBarLayout.of(bars, fitted).groupBy { it.side } }
        val maxRowHeight = maxHeight * MAX_ROW_SHARE
        // No button is bigger than the game area, so that what a sheet's lengths or a long name ask of a bar's layout
        // has a bound
        val area = DpSize(maxWidth.coerceAtLeast(MIN_BUTTON_SIZE), maxHeight.coerceAtLeast(MIN_BUTTON_SIZE))
        Column(Modifier.fillMaxSize()) {
            sides[BarSide.TOP]?.let { BarRow(it, maxRowHeight, area) }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                sides[BarSide.START]?.let { BarStrip(it, area) }
                content(Modifier.weight(1f).fillMaxHeight(), fitted)
                sides[BarSide.END]?.let { BarStrip(it, area) }
            }
            sides[BarSide.BOTTOM]?.let { BarRow(it, maxRowHeight, area) }
        }
    }
}

/**
 * Bars side by side in a row across the game area, at most [maxHeight] high, which scrolls when they do not fit; a
 * button of theirs is at most [area] big.
 */
@Composable
private fun BarRow(bars: List<PlacedBar>, maxHeight: Dp, area: DpSize) {
    // Each scroll on a node of its own, as a node takes one scroll action
    Box(Modifier.fillMaxWidth().heightIn(max = maxHeight).background(MaterialTheme.colorScheme.surfaceContainer).barsSemantics().horizontalScroll(rememberScrollState())) {
        Row(Modifier.verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (bar in bars) key(bar.bar.id) { BarGrid(bar, area) }
        }
    }
}

/**
 * A row or strip of bars is read after the script windows. The index is on the row or strip, beside the console and
 * the windows, not on its bars: TalkBack orders by index only within a traversal group, and each scroll is one.
 */
private fun Modifier.barsSemantics(): Modifier = semantics {
    isTraversalGroup = true
    traversalIndex = BARS_TRAVERSAL_INDEX
}

/**
 * Bars one above the other in a strip at a side of the game area, which scrolls when they do not fit; a button of
 * theirs is at most [area] big.
 */
@Composable
private fun BarStrip(bars: List<PlacedBar>, area: DpSize) {
    Box(Modifier.fillMaxHeight().widthIn(max = MAX_STRIP_WIDTH).background(MaterialTheme.colorScheme.surfaceContainer).barsSemantics().horizontalScroll(rememberScrollState())) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (bar in bars) key(bar.bar.id) { BarGrid(bar, area) }
        }
    }
}

/**
 * A bar's buttons in its grid: a horizontal one as columns, each as wide as its widest button, a vertical one as rows
 * of equal places. A button is at most [area] big. TalkBack reads it as one group named by the bar.
 */
@Composable
private fun BarGrid(placed: PlacedBar, area: DpSize) {
    val description = stringResource(R.string.bar_description, placed.bar.name)
    val cells = remember(placed) { placed.cells.associateBy { it.row to it.column } }
    val style = remember(placed.bar.styleSheet) { ButtonStyle.bar(placed.bar.styleSheet) }
    val backgroundImage = engineImage(style.backgroundImage?.let { ImageRequest(it) })
    val borderImage = engineImage(style.borderImage?.let { ImageRequest(it) })
    val group = Modifier.semantics {
        contentDescription = description
        isTraversalGroup = true
    }.barFrame(style, MaterialTheme.colorScheme.onSurface, backgroundImage, borderImage, LocalDensity.current)

    if (placed.horizontal) {
        Row(group) {
            for (column in 0 until placed.columns) {
                Column(Modifier.widthOfWidest()) {
                    for (row in 0 until placed.rows) GridPlace(cells[row to column], placed, style, area, Modifier.fillMaxWidth())
                }
            }
        }
    } else {
        Column(group.widthOfWidest()) {
            for (row in 0 until placed.rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (column in 0 until placed.columns) GridPlace(cells[row to column], placed, style, area, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * As wide as the widest of its content asks to be, as `width(IntrinsicSize.Max)`, but no wider than Compose can
 * measure: that modifier throws past 2^18 pixels, which the places of a vertical bar add up to when a script gives it
 * columns enough. Such a bar's places are narrower than their buttons ask.
 */
private fun Modifier.widthOfWidest(): Modifier = layout { measurable, constraints ->
    val width = constraints.constrainWidth(measurable.maxIntrinsicWidth(constraints.maxHeight).coerceAtLeast(0))
    val placeable = measurable.measure(Constraints.fitPrioritizingWidth(width, width, constraints.minHeight, constraints.maxHeight))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** A place of [placed]'s grid: [cell]'s button, at most [area] big, or room for one, on the bar drawn as [barStyle]. */
@Composable
private fun GridPlace(cell: BarCell?, placed: PlacedBar, barStyle: LabelStyle, area: DpSize, modifier: Modifier) {
    if (cell == null) {
        Spacer(modifier.size(MIN_BUTTON_SIZE))
    } else {
        key(cell.button.id) {
            val unnamed = stringResource(R.string.bar_button_unnamed, placed.cells.indexOf(cell) + 1, placed.bar.name)
            BarButtonView(cell.button, placed.bar.styleSheet, barStyle, unnamed, area, modifier)
        }
    }
}

/**
 * A button of a bar: its icon and name, or [unnamed] for TalkBack when it has no name. A push-down one is a switch,
 * on while down, with a tick; a folder opens a menu of its entries. Off while there is no engine to tell. Drawn with
 * its own style sheet over its bar's [barSheet] (docs/phase-f.md, "Button bars and commands"), on the bar drawn as
 * [barStyle], and the app's own look where they set none. At most [area] big, whatever its sheets and its name ask:
 * its margin, border and padding shrink to leave its content the least size of a button, and a longer name is cut.
 */
@Composable
private fun BarButtonView(button: BarButton, barSheet: String, barStyle: LabelStyle, unnamed: String, area: DpSize, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val engine = EngineClient.enginePid > 0
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val down = button.pushDown && button.checked
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val look = remember(barSheet, button.styleSheet, down, pressed, menuOpen, engine) {
        ButtonStyle.of(barSheet, button.styleSheet, ButtonState(checked = down, pressed = pressed || if (Build.VERSION.SDK_INT >= 34) false else menuOpen, enabled = engine))
    }
    val style = look.style
    val action = when {
        button.menu -> Modifier.clickable(enabled = engine, onClickLabel = stringResource(R.string.bar_open_menu), role = Role.Button, interactionSource = interaction) { menuOpen = true }
        button.pushDown -> Modifier.toggleable(value = button.checked, enabled = engine, role = Role.Switch, interactionSource = interaction) { EngineClient.front.windows.pressButton(button.id) }
        else -> Modifier.clickable(enabled = engine, role = Role.Button, interactionSource = interaction) { EngineClient.front.windows.pressButton(button.id) }
    }
    val name = if (button.name.isEmpty()) Modifier.semantics { contentDescription = unnamed } else Modifier
    // Where the sheets draw a frame of their own, the bar shows through what they do not fill, as on the desktop
    val (appColour, appText) = when {
        !look.appFill -> colors.surfaceContainer to colors.onSurface
        down -> colors.primary to colors.onPrimary
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    val fill = style.background ?: LabelFill.Solid(appColour.toArgb()).takeIf { if (Build.VERSION.SDK_INT >= 34) look.appFill else true }
    val content = Color(look.text(barStyle, appColour.toArgb(), appText.toArgb()))
    val frame = style.copy(borderColor = style.borderColor ?: content.toArgb())
    val backgroundImage = engineImage(style.backgroundImage?.let { ImageRequest(it) })
    val borderImage = engineImage(style.borderImage?.let { ImageRequest(it) })
    val room = remember(look, area) {
        look.room(area.width.value - MIN_BUTTON_SIZE.value, area.height.value - MIN_BUTTON_SIZE.value, DEFAULT_PADDING, DEFAULT_MARGIN)
    }
    val border = room.border
    val styled = !look.appFill || style.backgroundImage != null
    val shape = if (styled) RoundedCornerShape(style.borderRadius.dp) else MaterialTheme.shapes.small
    val inner = room.padding + border
    // One of the sheet's pixels to a dp, as a label's at the scale of a 1:1 window; its font size to an sp, so that a
    // button's name follows the system's font size as the app's own buttons do
    val scale = density.density
    // The theme's line is a fixed 24 sp high, which would cut the top and bottom off the letters of a larger size
    val textStyle = LocalTextStyle.current.let { if (style.fontSizePx != null) it.copy(lineHeight = TextUnit.Unspecified) else it }
    Box(modifier.sizeIn(maxWidth = area.width, maxHeight = area.height).edgePadding(room.margin, scale, scale, density)) {
        Row(
            Modifier.fillMaxWidth().sizeIn(minWidth = MIN_BUTTON_SIZE, minHeight = MIN_BUTTON_SIZE).clip(shape)
                .drawBehind { drawLabelFrame(fill, frame, border * scale, style.borderRadius * scale, scale, backgroundImage, borderImage) }
                .then(action).then(name).edgePadding(inner, scale, scale, density),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The tick stays whatever the sheet's colours, so that a down button still looks it
            if (down) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = content)
            if (button.icon.isNotEmpty()) EngineIcon(button.icon)
            if (button.name.isNotEmpty()) {
                Text(
                    button.name,
                    // A name too long for the button gives way to its tick, icon and arrow. The bar's grid asks the
                    // name how wide it would be with no end to its room, and Compose throws on widths that add up
                    // past 2^24
                    Modifier.weight(1f, fill = false).widthIn(max = area.width),
                    color = content,
                    fontSize = style.fontSizePx?.sp ?: TextUnit.Unspecified,
                    fontWeight = style.fontWeight?.let { FontWeight(it.coerceIn(1, 1000)) },
                    fontStyle = style.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
                    fontFamily = style.fontFamily?.let(::fontFamily),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = textStyle,
                )
            }
            if (button.menu) Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, tint = content)
        }
        if (button.menu) {
            FolderMenu(button, unnamed, expanded = menuOpen) { picked ->
                menuOpen = false
                // As the GUI: the entry picked runs, then the folder's own script, once its menu has closed
                picked?.let { EngineClient.front.windows.pressButton(it.id) }
                EngineClient.front.windows.pressButton(button.id)
            }
        }
    }
}

/**
 * [style], what a bar draws of its own style sheet as [ButtonStyle.bar] reads it: its background, images and border
 * around its buttons, the border in [borderColour] where the sheet names none.
 */
private fun Modifier.barFrame(style: LabelStyle, borderColour: Color, backgroundImage: ImageBitmap?, borderImage: ImageBitmap?, density: Density): Modifier {
    if (style == EMPTY_STYLE) return this
    val scale = density.density
    val frame = style.copy(borderColor = style.borderColor ?: style.color ?: borderColour.toArgb())
    val border = style.drawnBorderWidth
    return clip(RoundedCornerShape(style.borderRadius.dp))
        .drawBehind { drawLabelFrame(style.background, frame, border * scale, style.borderRadius * scale, scale, backgroundImage, borderImage) }
        .edgePadding(Edges(border, border, border, border), scale, scale, density)
}

/**
 * The menu of the folder [folder]: its entries, a folder among them opening its own in place, with a way back.
 * [onClose] gets the entry picked, or null when the menu was closed without one.
 */
@Composable
private fun FolderMenu(folder: BarButton, folderName: String, expanded: Boolean, onClose: (BarButton?) -> Unit) {
    // The folders opened within it, from the outermost
    var opened by remember(expanded) { mutableStateOf(emptyList<BarButton>()) }
    DropdownMenu(expanded, onDismissRequest = { onClose(null) }) {
        val current = opened.lastOrNull() ?: folder
        if (opened.isNotEmpty()) MenuBackItem { opened = opened.dropLast(1) }
        val parentName = current.name.ifEmpty { folderName }
        ButtonBarLayout.menuOf(current).forEachIndexed { i, entry ->
            val label = entry.name.ifEmpty { stringResource(R.string.bar_button_unnamed, i + 1, parentName) }
            if (entry.menu) {
                SubmenuItem(label) { opened = opened + entry }
            } else {
                val checked = entry.pushDown && entry.checked
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onClose(entry) },
                    modifier = if (entry.pushDown) Modifier.semantics { toggleableState = ToggleableState(entry.checked) } else Modifier,
                    leadingIcon = if (entry.icon.isNotEmpty()) {
                        { EngineIcon(entry.icon) }
                    } else {
                        null
                    },
                    trailingIcon = if (checked) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** A menu's entry that goes back from the submenu shown to the one it is in. */
@Composable
internal fun MenuBackItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_back)) },
        onClick = onClick,
        leadingIcon = { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null) },
    )
}

/** A menu's entry [label] that opens a submenu in its place. */
@Composable
internal fun SubmenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = { Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.menu_submenu)) },
    )
}

/** The icon file [path] of the engine's, at the size of an icon; room for it until it comes. Decorative. */
@Composable
internal fun EngineIcon(path: String) {
    val px = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    val image = engineImage(remember(path, px) { ImageRequest(path, px, px) })
    if (image != null) Image(image, contentDescription = null, Modifier.size(ICON_SIZE)) else Spacer(Modifier.size(ICON_SIZE))
}
