package org.mudlet.android.ui.game

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.data.Settings

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
        return (attachedOverride ?: true) && attachedOverride != true
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
        hiddenInput = false,
        hardwareKeyboard = HardwareKeyboard.attached(),
        keyboardUp = false,
        widthDp = MovementPadLayout.dp(screen.width, density.density),
        heightDp = MovementPadLayout.dp(screen.height, density.density),
    )
}

/**
 * The movement pad: a key for each of [MovementPadLayout.rows] at the row's end, where the thumb of the hand that
 * holds the phone is, and at its start the button that puts the pad away ([onHide]). [onPress] is a tap on a key.
 * West is on the left in every language, as on a map.
 */
@Composable
internal fun MovementPad(onPress: (MovementKey) -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.testTag(MOVEMENT_PAD_TAG)) {
        IconButton(onHide, Modifier.semantics { traversalIndex = 1f }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.movement_pad_hide)) }
        Spacer(Modifier.weight(1f))
        CompositionLocalProvider(LocalLayoutDirection provides LocalLayoutDirection.current) {
            Column {
                for (row in MovementPadLayout.rows) {
                    Row {
                        for (key in row) {
                            // A gap is a cell too. The button drawn is smaller than its cell, all of which takes its touch
                            Box(Modifier.size(MovementPadLayout.KEY_DP.dp), contentAlignment = Alignment.Center) {
                                if (key != null) {
                                    val look = key.look()
                                    FilledTonalIconButton({ onPress(key) }) {
                                        Icon(painterResource(look.icon), stringResource(look.name), Modifier.rotate(look.turn))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
