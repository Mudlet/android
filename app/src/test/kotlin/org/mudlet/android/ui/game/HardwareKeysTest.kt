package org.mudlet.android.ui.game

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.data.Settings.CaretKey
import org.mudlet.android.protocol.KeyBinding
import org.mudlet.android.protocol.KeyModifier

class HardwareKeysTest {
    private val shift = KeyModifier.SHIFT.bit
    private val keypad = KeyModifier.KEYPAD.bit

    @Test
    fun aKeypadDigitIsTheDigitOnTheKeypadWithNumLockAndTheKeyUnderItWithout() {
        assertEquals("tempKey(mudlet.keymodifier.Keypad, mudlet.key[\"8\"])", KeyBinding('8'.code, keypad), HardwareKeys.of(KeyEvent.KEYCODE_NUMPAD_8, KeyEvent.META_NUM_LOCK_ON, '8'.code))
        assertEquals("Up", KeyBinding(0x01000013, keypad), HardwareKeys.of(KeyEvent.KEYCODE_NUMPAD_8, 0, 0))
        assertEquals("Clear", KeyBinding(0x0100000b, keypad), HardwareKeys.of(KeyEvent.KEYCODE_NUMPAD_0 + 5, 0, 0))
        assertEquals("Delete", KeyBinding(0x01000007, keypad), HardwareKeys.of(KeyEvent.KEYCODE_NUMPAD_DOT, 0, 0))
        assertEquals(KeyBinding('.'.code, keypad), HardwareKeys.of(KeyEvent.KEYCODE_NUMPAD_DOT, KeyEvent.META_NUM_LOCK_ON, '.'.code))
        assertEquals(KeyBinding('+'.code, keypad), HardwareKeys.of(KeyEvent.KEYCODE_NUMPAD_ADD, KeyEvent.META_NUM_LOCK_ON, '+'.code))
        assertEquals("Enter, not Return", KeyBinding(0x01000005, keypad), HardwareKeys.of(KeyEvent.KEYCODE_NUMPAD_ENTER, 0, 0))
    }

    @Test
    fun functionKeysAndModifiers() {
        assertEquals(KeyBinding(0x01000030, 0), HardwareKeys.of(KeyEvent.KEYCODE_F1, 0, 0))
        assertEquals(KeyBinding(0x0100003b, KeyModifier.CONTROL.bit or KeyModifier.ALT.bit), HardwareKeys.of(KeyEvent.KEYCODE_F12, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON, 0))
        assertEquals(KeyBinding(0x01000030, KeyModifier.META.bit or shift), HardwareKeys.of(KeyEvent.KEYCODE_F1, KeyEvent.META_META_ON or KeyEvent.META_SHIFT_ON, 0))
    }

    @Test
    fun aPrintableKeyIsTheCharacterItTypesUpperCased() {
        assertEquals(KeyBinding('A'.code, 0), HardwareKeys.of(KeyEvent.KEYCODE_A, 0, 'a'.code))
        assertEquals(KeyBinding('A'.code, shift), HardwareKeys.of(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON, 'A'.code))
        assertEquals("Shift+1 is Qt's Exclam", KeyBinding('!'.code, shift), HardwareKeys.of(KeyEvent.KEYCODE_1, KeyEvent.META_SHIFT_ON, '!'.code))
        assertEquals(KeyBinding('A'.code, KeyModifier.CONTROL.bit), HardwareKeys.of(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON, 'a'.code))
        assertEquals("AltGr+E is no Alt", KeyBinding('E'.code, 0), HardwareKeys.of(KeyEvent.KEYCODE_E, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON, 'e'.code))
        assertEquals(KeyBinding(' '.code, 0), HardwareKeys.of(KeyEvent.KEYCODE_SPACE, 0, ' '.code))
    }

    @Test
    fun namedKeys() {
        assertEquals("Return", KeyBinding(0x01000004, 0), HardwareKeys.of(KeyEvent.KEYCODE_ENTER, 0, '\n'.code))
        assertEquals(KeyBinding(0x01000000, 0), HardwareKeys.of(KeyEvent.KEYCODE_ESCAPE, 0, 0))
        assertEquals(KeyBinding(0x01000001, 0), HardwareKeys.of(KeyEvent.KEYCODE_TAB, 0, '\t'.code))
        assertEquals("Shift+Tab is Qt's Backtab", KeyBinding(0x01000002, shift), HardwareKeys.of(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON, '\t'.code))
        assertEquals(KeyBinding(0x01000013, 0), HardwareKeys.of(KeyEvent.KEYCODE_DPAD_UP, 0, 0))
        assertEquals(KeyBinding(0x01000017, 0), HardwareKeys.of(KeyEvent.KEYCODE_PAGE_DOWN, 0, 0))
    }

    @Test
    fun aModifierAloneIsNoKey() {
        assertNull(HardwareKeys.of(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON, 0))
    }

    @Test
    fun theCaretKeyIsDesktopsTabWithoutCtrlCtrlTabOrF6Alone() {
        val tab = HardwareKeys.of(KeyEvent.KEYCODE_TAB, 0, '\t'.code)
        val altTab = HardwareKeys.of(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON, '\t'.code)
        val ctrlTab = HardwareKeys.of(KeyEvent.KEYCODE_TAB, KeyEvent.META_CTRL_ON, '\t'.code)
        val shiftTab = HardwareKeys.of(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON, '\t'.code)
        val f6 = HardwareKeys.of(KeyEvent.KEYCODE_F6, 0, 0)
        val shiftF6 = HardwareKeys.of(KeyEvent.KEYCODE_F6, KeyEvent.META_SHIFT_ON, 0)
        val f5 = HardwareKeys.of(KeyEvent.KEYCODE_F5, 0, 0)
        for (key in listOf(tab, ctrlTab, f6, null)) assertFalse("$key", HardwareKeys.caretKeyMatches(CaretKey.NONE, key))
        assertTrue(HardwareKeys.caretKeyMatches(CaretKey.TAB, tab))
        assertTrue("Alt does not stop it", HardwareKeys.caretKeyMatches(CaretKey.TAB, altTab))
        assertFalse(HardwareKeys.caretKeyMatches(CaretKey.TAB, ctrlTab))
        assertFalse("Backtab", HardwareKeys.caretKeyMatches(CaretKey.TAB, shiftTab))
        assertTrue(HardwareKeys.caretKeyMatches(CaretKey.CTRL_TAB, ctrlTab))
        assertFalse(HardwareKeys.caretKeyMatches(CaretKey.CTRL_TAB, tab))
        assertTrue(HardwareKeys.caretKeyMatches(CaretKey.F6, f6))
        assertFalse(HardwareKeys.caretKeyMatches(CaretKey.F6, shiftF6))
        assertFalse(HardwareKeys.caretKeyMatches(CaretKey.F6, f5))
    }

    @Test
    fun theCaretPassesOverTheLinesAScriptDeleted() {
        val deleted = setOf(0, 3, 4, 7)
        val hidden = { i: Int -> i in deleted }
        assertEquals("up over two", 2, HardwareKeys.caretStop(5, 4, 7, hidden))
        assertEquals("down over two", 5, HardwareKeys.caretStop(2, 3, 7, hidden))
        assertEquals("the last deleted: the one above it", 6, HardwareKeys.caretStop(7, 7, 7, hidden))
        assertEquals("a page down past the end: the last drawn", 6, HardwareKeys.caretStop(2, 12, 7, hidden))
        assertEquals("to the first, deleted: the first drawn", 1, HardwareKeys.caretStop(5, 0, 7, hidden))
        assertEquals("up from the first drawn: it stays", 1, HardwareKeys.caretStop(1, 0, 7, hidden))
        assertEquals("a drawn line is where it goes", 1, HardwareKeys.caretStop(2, 1, 7) { false })
    }

    @Test
    fun aPrintableKeyWithoutCtrlAltOrMetaGoesFromTheCaretToTheInputLine() {
        assertTrue(HardwareKeys.typesFromCaret(0, 'n'.code))
        assertTrue("Shift", HardwareKeys.typesFromCaret(KeyEvent.META_SHIFT_ON, 'N'.code))
        assertTrue("AltGr, the right Alt", HardwareKeys.typesFromCaret(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON, '@'.code))
        assertFalse(HardwareKeys.typesFromCaret(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON, 'n'.code))
        assertFalse(HardwareKeys.typesFromCaret(KeyEvent.META_CTRL_ON, 'n'.code))
        assertFalse(HardwareKeys.typesFromCaret(KeyEvent.META_META_ON, 'n'.code))
        assertFalse("Tab", HardwareKeys.typesFromCaret(0, '\t'.code))
        assertFalse("an arrow key", HardwareKeys.typesFromCaret(0, 0))
    }
}
