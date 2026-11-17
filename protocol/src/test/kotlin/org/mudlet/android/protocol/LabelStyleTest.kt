package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The style sheet subset the app draws of a label (docs/phase-f.md, "Labels"). */
class LabelStyleTest {
    @Test
    fun colours() {
        assertEquals(0xFFFF0000.toInt(), CssColor.parse("red"))
        assertEquals(0xFF2F4F4F.toInt(), CssColor.parse("DarkSlateGray"))
        assertEquals(0, CssColor.parse("transparent"))
        assertEquals(0xFFAABBCC.toInt(), CssColor.parse("#abc"))
        assertEquals(0xFF102030.toInt(), CssColor.parse("#102030"))
        // Qt's eight digits put the alpha first
        assertEquals(0x80102030.toInt(), CssColor.parse("#80102030"))
        assertEquals(0xFF0A141E.toInt(), CssColor.parse("rgb(10, 20, 30)"))
        assertEquals(0xFFFF8000.toInt(), CssColor.parse("rgb(100%, 50%, 0%)"))
        assertEquals(0x7F0A141E, CssColor.parse("rgba(10,20,30,127)"))
        assertEquals(0x800A141E.toInt(), CssColor.parse("rgba(10, 20, 30, 0.5)"))
        assertEquals(0x800A141E.toInt(), CssColor.parse("rgba(10, 20, 30, 50%)"))
        assertNull(CssColor.parse("#12345"))
        assertNull(CssColor.parse("#ggg"))
        assertNull(CssColor.parse("rgb(1, 2)"))
        assertNull(CssColor.parse("notacolour"))
    }

    @Test
    fun aGeyserLabelsSheet() {
        val style = LabelStyle.parse(
            """
            background-color: rgba(0, 0, 0, 50%); /* half see-through */
            border: 2px solid white;
            border-radius: 5px;
            color: #ffd700;
            font-size: 12pt;
            font-weight: bold;
            font-style: italic;
            font-family: "Bitstream Vera Sans Mono", monospace;
            padding: 1px 2px;
            margin: 3px;
            qproperty-alignment: 'AlignRight | AlignBottom';
            """.trimIndent(),
        )
        assertEquals(LabelFill.Solid(0x80000000.toInt()), style.background)
        assertEquals(2f, style.borderWidth)
        assertTrue(style.borderStyled)
        assertEquals(2f, style.drawnBorderWidth)
        assertEquals(0xFFFFFFFF.toInt(), style.borderColor)
        assertEquals(5f, style.borderRadius)
        assertEquals(0xFFFFD700.toInt(), style.color)
        assertEquals(16f, style.fontSizePx)
        assertEquals(700, style.fontWeight)
        assertEquals(true, style.italic)
        assertEquals("Bitstream Vera Sans Mono", style.fontFamily)
        assertEquals(Edges(1f, 2f, 1f, 2f), style.padding)
        assertEquals(Edges(3f, 3f, 3f, 3f), style.margin)
        assertEquals(HAlign.RIGHT, style.hAlign)
        assertEquals(VAlign.BOTTOM, style.vAlign)
    }

    @Test
    fun aBorderWithoutAStyleIsNotDrawn() {
        val style = LabelStyle.parse("border-width: 3px; border-color: red;")
        assertEquals(3f, style.borderWidth)
        assertFalse(style.borderStyled)
        assertEquals(0f, style.drawnBorderWidth)
        assertEquals(3f, LabelStyle.parse("border-width: 3px; border-style: dashed").drawnBorderWidth)
        assertEquals(0f, LabelStyle.parse("border: 3px solid red; border-style: none").drawnBorderWidth)
    }

    @Test
    fun onlyTheLabelsOwnRulesApply() {
        val style = LabelStyle.parse(
            "color: red; QLabel { background-color: blue; } QLabel:hover { color: green; } QPushButton { color: pink } " +
                "QLabel#other { color: gray } TLabel, QFrame { font-size: 10px }",
        )
        assertEquals(0xFFFF0000.toInt(), style.color)
        assertEquals(LabelFill.Solid(0xFF0000FF.toInt()), style.background)
        assertEquals(10f, style.fontSizePx)
    }

    @Test
    fun aTextBoxsSheetTakesItsOwnRulesAndNotALabels() {
        val sheet = "padding: 2px; QPlainTextEdit { color: red; } QLabel { color: blue; } TTextBox { font-size: 12px } QPlainTextEdit:focus { color: green }"
        val box = LabelStyle.parse(sheet, LabelStyle.TEXT_BOX_SELECTORS)
        assertEquals(0xFFFF0000.toInt(), box.color)
        assertEquals(12f, box.fontSizePx)
        assertEquals(Edges(2f, 2f, 2f, 2f), box.padding)
        assertEquals(0xFF0000FF.toInt(), LabelStyle.parse(sheet).color)
        assertNull(LabelStyle.parse(sheet).fontSizePx)
    }

    @Test
    fun gradientsAndImages() {
        val style = LabelStyle.parse(
            "background-color: qlineargradient(spread:pad, x1:0, y1:0, x2:0, y2:1, stop:0 rgba(255, 0, 0, 255), stop:1 #0000ff);" +
                "border-image: url(\"/data/hp.png\") 0 0 0 0 stretch stretch;",
        )
        assertEquals(LabelFill.Gradient(0f, 0f, 0f, 1f, listOf(0f to 0xFFFF0000.toInt(), 1f to 0xFF0000FF.toInt())), style.background)
        assertEquals("/data/hp.png", style.borderImage)
        val tiled = LabelStyle.parse("background: #222 url(tile.png) repeat;")
        assertEquals(LabelFill.Solid(0xFF222222.toInt()), tiled.background)
        assertEquals("tile.png", tiled.backgroundImage)
        // Radial and conical gradients are drawn as their first colour
        assertEquals(
            LabelFill.Solid(0xFF00FF00.toInt()),
            LabelStyle.parse("background: qradialgradient(cx:0.5, cy:0.5, radius:1, stop:0 lime, stop:1 black)").background,
        )
    }

    @Test
    fun lengthsInPixelsOrPoints() {
        assertEquals(4f, LabelStyle.length("4"))
        assertEquals(4f, LabelStyle.length("4px"))
        assertEquals(12f, LabelStyle.length("9pt"))
        assertEquals(1.5f, LabelStyle.length(".5PX")!! * 3)
        assertNull(LabelStyle.length("2em"))
        assertNull(LabelStyle.length("bold"))
    }

    @Test
    fun theShorthandsSetWhatTheyDoNotNameBackToNone() {
        val background = LabelStyle.parse("background: #222 url(tile.png); background: red")
        assertEquals(LabelFill.Solid(0xFFFF0000.toInt()), background.background)
        assertNull(background.backgroundImage)
        assertNull(LabelStyle.parse("background-color: red; background: url(tile.png)").background)
        // `background-color` names only the colour
        assertEquals("tile.png", LabelStyle.parse("background: url(tile.png); background-color: red").backgroundImage)
        val border = LabelStyle.parse("border: 3px solid red; border: 2px")
        assertEquals(2f, border.borderWidth)
        assertFalse(border.borderStyled)
        assertNull(border.borderColor)
        assertEquals(0f, LabelStyle.parse("border-width: 3px; border-style: solid; border: blue").borderWidth)
    }

    @Test
    fun whatQtDrawsAsNothingIsNotDrawn() {
        // A negative radius is none, a size of 0 or less keeps the one before it, and one stop is one colour
        assertEquals(0f, LabelStyle.parse("border-radius: 4px; border-radius: -3px").borderRadius)
        assertEquals(10f, LabelStyle.parse("font-size: 10px; font-size: 0").fontSizePx)
        assertEquals(10f, LabelStyle.parse("font-size: 10px; font-size: -2pt").fontSizePx)
        assertEquals(
            LabelFill.Solid(0xFF00FF00.toInt()),
            LabelStyle.parse("background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0.5 lime)").background,
        )
        // A size in px is a whole number to Qt, so one under half a pixel is 0 and keeps the one before it too
        assertEquals(10f, LabelStyle.parse("font-size: 10px; font-size: 0.3px").fontSizePx)
        assertEquals(10f, LabelStyle.parse("font-size: 10px; font-size: .0001").fontSizePx)
        assertEquals(1f, LabelStyle.parse("font-size: 0.5px").fontSizePx)
        assertEquals(13f, LabelStyle.parse("font-size: 12.5px").fontSizePx)
        assertEquals(10f * LabelStyle.PX_PER_PT, LabelStyle.parse("font-size: 10PT").fontSizePx)
    }

    @Test
    fun noLengthIsLongerThanAScreenCouldShow() {
        // Thirty-nine digits are a float that is infinite once a screen's density multiplies it; forty are infinite
        // as read
        for (huge in listOf("90000", "2" + "0".repeat(38), "1" + "0".repeat(39))) {
            assertEquals(huge, LabelStyle.MAX_LENGTH, LabelStyle.length(huge))
            assertEquals(huge, LabelStyle.MAX_LENGTH, LabelStyle.length("${huge}px"))
            assertEquals(huge, LabelStyle.MAX_LENGTH, LabelStyle.length("${huge}pt"))
            assertEquals(huge, -LabelStyle.MAX_LENGTH, LabelStyle.length("-$huge"))
            val style = LabelStyle.parse("border-radius: ${huge}px; border: ${huge}px solid red; padding: $huge; margin: -$huge 0; font-size: ${huge}pt")
            assertEquals(huge, 4096f, style.borderRadius)
            assertEquals(huge, 4096f, style.drawnBorderWidth)
            assertEquals(huge, Edges(4096f, 4096f, 4096f, 4096f), style.padding)
            assertEquals(huge, Edges(-4096f, 0f, -4096f, 0f), style.margin)
            assertEquals(huge, 4096f, style.fontSizePx)
        }
        // A point is more than a pixel
        assertEquals(4096f, LabelStyle.length("4000pt"))
        assertEquals(4095.5f, LabelStyle.length("4095.5px"))
    }

    @Test
    fun aGradientsNumbersAreOnesAScreenCanDraw() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        // Android has no gradient between points that are not numbers or are infinitely far apart
        val gradient = LabelStyle.parse("background: qlineargradient(x1:NaN, y1:-Infinity, x2:1e39, y2:0.5, stop:0 red, stop:NaN lime, stop:1 blue)").background
        assertEquals(LabelFill.Gradient(0f, -4096f, 4096f, 0.5f, listOf(0f to red, 1f to blue)), gradient)
        // The one stop left of two is one colour
        assertEquals(LabelFill.Solid(red), LabelStyle.parse("background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 red, stop:NaN blue)").background)
    }

    @Test
    fun noneTakesAwayOnlyWhatQtTakesAwayWithIt() {
        // `background-image` takes only a file, so `none` leaves the one before it; the shorthand takes it away
        assertEquals("tile.png", LabelStyle.parse("background-image: url(tile.png); background-image: none").backgroundImage)
        assertNull(LabelStyle.parse("background-image: url(tile.png); background: none").backgroundImage)
        // In the shorthand `none` is the image's, not the colour's
        assertEquals(LabelFill.Solid(0xFFFF0000.toInt()), LabelStyle.parse("background: red none").background)
        assertNull(LabelStyle.parse("background-color: red; background-color: none").background)
        assertNull(LabelStyle.parse("border-image: url(frame.png); border-image: none").borderImage)
    }

    @Test
    fun whatItCannotReadLeavesTheRestAlone() {
        val style = LabelStyle.parse("color: nonsense; font-size: large; padding: 1px 2px 3px 4px 5px; ; : ; background-color: #00ff00 !important")
        assertNull(style.color)
        assertNull(style.fontSizePx)
        assertEquals(Edges.ZERO, style.padding)
        assertEquals(LabelFill.Solid(0xFF00FF00.toInt()), style.background)
        assertEquals(LabelStyle(), LabelStyle.parse(""))
    }
}
