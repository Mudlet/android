package org.mudlet.android.ui.game

import org.mudlet.android.protocol.KeyBinding
import org.mudlet.android.protocol.KeyModifier
import kotlin.math.roundToInt

/**
 * A key of the movement pad. [command] is the word it sends, the short ones Mudlet's generic mapper follows the player
 * by; [numpad] the number pad's digit desktop players walk that way with, null for the four ways the number pad has no
 * key for.
 */
internal enum class MovementKey(val command: String, val numpad: Char?) {
    NORTH("n", '8'),
    NORTHEAST("ne", '9'),
    EAST("e", '6'),
    SOUTHEAST("se", '3'),
    SOUTH("s", '2'),
    SOUTHWEST("sw", '1'),
    WEST("w", '4'),
    NORTHWEST("nw", '7'),
    UP("u", null),
    DOWN("d", null),
    IN("in", null),
    OUT("out", null),
    ;

    /**
     * What a tap does in a profile with the key [bindings]: the binding on this way's number pad key runs where the
     * profile has one, as that key does for a desktop player, so that the profile's own walking is used; else
     * [command] is sent.
     */
    fun press(bindings: Set<KeyBinding>): MovementPress {
        val digit = numpad ?: return MovementPress.Command(command)
        // The digit with Num Lock on, as HardwareKeys gives it and as desktop records a binding made on that key
        val key = KeyBinding(digit.code, KeyModifier.KEYPAD.bit)
        return if (key in bindings) MovementPress.Binding(key, digit.toString()) else MovementPress.Command(command)
    }
}

/** What a tap on a [MovementKey] does. */
internal sealed interface MovementPress {
    /** Sends [line] as if typed. */
    data class Command(val line: String) : MovementPress

    /** Presses the key binding on [key], which types [text] on a keyboard. */
    data class Binding(val key: KeyBinding, val text: String) : MovementPress
}

/** Where the movement pad's keys are, and when it shows. */
internal object MovementPadLayout {
    /** The rows from the top, each from west to east as on a map and the number pad; null is a gap. */
    val rows: List<List<MovementKey?>> = listOf(
        listOf(MovementKey.IN, MovementKey.NORTHWEST, MovementKey.NORTH, MovementKey.NORTHEAST, MovementKey.UP),
        listOf(null, MovementKey.WEST, null, MovementKey.EAST, null),
        listOf(MovementKey.OUT, MovementKey.SOUTHWEST, MovementKey.SOUTH, MovementKey.SOUTHEAST, MovementKey.DOWN),
    )

    /** A key's side, a touch target's least. */
    const val KEY_DP = 48

    /** The narrowest window the pad shows in, a small phone's: its keys, the button that hides it and the row's padding take 304. */
    const val MIN_WIDTH_DP = 320

    /** The lowest window the pad shows in: one lower, a phone on its side for one, would keep too little of the game's text. */
    const val MIN_HEIGHT_DP = 480

    /**
     * Whether the pad shows in a window of [widthDp] by [heightDp]: only with its setting on ([enabled]), and not
     * while the game hides input, when a tap would send a direction as the password; with a hardware keyboard, which
     * walks better; with the on-screen keyboard up, which leaves the game's text too little room for both; or in a
     * window too small for it.
     */
    fun shown(enabled: Boolean, hiddenInput: Boolean, hardwareKeyboard: Boolean, keyboardUp: Boolean, widthDp: Int, heightDp: Int): Boolean = enabled && !hiddenInput && !hardwareKeyboard && !keyboardUp && widthDp >= MIN_WIDTH_DP && heightDp >= MIN_HEIGHT_DP

    /**
     * [px] pixels in dp at [density], to the nearest: cut short, a window of just [MIN_WIDTH_DP] would be one less at
     * the densities where the division falls a hair under, and have no pad.
     */
    fun dp(px: Int, density: Float): Int = (px / density).roundToInt()
}
