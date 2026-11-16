package org.mudlet.android.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.protocol.BarButton
import org.mudlet.android.protocol.BarCell
import org.mudlet.android.protocol.BarSide
import org.mudlet.android.protocol.ButtonBarLayout
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
    val bars = ScriptWindowsClient.buttonBars
    BoxWithConstraints(modifier) {
        val fitted = WindowLayout.fitted(ScriptWindowsClient.layoutMode, maxWidth.value)
        val sides = remember(bars, fitted) { ButtonBarLayout.of(bars, fitted).groupBy { it.side } }
        val maxRowHeight = maxHeight * MAX_ROW_SHARE
        Column(Modifier.fillMaxSize()) {
            sides[BarSide.TOP]?.let { BarRow(it, maxRowHeight) }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                sides[BarSide.START]?.let { BarStrip(it) }
                content(Modifier.weight(1f).fillMaxHeight(), fitted)
                sides[BarSide.END]?.let { BarStrip(it) }
            }
            sides[BarSide.BOTTOM]?.let { BarRow(it, maxRowHeight) }
        }
    }
}

/** Bars side by side in a row across the game area, at most [maxHeight] high, which scrolls when they do not fit. */
@Composable
private fun BarRow(bars: List<PlacedBar>, maxHeight: Dp) {
    // Each scroll on a node of its own, as a node takes one scroll action
    Box(Modifier.fillMaxWidth().heightIn(max = maxHeight).background(MaterialTheme.colorScheme.surfaceContainer).barsSemantics().horizontalScroll(rememberScrollState())) {
        Row(Modifier.verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (bar in bars) key(bar.bar.id) { BarGrid(bar) }
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

/** Bars one above the other in a strip at a side of the game area, which scrolls when they do not fit. */
@Composable
private fun BarStrip(bars: List<PlacedBar>) {
    Box(Modifier.fillMaxHeight().widthIn(max = MAX_STRIP_WIDTH).background(MaterialTheme.colorScheme.surfaceContainer).barsSemantics().horizontalScroll(rememberScrollState())) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (bar in bars) key(bar.bar.id) { BarGrid(bar) }
        }
    }
}

/**
 * A bar's buttons in its grid: a horizontal one as columns, each as wide as its widest button, a vertical one as rows
 * of equal places. TalkBack reads it as one group named by the bar.
 */
@Composable
private fun BarGrid(placed: PlacedBar) {
    val description = stringResource(R.string.bar_description, placed.bar.name)
    val cells = remember(placed) { placed.cells.associateBy { it.row to it.column } }
    val group = Modifier.semantics {
        contentDescription = description
        isTraversalGroup = true
    }

    if (placed.horizontal) {
        Row(group) {
            for (column in 0 until placed.columns) {
                Column(Modifier.width(IntrinsicSize.Max)) {
                    for (row in 0 until placed.rows) GridPlace(cells[row to column], placed, Modifier.fillMaxWidth())
                }
            }
        }
    } else {
        Column(group.width(IntrinsicSize.Max)) {
            for (row in 0 until placed.rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (column in 0 until placed.columns) GridPlace(cells[row to column], placed, Modifier.weight(1f))
                }
            }
        }
    }
}

/** A place of [placed]'s grid: [cell]'s button, or room for one. */
@Composable
private fun GridPlace(cell: BarCell?, placed: PlacedBar, modifier: Modifier) {
    if (cell == null) {
        Spacer(modifier.size(MIN_BUTTON_SIZE))
    } else {
        key(cell.button.id) {
            val unnamed = stringResource(R.string.bar_button_unnamed, placed.cells.indexOf(cell) + 1, placed.bar.name)
            BarButtonView(cell.button, unnamed, modifier)
        }
    }
}

/**
 * A button of a bar: its icon and name, or [unnamed] for TalkBack when it has no name. A push-down one is a switch,
 * on while down, with a tick; a folder opens a menu of its entries. Off while there is no engine to tell.
 */
@Composable
private fun BarButtonView(button: BarButton, unnamed: String, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val engine = EngineClient.enginePid > 0
    val colors = MaterialTheme.colorScheme
    val down = button.pushDown && button.checked
    val action = when {
        button.menu -> Modifier.clickable(enabled = engine, onClickLabel = stringResource(R.string.bar_open_menu), role = Role.Button) { menuOpen = true }
        button.pushDown -> Modifier.toggleable(value = button.checked, enabled = engine, role = Role.Switch) { ScriptWindowsClient.pressButton(button.id) }
        else -> Modifier.clickable(enabled = engine, role = Role.Button) { ScriptWindowsClient.pressButton(button.id) }
    }
    val name = if (button.name.isEmpty()) Modifier.semantics { contentDescription = unnamed } else Modifier
    Box(modifier.padding(2.dp)) {
        Row(
            Modifier.fillMaxWidth().sizeIn(minWidth = MIN_BUTTON_SIZE, minHeight = MIN_BUTTON_SIZE).clip(MaterialTheme.shapes.small)
                .background(if (down) colors.primary else colors.surfaceVariant).then(action).then(name).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val content = if (down) colors.onPrimary else colors.onSurfaceVariant
            if (down) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = content)
            if (button.icon.isNotEmpty()) EngineIcon(button.icon)
            if (button.name.isNotEmpty()) Text(button.name, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (button.menu) Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, tint = content)
        }
        if (button.menu) {
            FolderMenu(button, unnamed, expanded = menuOpen) { picked ->
                menuOpen = false
                // As the GUI: the entry picked runs, then the folder's own script, once its menu has closed
                picked?.let { ScriptWindowsClient.pressButton(it.id) }
                ScriptWindowsClient.pressButton(button.id)
            }
        }
    }
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
