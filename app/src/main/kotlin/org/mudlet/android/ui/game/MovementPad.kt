package org.mudlet.android.ui.game

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.settings.AppSettings
import org.mudlet.android.ui.settings.ChoiceButtons
import org.mudlet.android.ui.settings.SettingEntry
import org.mudlet.android.ui.settings.settingsCatalogue

/** The movement pad's test tag. */
const val MOVEMENT_PAD_TAG = "movementPad"

/** Whether the device has a keyboard with letters ready to type on, which the movement pad stays away for. */
internal object HardwareKeyboard {
    /** Takes the place of the device's answer when not null: the tests' switch. */
    @VisibleForTesting
    var attachedOverride by mutableStateOf<Boolean?>(null)

    @Composable
    fun attached(): Boolean {
        val configuration = LocalConfiguration.current
        // A slid-in or folded-away keyboard is hidden; one on Bluetooth or USB never is
        val device = configuration.keyboard == Configuration.KEYBOARD_QWERTY && configuration.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
        return attachedOverride ?: device
    }
}

/**
 * Whether the movement pad shows on a game screen of [screen] pixels, whose game hides input or not ([hiddenInput]):
 * [MovementPadLayout.shown], with the player's setting and what the device has now.
 */
@Composable
internal fun movementPadShown(hiddenInput: Boolean, screen: IntSize): Boolean {
    val density = LocalDensity.current
    val keyboard = WindowInsets.ime
    // Derived, as the keyboard's height changes with each frame of its slide
    val keyboardUp by remember(keyboard, density) { derivedStateOf { keyboard.getBottom(density) > 0 } }
    return MovementPadLayout.shown(
        enabled = Settings.movementPad.value,
        hiddenInput = hiddenInput,
        hardwareKeyboard = HardwareKeyboard.attached(),
        keyboardUp = keyboardUp,
        widthDp = MovementPadLayout.dp(screen.width, density.density),
        heightDp = MovementPadLayout.dp(screen.height, density.density),
    )
}

/**
 * The movement pad in the player's [design] (docs/architecture.md, "The movement pad's designs"). [onPress] is a walk
 * the pad's way, [onHide] puts the pad away for good, [onFold] folds the Thumb pad into the button the game screen
 * shows for it, and [exits] are the ways out of the player's room the map has, for the Room exits pad. Each design has
 * the button that picks the design ([DesignButton]).
 */
@Composable
internal fun MovementPad(
    design: Settings.MovementPadDesign,
    onPress: (MovementKey) -> Unit,
    onHide: () -> Unit,
    onFold: () -> Unit,
    exits: Set<MovementKey>?,
    modifier: Modifier = Modifier,
) {
    when (design) {
        Settings.MovementPadDesign.CURRENT -> CurrentPad(onPress, onHide, modifier)
        Settings.MovementPadDesign.THUMB -> ThumbPad(onPress, onFold, modifier)
        Settings.MovementPadDesign.SWIPE -> SwipePad(onPress, onHide, modifier)
        Settings.MovementPadDesign.EXITS -> ExitsPad(exits, onPress, onHide, modifier)
    }
}

/**
 * The current pad: a key for each of [MovementPadLayout.rows] at the row's end, where the thumb of the hand that holds
 * the phone is, and at its start the button that puts the pad away ([onHide]) over the one that picks its design.
 */
@Composable
private fun CurrentPad(onPress: (MovementKey) -> Unit, onHide: () -> Unit, modifier: Modifier) {
    Row(modifier.testTag(MOVEMENT_PAD_TAG)) {
        Column(ReadTogether) {
            HideButton(onHide)
            DesignButton()
        }
        Spacer(Modifier.weight(1f))
        KeyGrid(onPress)
    }
}

/**
 * The Thumb pad: the current pad's keys at the edge of the player's side ([Settings.movementPadSide]), its middle key
 * walking the last way again, and beside them the buttons that fold it ([onFold]), move it to the other side and pick
 * the design.
 */
@Composable
private fun ThumbPad(onPress: (MovementKey) -> Unit, onFold: () -> Unit, modifier: Modifier) {
    val side = Settings.movementPadSide.value
    var last by rememberSaveable { mutableStateOf<MovementKey?>(null) }
    val walk = { key: MovementKey ->
        last = key
        onPress(key)
    }
    val keys = @Composable { KeyGrid(walk) { AgainKey(last, walk) } }
    val buttons = @Composable {
        Column {
            IconButton(onFold) { Icon(painterResource(R.drawable.ic_expand_more), stringResource(R.string.movement_pad_fold)) }
            SideButton(side)
            DesignButton()
        }
    }
    SideRow(side, modifier, keys, buttons)
}

/**
 * The Swipe pad: one strip at the edge of the player's side that a swipe across walks the swipe's way, and a tap lists
 * the twelve ways ([SwipeStrip]); beside it the buttons that put the pad away ([onHide]) and pick the design.
 */
@Composable
private fun SwipePad(onPress: (MovementKey) -> Unit, onHide: () -> Unit, modifier: Modifier) {
    val side = Settings.movementPadSide.value
    var last by rememberSaveable { mutableStateOf<MovementKey?>(null) }
    val strip = @Composable {
        SwipeStrip(last) { key ->
            last = key
            onPress(key)
        }
    }
    val buttons = @Composable {
        Column {
            HideButton(onHide)
            DesignButton()
        }
    }
    SideRow(side, modifier, strip, buttons)
}

/**
 * The Room exits pad: the current pad's keys for [exits], the ways out of the player's room the map has, each where it
 * is on the current pad, at the edge of the player's side; beside them the buttons that put the pad away ([onHide]),
 * pick the design and show all twelve keys. While the map does not know the room ([exits] null), or the player asked
 * for them, it has all twelve.
 */
@Composable
private fun ExitsPad(exits: Set<MovementKey>?, onPress: (MovementKey) -> Unit, onHide: () -> Unit, modifier: Modifier) {
    val side = Settings.movementPadSide.value
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) null else exits
    val keys = @Composable {
        if (shown?.isEmpty() == true) {
            Text(stringResource(R.string.movement_exits_none), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium)
        } else {
            KeyGrid(onPress, shown)
        }
    }
    val buttons = @Composable {
        Column {
            HideButton(onHide)
            DesignButton()
            if (exits != null) {
                if (all) {
                    IconButton({ all = false }) { Icon(painterResource(R.drawable.ic_expand_less), stringResource(R.string.movement_exits_room)) }
                } else {
                    IconButton({ all = true }) { Icon(painterResource(R.drawable.ic_expand_more), stringResource(R.string.movement_exits_all)) }
                }
            }
        }
    }
    SideRow(side, modifier, keys, buttons)
}

/**
 * A pad's [pad] at the edge of [side], left or right whatever the language's direction, with its [buttons] beside it on
 * the inner side. [pad] takes what room the buttons leave, and no more.
 */
@Composable
private fun SideRow(
    side: Settings.MovementPadSide,
    modifier: Modifier,
    pad: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
) {
    // Absolute: the children go left to right as given, and to the side's edge, in a language written from the right too
    val left = side == Settings.MovementPadSide.LEFT
    Row(
        modifier.testTag(MOVEMENT_PAD_TAG),
        horizontalArrangement = if (left) Arrangement.Absolute.Left else Arrangement.Absolute.Right,
    ) {
        if (left) {
            Box(Modifier.weight(1f, fill = false).then(ReadTogether)) { pad() }
            Box(ReadTogether) { buttons() }
        } else {
            Box(ReadTogether) { buttons() }
            Box(Modifier.weight(1f, fill = false).then(ReadTogether)) { pad() }
        }
    }
}

/**
 * The keys of [MovementPadLayout.rows], west on the left in every language as on a map, [middle] in their middle gap,
 * and only those of [shown] when not null, the others left as gaps so that each key stays where the thumb knows it.
 */
@Composable
private fun KeyGrid(onPress: (MovementKey) -> Unit, shown: Set<MovementKey>? = null, middle: (@Composable () -> Unit)? = null) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(ReadTogether) {
            MovementPadLayout.rows.forEachIndexed { r, row ->
                Row {
                    row.forEachIndexed { c, key ->
                        // A gap is a cell too
                        KeyCell {
                            if (key != null) {
                                if (shown == null || key in shown) PadKey(key, onPress)
                            } else if (middle != null && r == MovementPadLayout.MIDDLE_ROW && c == MovementPadLayout.MIDDLE_COLUMN) {
                                middle()
                            }
                        }
                    }
                }
            }
        }
    }
}

// TalkBack reads a pad's buttons together and its keys together, not row by row across the two
private val ReadTogether = Modifier.semantics { isTraversalGroup = true }

/** A key's cell: the button drawn is smaller than its cell, all of which takes its touch. */
@Composable
private fun KeyCell(content: @Composable () -> Unit) {
    Box(Modifier.size(MovementPadLayout.KEY_DP.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun PadKey(key: MovementKey, onPress: (MovementKey) -> Unit) {
    val look = key.look()
    FilledTonalIconButton({ onPress(key) }) {
        Icon(painterResource(look.icon), stringResource(look.name), Modifier.rotate(look.turn))
    }
}

/** The Thumb pad's middle key: it walks [last] again with [onPress], drawn as that way's key; before any walk it does nothing. */
@Composable
private fun AgainKey(last: MovementKey?, onPress: (MovementKey) -> Unit) {
    if (last == null) {
        FilledIconButton({}, enabled = false) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.movement_again_none)) }
        return
    }
    val look = last.look()
    FilledIconButton({ onPress(last) }) {
        Icon(painterResource(look.icon), stringResource(R.string.movement_again, stringResource(look.name)), Modifier.rotate(look.turn))
    }
}

/** The button that moves the pad from [side] to the other. */
@Composable
private fun SideButton(side: Settings.MovementPadSide) {
    val toLeft = side == Settings.MovementPadSide.RIGHT
    IconButton({ Settings.movementPadSide.set(if (toLeft) Settings.MovementPadSide.LEFT else Settings.MovementPadSide.RIGHT) }) {
        Icon(painterResource(R.drawable.ic_swap_horiz), stringResource(if (toLeft) R.string.movement_pad_to_left else R.string.movement_pad_to_right))
    }
}

/** The button that puts the pad away for good, with [onHide]. */
@Composable
private fun HideButton(onHide: () -> Unit) {
    IconButton(onHide) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.movement_pad_hide)) }
}

private val designChoice by lazy {
    settingsCatalogue.filterIsInstance<SettingEntry.Choice<*>>().single { it.setting == Settings.movementPadDesign }
}

/**
 * The button that lists the pad's designs as Settings > Experimental's "Movement pad design" does, marked experimental;
 * a pick changes the setting, which the game screen follows at once.
 */
@Composable
private fun DesignButton() {
    var picking by rememberSaveable { mutableStateOf(false) }
    IconButton({ picking = true }) { Icon(painterResource(R.drawable.ic_auto_fix_high), stringResource(designChoice.label)) }
    if (!picking) return
    AlertDialog(
        onDismissRequest = { picking = false },
        confirmButton = {},
        dismissButton = { TextButton({ picking = false }) { Text(stringResource(R.string.settings_choice_cancel)) } },
        title = {
            Column {
                Text(stringResource(designChoice.label))
                Text(stringResource(R.string.settings_experimental), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        },
        // The designs' lines under them make the list taller than a phone on its side
        text = { ChoiceButtons(designChoice, AppSettings, Modifier.verticalScroll(rememberScrollState()), inset = 0.dp) { picking = false } },
    )
}

/**
 * The Swipe pad's strip: a swipe across it walks the swipe's way with [onWalk] ([MovementPadLayout.swipe]), and a tap,
 * or TalkBack's double tap, lists the twelve ways, up, down, in and out first, which no swipe gives. TalkBack's actions
 * have the twelve ways too, as TalkBack takes swipes for itself. The way walked [last] shows in it.
 */
@Composable
private fun SwipeStrip(last: MovementKey?, onWalk: (MovementKey) -> Unit) {
    var listing by remember { mutableStateOf(false) }
    val walk by rememberUpdatedState(onWalk)
    val listLabel = stringResource(R.string.movement_swipe_list)
    val actions = MovementPadLayout.listed.map { key ->
        CustomAccessibilityAction(stringResource(key.look().name)) {
            walk(key)
            true
        }
    }
    Box {
        Box(
            Modifier.width(SWIPE_WIDTH_DP.dp).heightIn(min = SWIPE_HEIGHT_DP.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .pointerInput(Unit) {
                    val least = SWIPE_LEAST_DP.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var end = down.position
                        var lifted = false
                        while (!lifted) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            end = change.position
                            lifted = !change.pressed
                        }
                        // A gesture taken from the strip, as by a scroll around it, does nothing
                        if (!lifted) return@awaitEachGesture
                        val way = MovementPadLayout.swipe(end.x - down.position.x, end.y - down.position.y, least)
                        if (way != null) walk(way) else listing = true
                    }
                }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    onClick(listLabel) {
                        listing = true
                        true
                    }
                    customActions = actions
                }
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (last != null) {
                    val look = last.look()
                    Icon(painterResource(look.icon), null, Modifier.rotate(look.turn), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(stringResource(R.string.movement_swipe), color = MaterialTheme.colorScheme.onSecondaryContainer, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        }
        DropdownMenu(listing, { listing = false }) {
            for (key in MovementPadLayout.listed) {
                val look = key.look()
                DropdownMenuItem(
                    text = { Text(stringResource(look.name)) },
                    onClick = {
                        listing = false
                        walk(key)
                    },
                    leadingIcon = { Icon(painterResource(look.icon), null, Modifier.rotate(look.turn)) },
                )
            }
        }
    }
}

// The Swipe pad's strip: as wide as the current pad's keys and as tall as two, and the least swipe that walks
private const val SWIPE_WIDTH_DP = 240
private const val SWIPE_HEIGHT_DP = 96
private const val SWIPE_LEAST_DP = 24

/** A key's [icon], turned clockwise by [turn] degrees, and its [name] for TalkBack. */
private class KeyLook(@DrawableRes val icon: Int, val turn: Float, @StringRes val name: Int)

private fun MovementKey.look(): KeyLook = when (this) {
    MovementKey.NORTH -> KeyLook(R.drawable.ic_arrow_upward, 0f, R.string.movement_north)
    MovementKey.NORTHEAST -> KeyLook(R.drawable.ic_arrow_upward, 45f, R.string.movement_northeast)
    MovementKey.EAST -> KeyLook(R.drawable.ic_arrow_upward, 90f, R.string.movement_east)
    MovementKey.SOUTHEAST -> KeyLook(R.drawable.ic_arrow_upward, 135f, R.string.movement_southeast)
    MovementKey.SOUTH -> KeyLook(R.drawable.ic_arrow_upward, 180f, R.string.movement_south)
    MovementKey.SOUTHWEST -> KeyLook(R.drawable.ic_arrow_upward, 225f, R.string.movement_southwest)
    MovementKey.WEST -> KeyLook(R.drawable.ic_arrow_upward, 270f, R.string.movement_west)
    MovementKey.NORTHWEST -> KeyLook(R.drawable.ic_arrow_upward, 315f, R.string.movement_northwest)
    MovementKey.UP -> KeyLook(R.drawable.ic_keyboard_double_arrow_up, 0f, R.string.movement_up)
    MovementKey.DOWN -> KeyLook(R.drawable.ic_keyboard_double_arrow_down, 0f, R.string.movement_down)
    MovementKey.IN -> KeyLook(R.drawable.ic_login, 0f, R.string.movement_in)
    MovementKey.OUT -> KeyLook(R.drawable.ic_logout, 0f, R.string.movement_out)
}
