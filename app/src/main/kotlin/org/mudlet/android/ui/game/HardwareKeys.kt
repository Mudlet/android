package org.mudlet.android.ui.game

import android.view.KeyEvent
import org.mudlet.android.protocol.KeyBinding
import org.mudlet.android.protocol.KeyModifier
import org.mudlet.android.ui.scripts.QtKeys.BACKSPACE
import org.mudlet.android.ui.scripts.QtKeys.DELETE
import org.mudlet.android.ui.scripts.QtKeys.DOWN
import org.mudlet.android.ui.scripts.QtKeys.END
import org.mudlet.android.ui.scripts.QtKeys.ENTER
import org.mudlet.android.ui.scripts.QtKeys.ESCAPE
import org.mudlet.android.ui.scripts.QtKeys.F1
import org.mudlet.android.ui.scripts.QtKeys.HOME
import org.mudlet.android.ui.scripts.QtKeys.INSERT
import org.mudlet.android.ui.scripts.QtKeys.LEFT
import org.mudlet.android.ui.scripts.QtKeys.PAGE_DOWN
import org.mudlet.android.ui.scripts.QtKeys.PAGE_UP
import org.mudlet.android.ui.scripts.QtKeys.PAUSE
import org.mudlet.android.ui.scripts.QtKeys.RETURN
import org.mudlet.android.ui.scripts.QtKeys.RIGHT
import org.mudlet.android.ui.scripts.QtKeys.SPACE
import org.mudlet.android.ui.scripts.QtKeys.TAB
import org.mudlet.android.ui.scripts.QtKeys.UP

/**
 * A hardware key as Qt's key event gives it on the desktop, which is how Mudlet's key bindings name keys
 * (docs/protocol.md, `keyBindings`): Qt's key code and modifier bits from Android's key code and meta state.
 */
internal object HardwareKeys {
    private const val BACKTAB = 0x01000002
    private const val PRINT = 0x01000009
    private const val CLEAR = 0x0100000b

    private val named = mapOf(
        KeyEvent.KEYCODE_ESCAPE to ESCAPE,
        KeyEvent.KEYCODE_DEL to BACKSPACE,
        KeyEvent.KEYCODE_ENTER to RETURN,
        KeyEvent.KEYCODE_INSERT to INSERT,
        KeyEvent.KEYCODE_FORWARD_DEL to DELETE,
        KeyEvent.KEYCODE_BREAK to PAUSE,
        KeyEvent.KEYCODE_SYSRQ to PRINT,
        KeyEvent.KEYCODE_MOVE_HOME to HOME,
        KeyEvent.KEYCODE_MOVE_END to END,
        KeyEvent.KEYCODE_DPAD_LEFT to LEFT,
        KeyEvent.KEYCODE_DPAD_UP to UP,
        KeyEvent.KEYCODE_DPAD_RIGHT to RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN to DOWN,
        KeyEvent.KEYCODE_PAGE_UP to PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN to PAGE_DOWN,
        KeyEvent.KEYCODE_SPACE to SPACE,
    )

    // With Num Lock off a keypad digit is the key printed under it, as on the desktop
    private val keypadWithoutNumLock = intArrayOf(INSERT, END, DOWN, PAGE_DOWN, LEFT, CLEAR, RIGHT, HOME, UP, PAGE_UP)

    private val keypadSigns = mapOf(
        KeyEvent.KEYCODE_NUMPAD_DIVIDE to '/'.code,
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY to '*'.code,
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT to '-'.code,
        KeyEvent.KEYCODE_NUMPAD_ADD to '+'.code,
        KeyEvent.KEYCODE_NUMPAD_COMMA to ','.code,
        KeyEvent.KEYCODE_NUMPAD_EQUALS to '='.code,
        KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN to '('.code,
        KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN to ')'.code,
        KeyEvent.KEYCODE_NUMPAD_ENTER to ENTER,
    )

    /**
     * The key [keyCode] held with [metaState]; [shiftedChar] is what it types with Shift alone, if held, or 0, as
     * KeyEvent.getUnicodeChar(metaState and META_SHIFT_MASK) gives it, since Qt names a printable key by the
     * character it types upper-cased, Shift+1 as `!`. Null for a key Qt names otherwise here, or a modifier alone.
     */
    fun of(keyCode: Int, metaState: Int, shiftedChar: Int): KeyBinding? {
        var modifiers = 0
        if (metaState and KeyEvent.META_SHIFT_ON != 0) modifiers = modifiers or KeyModifier.SHIFT.bit
        if (metaState and KeyEvent.META_CTRL_ON != 0) modifiers = modifiers or KeyModifier.CONTROL.bit
        if (metaState and KeyEvent.META_ALT_ON != 0) modifiers = modifiers or KeyModifier.ALT.bit
        if (metaState and KeyEvent.META_META_ON != 0) modifiers = modifiers or KeyModifier.META.bit
        val keypad = modifiers or KeyModifier.KEYPAD.bit
        return when (keyCode) {
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                val digit = keyCode - KeyEvent.KEYCODE_NUMPAD_0
                KeyBinding(if (metaState and KeyEvent.META_NUM_LOCK_ON != 0) '0'.code + digit else keypadWithoutNumLock[digit], keypad)
            }

            KeyEvent.KEYCODE_NUMPAD_DOT -> KeyBinding(if (metaState and KeyEvent.META_NUM_LOCK_ON != 0) '.'.code else DELETE, keypad)

            in keypadSigns -> KeyBinding(keypadSigns.getValue(keyCode), keypad)

            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> KeyBinding(F1 + keyCode - KeyEvent.KEYCODE_F1, modifiers)

            // Qt gives Shift+Tab its own key
            KeyEvent.KEYCODE_TAB -> KeyBinding(if (modifiers and KeyModifier.SHIFT.bit != 0) BACKTAB else TAB, modifiers)

            in named -> KeyBinding(named.getValue(keyCode), modifiers)

            else -> if (shiftedChar > 0 && !Character.isISOControl(shiftedChar)) KeyBinding(Character.toUpperCase(shiftedChar), modifiers) else null
        }
    }
}
