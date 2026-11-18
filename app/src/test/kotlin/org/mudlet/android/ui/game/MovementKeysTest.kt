package org.mudlet.android.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.KeyBinding
import org.mudlet.android.protocol.KeyModifier

class MovementKeysTest {
    private val keypad = KeyModifier.KEYPAD.bit

    @Test
    fun withNoBindingAKeySendsTheShortWordTheGenericMapperFollows() {
        val words = listOf("n", "ne", "e", "se", "s", "sw", "w", "nw", "u", "d", "in", "out")
        assertEquals(words.map { MovementPress.Command(it) }, MovementKey.entries.map { it.press(emptySet()) })
    }

    @Test
    fun aCompassKeyPressesTheProfilesBindingOnItsNumberPadKey() {
        val digits = mapOf(
            MovementKey.NORTHWEST to '7',
            MovementKey.NORTH to '8',
            MovementKey.NORTHEAST to '9',
            MovementKey.WEST to '4',
            MovementKey.EAST to '6',
            MovementKey.SOUTHWEST to '1',
            MovementKey.SOUTH to '2',
            MovementKey.SOUTHEAST to '3',
        )
        val all = digits.values.map { KeyBinding(it.code, keypad) }.toSet()
        for ((key, digit) in digits) {
            assertEquals("$key", MovementPress.Binding(KeyBinding(digit.code, keypad), digit.toString()), key.press(all))
        }
        assertEquals("only its own key's binding", MovementPress.Command("n"), MovementKey.NORTH.press(all - KeyBinding('8'.code, keypad)))
    }

    @Test
    fun aBindingOnTheDigitOffTheNumberPadOrWithAKeyHeldIsNotTheNumberPadKeys() {
        val others = setOf(
            KeyBinding('8'.code, 0),
            KeyBinding('8'.code, keypad or KeyModifier.CONTROL.bit),
            // Up, which the number pad's 8 is with Num Lock off
            KeyBinding(0x01000013, keypad),
        )
        assertEquals(MovementPress.Command("n"), MovementKey.NORTH.press(others))
    }

    @Test
    fun upDownInAndOutHaveNoNumberPadKeyAndAlwaysSendTheirWord() {
        val everyDigit = ('0'..'9').map { KeyBinding(it.code, keypad) }.toSet()
        assertEquals(MovementPress.Command("u"), MovementKey.UP.press(everyDigit))
        assertEquals(MovementPress.Command("d"), MovementKey.DOWN.press(everyDigit))
        assertEquals(MovementPress.Command("in"), MovementKey.IN.press(everyDigit))
        assertEquals(MovementPress.Command("out"), MovementKey.OUT.press(everyDigit))
    }

    @Test
    fun thePadHoldsEachKeyOnceWithTheCompassAsOnAMap() {
        val rows = MovementPadLayout.rows
        assertEquals(MovementKey.entries.toSet(), rows.flatten().filterNotNull().toSet())
        assertEquals("each key once", MovementKey.entries.size, rows.flatten().filterNotNull().size)
        assertEquals("rows of one width", setOf(5), rows.map { it.size }.toSet())

        fun place(key: MovementKey): Pair<Int, Int> {
            val row = rows.indexOfFirst { key in it }
            return rows[row].indexOf(key) to row
        }
        val (northX, northY) = place(MovementKey.NORTH)
        val (southX, southY) = place(MovementKey.SOUTH)
        val (westX, westY) = place(MovementKey.WEST)
        val (eastX, eastY) = place(MovementKey.EAST)
        assertTrue("north over south, in one column", northY < southY && northX == southX)
        assertTrue("west left of east, in one row", westX < eastX && westY == eastY)
        assertEquals("north-west over west, left of north", westX to northY, place(MovementKey.NORTHWEST))
        assertEquals(eastX to northY, place(MovementKey.NORTHEAST))
        assertEquals(westX to southY, place(MovementKey.SOUTHWEST))
        assertEquals(eastX to southY, place(MovementKey.SOUTHEAST))
        assertTrue("the compass's keys are side by side", eastX - northX == 1 && northX - westX == 1 && southY - westY == 1 && westY - northY == 1)
        assertTrue("up over down", place(MovementKey.UP).first == place(MovementKey.DOWN).first && place(MovementKey.UP).second < place(MovementKey.DOWN).second)
        assertTrue("in over out", place(MovementKey.IN).first == place(MovementKey.OUT).first && place(MovementKey.IN).second < place(MovementKey.OUT).second)
    }

    @Test
    fun thePadShowsOnAPhoneHeldUprightWithNoKeyboard() {
        fun shown(
            enabled: Boolean = true,
            hiddenInput: Boolean = false,
            hardwareKeyboard: Boolean = false,
            keyboardUp: Boolean = false,
            widthDp: Int = 360,
            heightDp: Int = 640,
        ) = MovementPadLayout.shown(enabled, hiddenInput, hardwareKeyboard, keyboardUp, widthDp, heightDp)

        assertTrue(shown())
        assertTrue("the smallest phone the app's tests run on", shown(widthDp = 320, heightDp = 480))
        assertFalse("turned off", shown(enabled = false))
        assertFalse("a password is asked for", shown(hiddenInput = true))
        assertFalse("a keyboard is attached", shown(hardwareKeyboard = true))
        assertFalse("the on-screen keyboard is up", shown(keyboardUp = true))
        assertFalse("too narrow for its keys", shown(widthDp = 319))
        assertFalse("a phone on its side", shown(widthDp = 640, heightDp = 360))
        assertFalse(shown(heightDp = 479))
    }

    @Test
    fun aWindowOfJustTheLeastWidthIsThatWideAtEveryDensity() {
        // Each screen density from 120 to 640 dpi at which the least width is a whole number of pixels
        val densities = (120..640).filter { MovementPadLayout.MIN_WIDTH_DP * it % 160 == 0 }
        assertTrue("among them 163 dpi, where 326 pixels by the density is a hair under 320", 163 in densities)
        for (dpi in densities) {
            // As Android's DisplayMetrics makes its density
            val density = dpi * (1f / 160)
            assertEquals("$dpi dpi", MovementPadLayout.MIN_WIDTH_DP, MovementPadLayout.dp(MovementPadLayout.MIN_WIDTH_DP * dpi / 160, density))
        }
        assertEquals("half a dp less is still the nearest", 320, MovementPadLayout.dp(639, 2f))
        assertEquals(319, MovementPadLayout.dp(638, 2f))
    }

    @Test
    fun thePadWithTheButtonThatHidesItFitsTheNarrowestWindowItShowsIn() {
        val keys = MovementPadLayout.rows.first().size * MovementPadLayout.KEY_DP
        // The hide button, as wide as a key, and the row's padding of 8 dp a side
        assertTrue(keys + MovementPadLayout.KEY_DP + 16 <= MovementPadLayout.MIN_WIDTH_DP)
    }
}
