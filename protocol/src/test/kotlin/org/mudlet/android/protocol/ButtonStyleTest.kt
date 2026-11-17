package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The style sheet subset the app draws of a bar's button and of the bar (docs/phase-f.md, "Button bars and commands"). */
class ButtonStyleTest {
    private val up = ButtonState()

    private fun background(barSheet: String, buttonSheet: String, state: ButtonState = up) = ButtonStyle.of(barSheet, buttonSheet, state).style.background

    @Test
    fun aButtonsOwnSheetIsOverItsBarsWhateverTheSelectors() {
        val bar = "QPushButton { background-color: #000080; color: #ffff00; font-size: 9pt }"
        val plain = ButtonStyle.of(bar, "", up).style
        assertEquals(LabelFill.Solid(0xFF000080.toInt()), plain.background)
        assertEquals(0xFFFFFF00.toInt(), plain.color)
        assertEquals(12f, plain.fontSizePx)
        // The button's bare declaration, Qt's `* { }`, ranks below the bar's QPushButton rule within one sheet, but its
        // own sheet is over the bar's; what it does not set it still takes from the bar
        val own = ButtonStyle.of(bar, "background-color: #800000", up).style
        assertEquals(LabelFill.Solid(0xFF800000.toInt()), own.background)
        assertEquals(0xFFFFFF00.toInt(), own.color)
    }

    @Test
    fun aBarsBareDeclarationsAndWidgetRulesReachItsButtons() {
        assertEquals(LabelFill.Solid(0xFF202020.toInt()), background("background-color: #202020", ""))
        assertEquals(LabelFill.Solid(0xFF202020.toInt()), background("QWidget { background-color: #202020 }", ""))
        assertEquals(LabelFill.Solid(0xFF202020.toInt()), background("QAbstractButton { background-color: #202020 }", ""))
        assertEquals(LabelFill.Solid(0xFF202020.toInt()), background(".QPushButton { background-color: #202020 }", ""))
        // A rule for another widget, one by object name and one for a part of the button are not the button's
        assertNull(background("QLabel { background: red } QToolButton { background: red } #flipButton_1 { background: red } QPushButton::menu-indicator { background: red }", ""))
        // Within a sheet a rule for the button ranks over `*`, wherever it is
        assertEquals(LabelFill.Solid(0xFF000080.toInt()), background("QPushButton { background-color: #000080 } * { background-color: #202020 }", ""))
    }

    @Test
    fun aBarsAndAButtonsSheetIsItsRulesOrItsBareDeclarationsToo() {
        val navy = LabelFill.Solid(0xFF000080.toInt())
        val red = LabelFill.Solid(0xFFFF0000.toInt())
        // Qt reads a sheet's rules up to what is not one, so not what follows them, a `*` rule either
        assertEquals(navy, background("", "* { background: navy } background: red"))
        assertEquals(navy, background("* { background: navy } background: red", ""))
        assertEquals(navy, ButtonStyle.bar("* { background: navy } background: red").background)
        assertNull(background("", "QPushButton { color: yellow } background: red"))
        assertNull(ButtonStyle.bar("QWidget { color: yellow } background: red").background)
        // One that starts with bare declarations is those and none of its rules
        assertEquals(red, background("", "background: red; QPushButton { background: navy }"))
        assertEquals(red, ButtonStyle.bar("background: red; QWidget { background: navy }").background)
        // A rule with a pseudo-state after them reads as a declaration with a brace after it, which loses them all
        assertNull(background("background: red; QPushButton:checked { background: navy }", "", ButtonState(checked = true)))
        // What Qt cannot read of a rule loses it and all after it, here for want of a `;`
        assertNull(background("", "QPushButton { color: yellow\n background: red } QPushButton { background: navy }"))
        assertNull(ButtonStyle.bar("QWidget { color: yellow\n background: red } QWidget { background: navy }").background)
    }

    @Test
    fun rulesRankAsQtRanksThem() {
        val down = ButtonState(checked = true)
        // A class and a pseudo-state each count as much as each other, and more than a type, wherever the rules are
        assertEquals(LabelFill.Solid(0xFF006400.toInt()), background("", ".QPushButton { background: #000080 } QPushButton:checked { background: #006400 }", down))
        assertEquals(LabelFill.Solid(0xFF000080.toInt()), background("", ".QPushButton { background: #000080 } QPushButton { background: #006400 }"))
        assertEquals(LabelFill.Solid(0xFF006400.toInt()), background("", "QPushButton:checked:!pressed { background: #006400 } .QPushButton:checked { background: #000080 }", down))
        // A rule for a group ranks as the most specific of its selectors that apply
        assertEquals(LabelFill.Solid(0xFF006400.toInt()), background("", "*, QPushButton:checked { background: #006400 } QPushButton { background: #000080 }", down))
        // Pseudo-states are read whatever their case, and a rule negating one the app does not know is skipped too
        assertEquals(LabelFill.Solid(0xFF006400.toInt()), background("", "QPushButton:CHECKED { background: #006400 }", down))
        assertNull(background("", "QPushButton:!selected { background: #006400 }"))
    }

    @Test
    fun aPushDownButtonsCheckedRuleWinsWhileItIsDownWhereverItIs() {
        val sheet = "QPushButton:checked { background-color: #006400 } QPushButton { background-color: #000080; color: white }"
        assertEquals(LabelFill.Solid(0xFF000080.toInt()), background("", sheet))
        val down = ButtonStyle.of("", sheet, ButtonState(checked = true)).style
        assertEquals(LabelFill.Solid(0xFF006400.toInt()), down.background)
        assertEquals(0xFFFFFFFF.toInt(), down.color)
        // Qt's other names for it, and its negation
        assertEquals(LabelFill.Solid(0xFF006400.toInt()), background("QPushButton:on { background: #006400 }", "", ButtonState(checked = true)))
        assertEquals(LabelFill.Solid(0xFF000080.toInt()), background("QPushButton:!checked { background: #000080 }", ""))
        assertNull(background("QPushButton:!checked { background: #000080 }", "", ButtonState(checked = true)))
        // As in the bar's sheet: the button's own sheet is still over it
        assertEquals(LabelFill.Solid(0xFF800000.toInt()), background("QPushButton:checked { background: #006400 }", "background: #800000", ButtonState(checked = true)))
    }

    @Test
    fun aTouchScreensStatesPickTheRules() {
        val sheet = "QPushButton:pressed { background: #111111 } QPushButton:hover { color: red } QPushButton:focus { color: red } " +
            "QPushButton:disabled { color: gray } QPushButton:!hover { font-size: 20px } QPushButton:selected { color: red }"
        val idle = ButtonStyle.of("", sheet, up).style
        assertNull(idle.background)
        // Nothing is under a pointer or focused on a touch screen, and a state the app does not know is skipped
        assertNull(idle.color)
        assertEquals(20f, idle.fontSizePx)
        assertEquals(LabelFill.Solid(0xFF111111.toInt()), ButtonStyle.of("", sheet, ButtonState(pressed = true)).style.background)
        assertEquals(0xFF808080.toInt(), ButtonStyle.of("", sheet, ButtonState(enabled = false)).style.color)
        assertEquals(0xFF008000.toInt(), ButtonStyle.of("", "QPushButton:enabled { color: green }", up).style.color)
        assertNull(ButtonStyle.of("", "QPushButton:enabled { color: green }", ButtonState(enabled = false)).style.color)
        // Open while down or pressed, as Qt reads a button's state; never unchecked, flat or default, as TFlipButton draws itself
        val open = "QPushButton:open { background: #222222 } QPushButton:closed { background: #333333 } QPushButton:unchecked { color: red } " +
            "QPushButton:off { color: red } QPushButton:flat { color: red } QPushButton:default { color: red }"
        assertEquals(LabelFill.Solid(0xFF333333.toInt()), background("", open))
        assertEquals(LabelFill.Solid(0xFF222222.toInt()), background("", open, ButtonState(checked = true)))
        assertEquals(LabelFill.Solid(0xFF222222.toInt()), background("", open, ButtonState(pressed = true)))
        assertNull(ButtonStyle.of("", open, up).style.color)
        // Every TFlipButton is vertical, and the app's window is active while a button shows
        val placed = "QPushButton:vertical { background: #444444 } QPushButton:horizontal { color: red } QPushButton:!vertical { color: red } " +
            "QPushButton:active { font-size: 20px } QPushButton:!active { font-size: 30px }"
        val vertical = ButtonStyle.of("", placed, up).style
        assertEquals(LabelFill.Solid(0xFF444444.toInt()), vertical.background)
        assertNull(vertical.color)
        assertEquals(20f, vertical.fontSizePx)
        // A state alone is the universal selector's
        assertEquals(LabelFill.Solid(0xFF006400.toInt()), background("", ":checked { background: #006400 }", ButtonState(checked = true)))
    }

    @Test
    fun paddingAndMarginAreTheAppsUnlessASheetSetsThem() {
        val none = ButtonStyle.of("color: red", "background: blue", up)
        assertNull(none.padding)
        assertNull(none.margin)
        val zero = ButtonStyle.of("padding: 0px", "margin: 0", up)
        assertEquals(Edges.ZERO, zero.padding)
        assertEquals(Edges.ZERO, zero.margin)
        assertEquals(Edges(1f, 2f, 1f, 2f), ButtonStyle.of("padding: 5px", "padding: 1px 2px", up).padding)
        // A value that is not a length leaves it unset
        assertNull(ButtonStyle.of("", "padding: wide", up).padding)
        // One in a unit Qt does not read a box in is 0, set
        assertEquals(Edges.ZERO, ButtonStyle.of("padding: 5px", "padding: 30pt", up).padding)
    }

    @Test
    fun aShorthandSetsWhatItDoesNotNameBackToNoneAcrossRules() {
        val bar = "QPushButton { background: #000080 url(tile.png); border: 2px solid red; border-radius: 4px }"
        val down = ButtonStyle.of(bar, "QPushButton:checked { background: #006400; border: none }", ButtonState(checked = true)).style
        assertEquals(LabelFill.Solid(0xFF006400.toInt()), down.background)
        assertNull(down.backgroundImage)
        assertEquals(0f, down.drawnBorderWidth)
        assertNull(down.borderColor)
        // What the shorthands do not cover stays
        assertEquals(4f, down.borderRadius)
        // `background-image: none` in a rule over it does not take the bar's image away, as it does not in Qt
        val tiled = ButtonStyle.of(bar, "QPushButton:checked { background-image: none; background-color: #006400 }", ButtonState(checked = true)).style
        assertEquals(LabelFill.Solid(0xFF006400.toInt()), tiled.background)
        assertEquals("tile.png", tiled.backgroundImage)
    }

    @Test
    fun aSizeOfNothingKeepsTheBarsAndARadiusBelowNothingIsNone() {
        val style = ButtonStyle.of("QPushButton { font-size: 9pt; border-radius: 6px }", "font-size: 0px; border-radius: -3px", up).style
        assertEquals(12f, style.fontSizePx)
        assertEquals(0f, style.borderRadius)
    }

    @Test
    fun theAppsButtonIsDrawnOnlyWhereTheSheetsLeaveItsNativeFrame() {
        fun of(bar: String, button: String) = ButtonStyle.of(bar, button, up)
        assertTrue(of("color: red", "background-image: url(tile.png); padding: 4px").nativeBorder)
        assertTrue(of("color: red", "").appFill)
        // A style for the top edge but `native`, a radius, or a border image, is a frame of the sheets', and the bar's
        // reach the button; each as Qt 6.4.2 draws a QPushButton
        val gone = listOf(
            "border: none", "border: 2px solid red", "border-style: solid", "border-top: 3px solid red", "border-top-style: solid",
            "border-radius: 4px", "border-radius: 0", "border-top-left-radius: 0", "border-image: url(b.png)",
        )
        for (sheet in gone) {
            assertFalse(sheet, of("", sheet).nativeBorder)
            assertFalse(sheet, of("", sheet).appFill)
            assertFalse(sheet, of("QWidget { $sheet }", "").nativeBorder)
            assertTrue(sheet, of("", sheet).style.drawsBox)
        }
        // Qt keeps its native frame under the other edges' styles, the border's colour and width, and a negative radius
        val kept = listOf(
            "border-color: red", "border-left-color: red", "border-width: 0", "border-width: 3px; border-color: red", "border-left: 3px solid red",
            "border-bottom-style: solid", "border-style: native", "border: native", "border: 3px native red", "border-top-style: native", "border-radius: -3px",
        )
        for (sheet in kept) {
            assertTrue(sheet, of("", sheet).nativeBorder)
            assertTrue(sheet, of("", sheet).appFill)
            assertTrue(sheet, of("QWidget { $sheet }", "").nativeBorder)
            assertFalse(sheet, of("", sheet).style.drawsBox)
            assertEquals(sheet, 0f, of("", sheet).style.drawnBorderWidth)
        }
        // A background of the sheets' is drawn instead of the app's, frame or not
        val filled = of("", "background: #000080")
        assertTrue(filled.nativeBorder)
        assertFalse(filled.appFill)
        // A rule that does not apply sets nothing
        assertTrue(of("QPushButton:pressed { border: none }", "").nativeBorder)
        // `border-image: none` makes no frame, and takes away the one a rule before it made, as Qt draws them
        assertTrue(of("", "border-image: none").appFill)
        val unframed = of("QPushButton { border-image: url(frame.png) }", "border-image: none")
        assertTrue(unframed.appFill)
        assertNull(unframed.style.borderImage)
    }

    @Test
    fun theTextIsTheSheetsWhereItReadsOnTheAppsColourInEitherTheme() {
        val light = 0xFFE0E0E0.toInt()
        val dark = 0xFF303030.toInt()
        val onLight = 0xFF1A1A1A.toInt()
        val onDark = 0xFFEEEEEE.toInt()
        val bare = LabelStyle()
        val navy = ButtonStyle.of("", "color: #000080", up)
        assertEquals(0xFF000080.toInt(), navy.text(bare, light, onLight))
        assertEquals(onDark, navy.text(bare, dark, onDark))
        val yellow = ButtonStyle.of("", "color: #ffff00", up)
        assertEquals(onLight, yellow.text(bare, light, onLight))
        assertEquals(0xFFFFFF00.toInt(), yellow.text(bare, dark, onDark))
        assertEquals(onDark, ButtonStyle.of("", "", up).text(bare, dark, onDark))
        // With nothing of the sheets' on it the app's own pair is the theme's to choose, whatever its contrast
        assertEquals(0xFF909090.toInt(), ButtonStyle.of("", "", up).text(bare, 0xFF808080.toInt(), 0xFF909090.toInt()))
        // A see-through fill of the sheets' shows the app's colour through it
        assertEquals(onDark, ButtonStyle.of("", "background: rgba(255, 255, 255, 20); color: #000080", up).text(bare, dark, onDark))
        // A see-through `color` is read as it is drawn, mixed into what it is on
        assertEquals(onLight, ButtonStyle.of("", "color: rgba(0, 0, 0, 40)", up).text(bare, light, onLight))
        assertEquals(0xDD000000.toInt(), ButtonStyle.of("", "color: rgba(0, 0, 0, 221)", up).text(bare, light, onLight))
    }

    @Test
    fun onASeeThroughColourOverTheAppsTheTextIsWhateverReads() {
        val light = 0xFFE0E0E0.toInt()
        val dark = 0xFF303030.toInt()
        val onLight = 0xFF1A1A1A.toInt()
        val onDark = 0xFFEEEEEE.toInt()
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val bare = LabelStyle()
        // Under half see-through black, the bar's and then the button's, the app's light colour is a dark grey, where
        // its own dark text no longer reads; under the one layer it is a mid grey, where black just does
        val dim = "QWidget { background-color: rgba(0, 0, 0, 120) }"
        assertEquals(white, ButtonStyle.of(dim, "border: none", up).text(ButtonStyle.bar(dim), light, onLight))
        assertEquals(black, ButtonStyle.of("", "background-color: rgba(0, 0, 0, 120)", up).text(bare, light, onLight))
        // And under half see-through white its dark colour is a mid grey
        assertEquals(black, ButtonStyle.of("", "background-color: rgba(255, 255, 255, 127)", up).text(bare, dark, onDark))
        // A colour of the sheets' that does not read there is not swapped for one of the app's that reads worse
        assertEquals(white, ButtonStyle.of(dim, "border: none; color: #707070", up).text(ButtonStyle.bar(dim), light, onLight))
        // Theirs where it reads, and the app's where that still does
        assertEquals(0xFFFFFF00.toInt(), ButtonStyle.of(dim, "border: none; color: #ffff00", up).text(ButtonStyle.bar(dim), light, onLight))
        assertEquals(onDark, ButtonStyle.of("", "background-color: rgba(255, 255, 255, 20)", up).text(bare, dark, onDark))
    }

    @Test
    fun onWhatTheSheetsDrewTheTextIsTheirsOrBlackOrWhite() {
        val dark = 0xFF303030.toInt()
        val onDark = 0xFFEEEEEE.toInt()
        val bare = LabelStyle()
        assertEquals(0xFF000000.toInt(), ButtonStyle.of("", "background: white", up).text(bare, dark, onDark))
        // Black on a mid grey just light enough for it, white on one just too dark
        assertEquals(0xFF000000.toInt(), ButtonStyle.of("", "background: #808080", up).text(bare, dark, onDark))
        assertEquals(0xFFFFFFFF.toInt(), ButtonStyle.of("", "background: #707070", up).text(bare, dark, onDark))
        // Their colour, as on the desktop, even where it reads badly
        assertEquals(0xFF111111.toInt(), ButtonStyle.of("", "background: #000080; color: #111111", up).text(bare, dark, onDark))
        // An image of theirs, whatever it shows: their colour, or the app's text without one
        assertEquals(onDark, ButtonStyle.of("", "border-image: url(b.png)", up).text(bare, dark, onDark))
        assertEquals(0xFF111111.toInt(), ButtonStyle.of("", "border-image: url(b.png); color: #111111", up).text(bare, dark, onDark))
        assertEquals(0xFF111111.toInt(), ButtonStyle.of("", "background-image: url(t.png); color: #111111", up).text(bare, dark, onDark))
        // The bar's image shows behind a frame of the sheets', and not behind the app's button
        val tiled = "QWidget { background-image: url(t.png) }"
        assertEquals(0xFF111111.toInt(), ButtonStyle.of(tiled, "background: none; border: none; color: #111111", up).text(ButtonStyle.bar(tiled), dark, onDark))
        assertEquals(onDark, ButtonStyle.of(tiled, "background: none; color: #111111", up).text(ButtonStyle.bar(tiled), dark, onDark))
    }

    @Test
    fun aFrameOfTheSheetsShowsTheBarBehindTheText() {
        val light = 0xFFE0E0E0.toInt()
        val onLight = 0xFF1A1A1A.toInt()
        val barSheet = "QWidget { background: #202020 }"
        val bar = ButtonStyle.bar(barSheet)
        // The button's own `background: none` leaves the bar's colour to the bar; with no frame of its own the app's
        // button is drawn over it, so the bar is not what the text is on: yellow reads on the bar, not on the button
        val native = ButtonStyle.of(barSheet, "background: none; color: #ffff00", up)
        assertTrue(native.appFill)
        assertEquals(onLight, native.text(bar, light, onLight))
        assertEquals(onLight, ButtonStyle.of(barSheet, "background: none", up).text(bar, light, onLight))
        // With one, the bar is: white on its dark grey, and the sheets' colour there as they set it
        val framed = ButtonStyle.of(barSheet, "background: none; border: none", up)
        assertFalse(framed.appFill)
        assertEquals(0xFFFFFFFF.toInt(), framed.text(bar, light, onLight))
        assertEquals(0xFF000080.toInt(), ButtonStyle.of(barSheet, "background: none; border: none; color: #000080", up).text(bar, light, onLight))
        // On a bar of the app's colour the sheets' colour is drawn only where it reads
        assertEquals(onLight, ButtonStyle.of("", "border: none; color: #ffff00", up).text(LabelStyle(), light, onLight))
    }

    @Test
    fun noLengthOfAButtonsOrABarsIsLongerThanAScreenCouldShow() {
        val huge = ButtonStyle.of("", "padding: 50000px; margin: 0 60000px; border: 1000000px solid red; font-size: 100000px", up)
        assertEquals(Edges(4096f, 4096f, 4096f, 4096f), huge.padding)
        assertEquals(Edges(0f, 4096f, 0f, 4096f), huge.margin)
        assertEquals(4096f, huge.style.drawnBorderWidth)
        assertEquals(4096f, huge.style.fontSizePx)
        assertEquals(4096f, ButtonStyle.bar("border: 70000px solid red").drawnBorderWidth)
        assertEquals(Edges(1f, 2f, 3f, 4f), ButtonStyle.of("", "padding: 1px 2px 3px 4px", up).padding)
        // A radius of 39 digits is a float, and infinite once a screen's density multiplies it, which no corner may be
        val round = "border-radius: 2" + "0".repeat(38) + "px"
        assertEquals(4096f, ButtonStyle.of("", round, up).style.borderRadius)
        assertEquals(4096f, ButtonStyle.of("QPushButton { $round }", "", up).style.borderRadius)
        assertEquals(4096f, ButtonStyle.bar("$round; background: red").borderRadius)
    }

    @Test
    fun aButtonsRoomShrinksToWhatItsPlaceHas() {
        val appPadding = Edges(0f, 12f, 0f, 12f)
        val appMargin = Edges(2f, 2f, 2f, 2f)
        fun room(sheet: String, width: Float, height: Float) = ButtonStyle.of("", sheet, up).room(width, height, appPadding, appMargin)
        // The app's own spacing where the sheets set none, and the sheets' as they set it where the place has the room
        assertEquals(ButtonRoom(appMargin, 0f, appPadding), room("color: red", 312f, 400f))
        assertEquals(ButtonRoom(Edges(10f, 20f, 10f, 20f), 3f, Edges(1f, 2f, 3f, 4f)), room("margin: 10px 20px; border: 3px solid red; padding: 1px 2px 3px 4px", 312f, 400f))
        assertEquals(ButtonRoom(appMargin, 0f, appPadding), room("border: 3px", Float.POSITIVE_INFINITY, Float.NaN))
        // Qt draws its native button where the top edge is native, and none of the others' border
        assertEquals(ButtonRoom(appMargin, 0f, appPadding), room("background: blue; border: 6px solid red; border-top-style: native", 312f, 400f))
        // Across, 800 are asked of 200, so each gets a quarter; down there is room for all 220
        assertEquals(ButtonRoom(Edges(10f, 25f, 10f, 25f), 25f, Edges(0f, 50f, 0f, 50f)), room("margin: 10px 100px; border: 100px solid red; padding: 0 200px", 200f, 400f))
        // Lengths at their longest take no more than there is, either way, and nothing where there is nothing
        for ((width, height) in listOf(312f to 500f, 500f to 312f, 0f to 0f, -48f to 100f)) {
            val huge = room("margin: 99999px; border: 99999px solid red; padding: 99999px", width, height)
            val across = huge.margin.left + huge.margin.right + 2 * huge.border + huge.padding.left + huge.padding.right
            val down = huge.margin.top + huge.margin.bottom + 2 * huge.border + huge.padding.top + huge.padding.bottom
            assertTrue("$across of $width", across <= width.coerceAtLeast(0f) + 0.01f)
            assertTrue("$down of $height", down <= height.coerceAtLeast(0f) + 0.01f)
            assertTrue("$huge", listOf(huge.border, huge.margin.left, huge.margin.top, huge.padding.right, huge.padding.bottom).all { it >= 0f })
        }
        assertEquals(312f, room("margin: 0 99999px; padding: 0 99999px", 312f, 500f).let { it.margin.left + it.margin.right + it.padding.left + it.padding.right }, 0.01f)
        // A margin below nothing gives the rest no room: the padding's 200 are asked of 150 across, and fit down
        assertEquals(ButtonRoom(Edges(-50f, -37.5f, -50f, -37.5f), 0f, Edges(100f, 75f, 100f, 75f)), room("margin: -50px; padding: 100px", 150f, 200f))
    }

    @Test
    fun aBarDrawsItsWidgetsRulesButNotAButtonsOnes() {
        val bar = ButtonStyle.bar(
            "* { background-color: #202020; border: 1px solid white } QPushButton { background-color: #000080 } QWidget:hover { background: red } " +
                "QWidget { border-radius: 4px }",
        )
        assertEquals(LabelFill.Solid(0xFF202020.toInt()), bar.background)
        assertEquals(1f, bar.drawnBorderWidth)
        assertEquals(4f, bar.borderRadius)
        assertEquals(LabelFill.Solid(0xFF202020.toInt()), ButtonStyle.bar("background: #202020").background)
        // Ranked as a button's sheet is: the widget's rule over `*` wherever it is, and nothing is under a pointer
        assertEquals(LabelFill.Solid(0xFF202020.toInt()), ButtonStyle.bar("QWidget { background: #202020 } * { background: red }").background)
        assertEquals(LabelFill.Solid(0xFF202020.toInt()), ButtonStyle.bar("QWidget:!hover { background: #202020 }").background)
    }
}
