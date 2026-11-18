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
        // Qt rounds a channel and an alpha above 1, and cuts a fraction of 1 short
        assertEquals(0xFFFF7F00.toInt(), CssColor.parse("rgb(100%, 50%, 0%)"))
        assertEquals(0xFF800102.toInt(), CssColor.parse("rgb(127.5, 0.5, 1.5)"))
        assertEquals(0x7F0A141E, CssColor.parse("rgba(10,20,30,127)"))
        assertEquals(0x7F0A141E, CssColor.parse("rgba(10, 20, 30, 0.5)"))
        assertEquals(0x7F0A141E, CssColor.parse("rgba(10, 20, 30, 50%)"))
        assertEquals(0xFE0A141E.toInt(), CssColor.parse("rgba(10, 20, 30, 0.999)"))
        assertEquals(0x020A141E, CssColor.parse("rgba(10, 20, 30, 1.5)"))
        assertEquals(0xFF010203.toInt(), CssColor.parse("rgb(1 , 2/3)"))
        assertNull(CssColor.parse("#12345"))
        assertNull(CssColor.parse("#ggg"))
        assertNull(CssColor.parse("rgb(1, 2)"))
        assertNull(CssColor.parse("rgb(1 2 3)"))
        assertNull(CssColor.parse("rgb(1,,2,3)"))
        assertNull(CssColor.parse("rgb(1px, 2, 3)"))
    }

    @Test
    fun qtReadsHueAndEveryWayOfWritingAColourAndDrawsWhatItCannotNameBlack() {
        // Each as Qt 6.4.2 sets a label's text colour from it
        assertEquals(0xFF2E8729.toInt(), CssColor.parse("hsl(117, 136, 88)"))
        assertEquals(0x7FFF0101, CssColor.parse("hsla(0, 255, 128, 0.5)"))
        assertEquals(0xFFC86464.toInt(), CssColor.parse("hsv(0, 128, 200)"))
        assertEquals(0x3F6464C8, CssColor.parse("hsva(240, 128, 200, 0.25)"))
        // A hue's percentage is of 359
        assertEquals(0xFF01FFFF.toInt(), CssColor.parse("hsl(50%, 255, 128)"))
        assertEquals(0xFF80FF00.toInt(), CssColor.parse("hsv(25%, 100%, 100%)"))
        assertEquals(0xFFFFFFFF.toInt(), CssColor.parse("hsl(-1, 0, 255)"))
        assertEquals(0xFFFF01FF.toInt(), CssColor.parse("hsl(300, 255, 128)"))
        assertEquals(0xFF808080.toInt(), CssColor.parse("hsl(-1, 255, 128)"))
        assertEquals(0xFF124578.toInt(), CssColor.parse("#123456789"))
        assertEquals(0xFF12569A.toInt(), CssColor.parse("#123456789abc"))
        assertEquals(0xFFFF0000.toInt(), CssColor.parse("\"red\""))
        // A name or a string Qt does not know, and a number out of its range, are its invalid colour, which it draws black
        for (black in listOf("notacolour", "'nosuch'", "rgb(256, 0, 0)", "rgb(255.5, 0, 0)", "hsl(360, 0, 0)", "rgba(1, 2, 3, 1.4)", "rgba(1, 2, 3, 0.4%)")) {
            assertEquals(black, CssColor.INVALID, CssColor.parse(black))
        }
        // Its names of functions are case-sensitive, and an alpha is there exactly when the name asks for one
        for (none in listOf("RGB(1, 2, 3)", "Rgb(1, 2, 3)", "rgba(1, 2, 3)", "rgb(1, 2, 3, 4)", "hsl(1, 2, 3, 4)", "rgbx(1, 2, 3)", "none", "solid")) {
            assertNull(none, CssColor.parse(none))
        }
        // Not read: Qt takes this from the palette, which the app does not have (docs/divergences.md)
        assertNull(CssColor.parse("palette(highlight)"))
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
        assertEquals(LabelFill.Solid(0x7F000000), style.background)
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
    fun aNativeTopEdgeLeavesNoRoomForTheBorder() {
        // As Qt 6.4.2 places a label's text: where the top edge is native it leaves the others' width no room, and
        // where a radius or a border image takes the native frame away it does
        for (native in listOf("border-top-style: native", "border-style: native solid", "border-top: 6px native red")) {
            assertEquals(native, 0f, LabelStyle.parse("background: blue; border: 6px solid red; $native").drawnBorderWidth)
        }
        assertEquals(6f, LabelStyle.parse("background: blue; border-top-style: native; border: 6px solid red").drawnBorderWidth)
        assertEquals(6f, LabelStyle.parse("background: blue; border: 6px solid red; border-top-style: native; border-radius: 0px").drawnBorderWidth)
        assertEquals(6f, LabelStyle.parse("border: 6px solid red; border-top-style: native; border-image: url(frame.png)").drawnBorderWidth)
    }

    @Test
    fun onlyTheLabelsOwnRulesApply() {
        val style = LabelStyle.parse(
            "* { color: red; } QLabel { background-color: blue; } QLabel:hover { color: green; } QPushButton { color: pink } " +
                "QLabel#other { color: gray } TLabel, QFrame { font-size: 10px }",
        )
        assertEquals(0xFFFF0000.toInt(), style.color)
        assertEquals(LabelFill.Solid(0xFF0000FF.toInt()), style.background)
        assertEquals(10f, style.fontSizePx)
    }

    @Test
    fun aTextBoxsSheetTakesItsOwnRulesAndNotALabels() {
        val sheet = "* { padding: 2px; } QPlainTextEdit { color: red; } QLabel { color: blue; } TTextBox { font-size: 12px } QPlainTextEdit:focus { color: green }"
        val box = LabelStyle.parse(sheet, LabelStyle.TEXT_BOX_SELECTORS)
        assertEquals(0xFFFF0000.toInt(), box.color)
        assertEquals(12f, box.fontSizePx)
        assertEquals(Edges(2f, 2f, 2f, 2f), box.padding)
        assertEquals(0xFF0000FF.toInt(), LabelStyle.parse(sheet).color)
        assertNull(LabelStyle.parse(sheet).fontSizePx)
    }

    @Test
    fun rulesRankAsQtRanksThemWhereverTheyAre() {
        val navy = LabelFill.Solid(0xFF000080.toInt())
        val red = LabelFill.Solid(0xFFFF0000.toInt())
        fun label(sheet: String) = LabelStyle.parse(sheet).background
        fun box(sheet: String) = LabelStyle.parse(sheet, LabelStyle.TEXT_BOX_SELECTORS).background
        // A rule naming a type is over one for `*`, before it or after it
        assertEquals(navy, label("QLabel { background: navy } * { background: red }"))
        assertEquals(navy, label("* { background: red } QLabel { background: navy }"))
        assertEquals(navy, box("QPlainTextEdit { background: navy } * { background: red }"))
        // Each class a widget inherits counts the same, so the later rule wins
        assertEquals(red, label("TLabel { background: navy } QLabel { background: red }"))
        assertEquals(navy, label("QLabel { background: red } QWidget { background: navy }"))
        assertEquals(red, box("TTextBox { background: navy } QPlainTextEdit { background: red }"))
        // The class it is counts for more than a type
        assertEquals(navy, label(".TLabel { background: navy } QLabel { background: red }"))
        assertEquals(navy, box(".TTextBox { background: navy } QPlainTextEdit { background: red }"))
        // A rule for a group ranks as the most specific of its selectors that apply
        assertEquals(navy, label("QPushButton, QLabel { background: navy } * { background: red }"))
        assertEquals(red, label("*, QLabel { background: navy } QWidget { background: red }"))
        assertEquals(navy, label("*, QLabel { background: navy } * { background: red }"))
        assertEquals(navy, label("QLabel, * { background: navy } * { background: red }"))
    }

    @Test
    fun aLabelAndATextBoxAreEachClassQtKnowsThemBy() {
        for (selector in listOf("TLabel", ".TLabel", "QLabel", "QFrame", "QWidget", "QObject", "*")) {
            assertEquals(selector, 0xFF000080.toInt(), LabelStyle.parse("$selector { color: navy }").color)
        }
        for (selector in listOf("TTextBox", ".TTextBox", "QPlainTextEdit", "QAbstractScrollArea", "QFrame", "QWidget", "QObject", "*")) {
            assertEquals(selector, 0xFF000080.toInt(), LabelStyle.parse("$selector { color: navy }", LabelStyle.TEXT_BOX_SELECTORS).color)
        }
        // `.QLabel` is a widget that is a QLabel and no class of it, which Mudlet's is; Qt's names are in their own case
        for (selector in listOf(".QLabel", ".QFrame", "qlabel", "QAbstractScrollArea", "QPlainTextEdit", "QPushButton", "#label_profile_name")) {
            assertNull(selector, LabelStyle.parse("$selector { color: navy }").color)
        }
        for (selector in listOf(".QPlainTextEdit", "qplaintextedit", "QTextEdit", "QLabel", "#textEdit_profile_name")) {
            assertNull(selector, LabelStyle.parse("$selector { color: navy }", LabelStyle.TEXT_BOX_SELECTORS).color)
        }
    }

    @Test
    fun aSheetIsItsRulesOrItsBareDeclarations() {
        val navy = LabelFill.Solid(0xFF000080.toInt())
        val red = LabelFill.Solid(0xFFFF0000.toInt())
        val twenty = Edges(20f, 20f, 20f, 20f)
        // Qt reads rules up to what is not one, so not the colour `setBackgroundColor()` writes after a label's sheet
        val added = "\nbackground-color: rgba(255, 0, 0, 255);"
        val coloured = LabelStyle.parse("QLabel { color: yellow }$added")
        assertNull(coloured.background)
        assertEquals(0xFFFFFF00.toInt(), coloured.color)
        assertEquals(navy, LabelStyle.parse("QLabel { background: navy }$added").background)
        assertEquals(navy, LabelStyle.parse("* { background: navy }$added").background)
        assertEquals(navy, LabelStyle.parse("QPlainTextEdit { background: navy } background: red;", LabelStyle.TEXT_BOX_SELECTORS).background)
        // A sheet that starts with bare declarations is the block of a `*` rule, where a rule is lost up to the
        // next `;`, and with none after it the rest of the sheet
        assertEquals(red, LabelStyle.parse("background: red; QLabel { background: navy }").background)
        val lost = LabelStyle.parse("background: red; QLabel { background: navy } padding: 20px")
        assertEquals(red, lost.background)
        assertEquals(Edges.ZERO, lost.padding)
        val kept = LabelStyle.parse("background: red; QLabel { color: yellow }; padding: 20px")
        assertEquals(red, kept.background)
        assertNull(kept.color)
        assertEquals(twenty, kept.padding)
        assertEquals(red, LabelStyle.parse("background: red; QPlainTextEdit { background: navy }", LabelStyle.TEXT_BOX_SELECTORS).background)
        // A `*` is no declaration's start, so with such a rule after them Qt reads none of it
        assertEquals(LabelStyle(), LabelStyle.parse("background: red; * { background: navy }"))
        // Qt reads the whole sheet as that block too where rules come first: they are lost as one that starts the
        // block, so what follows them is, unless the sheet starts with a space, which has Qt stop at the next `;`
        assertNull(LabelStyle.parse("QLabel{padding: 20px} x:y; background:red").background)
        val spaced = LabelStyle.parse(" QLabel{padding: 20px} x:y; background:red")
        assertEquals(red, spaced.background)
        assertEquals(twenty, spaced.padding)
        // Those are a `*` rule's, under the label's own rule
        assertEquals(navy, LabelStyle.parse(" QLabel{background: navy} x:y; background:red").background)
    }

    @Test
    fun whatQtCannotReadOfASheetIsLost() {
        val red = LabelFill.Solid(0xFFFF0000.toInt())
        val twenty = Edges(20f, 20f, 20f, 20f)
        fun label(sheet: String) = LabelStyle.parse(sheet)
        // A declaration is followed by a `;` or the closing brace, or the rule is lost and all after it: with no `;`
        // the next declaration's name is in its value, as when `setBackgroundColor()` writes after a sheet without one
        assertEquals(LabelStyle(), label("border: 10px solid blue\nbackground-color: rgba(255, 0, 0, 255);"))
        assertEquals(10f, label("border: 10px solid blue;\nbackground-color: rgba(255, 0, 0, 255);").drawnBorderWidth)
        assertEquals(LabelStyle(), label("QLabel { padding: 20px\n background: red; } QLabel { border: 5px solid red }"))
        assertEquals(5f, label("QLabel { border: 5px solid red } QLabel { padding: 20px\n background: red; }").drawnBorderWidth)
        assertNull(label("QLabel { border: 5px solid red } QLabel { padding: 20px\n background: red; }").background)
        // So is a rule that is not closed, or that has what starts no declaration
        assertEquals(LabelStyle(), label("QLabel { background: red"))
        assertEquals(LabelStyle(), label("QLabel { background: red; // a note\n padding: 20px }"))
        assertEquals(LabelStyle(), label("padding: 20px; ; : ; background-color: #00ff00"))
        assertEquals(red, label("QLabel { background: red } // a note\nQLabel { padding: 20px }").background)
        assertEquals(Edges.ZERO, label("QLabel { background: red } // a note\nQLabel { padding: 20px }").padding)
        // A value ends at what is no part of one, and what follows it is neither `;` nor brace
        assertEquals(LabelStyle(), label("QLabel { background: red; qproperty-alignment: AlignLeft | AlignTop; }"))
        assertEquals(red, label("QLabel { background: red; qproperty-alignment: 'AlignLeft | AlignTop'; }").background)
        assertEquals(LabelStyle(), label("QLabel { background: red; padding: 20px !imp; }"))
        assertEquals(twenty, label("QLabel { background: red; padding: 20px ! important }").padding)
        assertEquals(LabelStyle(), label("QLabel { background: red; font-family: Sans.Serif }"))
        assertEquals(red, label("QLabel { background: red; font-family: Sans .5 }").background)
        // What starts with a name and is no declaration is skipped up to the next `;`: a name with no colon, a value
        // that is none, a `#` with no colour's digits, a string with no closing quote on its line
        for (skipped in listOf("bogus", "color:", "color: ,red", "color: #12345", "color: +red", "font-family: 'Sans\n")) {
            val style = label("QLabel { background: red; $skipped; padding: 20px }")
            assertEquals(skipped, red, style.background)
            assertNull(skipped, style.color)
            assertEquals(skipped, twenty, style.padding)
        }
        // So a `#` that is no colour takes away what the rest of its declaration names
        for (hash in listOf("#12345", "#1234567")) {
            val style = label("QLabel { background: red; border: 5px solid red $hash }")
            assertEquals(hash, red, style.background)
            assertEquals(hash, 0f, style.drawnBorderWidth)
        }
        // A string or a function that is not closed takes the `;` and the brace with it
        assertEquals(LabelStyle(), label("QLabel { background: red; font-family: 'Sans; padding: 20px }"))
        assertEquals(LabelStyle(), label("QLabel { background: red; color: rgb(1, 2; padding: 20px }"))
        assertEquals(LabelStyle(), label("QLabel { background: red; font-family: Sans 'Serif\n; padding: 20px }"))
        // Qt counts the braces from the one before what it skips, so right after the rule's own no `;` ends it
        assertEquals(red, label("QLabel { bogus; background: red }").background)
        assertEquals(LabelStyle(), label("QLabel {bogus; background: red }"))
        assertEquals(5f, label("QLabel {bogus; background: red } QLabel { border: 5px solid red }").drawnBorderWidth)
    }

    @Test
    fun qtReadsNothingAfterACharacterItsScannerDoesNotKnow() {
        val red = LabelFill.Solid(0xFFFF0000.toInt())
        val navy = LabelFill.Solid(0xFF000080.toInt())
        // An unquoted file name with an accented letter of Latin-1, a `?` or an `&` ends the sheet there: the rule
        // it is in is not closed
        for (name in listOf("C:/Users/Jos\u00e9/hp.png", "hp.png?v=2", "a&b.png", "hp\$.png", "hp%20.png")) {
            val style = LabelStyle.parse("QLabel { background: red } QLabel { border-image: url($name) } QLabel { padding: 20px }")
            assertEquals(name, red, style.background)
            assertNull(name, style.borderImage)
            assertEquals(name, Edges.ZERO, style.padding)
            assertEquals(name, name, LabelStyle.parse("QLabel { border-image: url(\"$name\") }").borderImage)
        }
        assertEquals("C:/Users/\u0141ukasz/50%.png", LabelStyle.parse("border-image: url(C:/Users/\u0141ukasz/50%.png)").borderImage)
        assertEquals(LabelStyle(), LabelStyle.parse("background: red; font-family: Jos\u00e9"))
        assertEquals(LabelStyle(), LabelStyle.parse("background: red; color: #; padding: 20px"))
        assertEquals(LabelStyle(), LabelStyle.parse("background: red; color: @; padding: 20px"))
        assertEquals(LabelStyle(), LabelStyle.parse("background: red; color: <red>; padding: 20px"))
        assertEquals(red, LabelStyle.parse("background: red; /* Jos\u00e9's */ padding: 20px").background)
        // Rules of a sheet Qt could read neither as rules nor as a block it leaves unordered, and the first of two
        // that rank the same then wins
        assertEquals(red, LabelStyle.parse("QLabel { background: red } QLabel { background: navy } QLabel { font-family: Jos\u00e9 }").background)
        assertEquals(navy, LabelStyle.parse("QLabel { background: red } QLabel { background: navy } QLabel { font-family: \"Jos\u00e9\" }").background)
        assertEquals(navy, LabelStyle.parse("* { background: red } QLabel { background: navy } QLabel { font-family: Jos\u00e9 }").background)
        // One read as a block the second time is in order
        assertEquals(navy, LabelStyle.parse("QLabel { background: red } QLabel { background: navy } bogus").background)
    }

    @Test
    fun anUnclosedCommentRunsToTheLastCharacterOfTheSheet() {
        fun read(sheet: String) = LabelStyle.declarations(sheet)
        val both = listOf("color" to "red", "padding" to "2px")
        // Qt's scanner keeps that character, so a closing brace there still closes the rule
        assertEquals(both, read("color: red; padding: 2px /* a note"))
        assertEquals(both, read("color: red; padding: 2px; /* a note"))
        assertEquals(both, read("QLabel { color: red } QLabel { padding: 2px /* a note }"))
        assertEquals(listOf("color" to "red"), read("QLabel { color: red /*}"))
        assertEquals(emptyList<Pair<String, String>>(), read("QLabel { color: red /*/"))
        assertEquals(listOf("color" to "red"), read("QLabel { color: red } QLabel { padding: 2px /*/"))
        assertEquals(listOf("color" to "red"), read("QLabel { color: red } /* QLabel { padding: 2px }"))
        // In a string it is the string's
        assertEquals(listOf("font-family" to "\"a /* b */ c\"", "padding" to "2px"), read("QLabel { font-family: \"a /* b */ c\"; padding: 2px }"))
    }

    @Test
    fun everyCharacterFromU0100IsANameCharacterAndABackslashEscapesOne() {
        fun read(sheet: String) = LabelStyle.declarations(sheet)
        val both = listOf("color" to "red", "padding" to "2px")
        // A symbol, an emoji, a zero-width space, and even an em space or a byte-order mark: Qt's spaces are ASCII
        for (odd in listOf("\u2713", "\uD83D\uDE00", "\u200B", "\u2003", "\uFEFF")) {
            val name = Integer.toHexString(odd.codePointAt(0))
            assertEquals(name, listOf("color" to "red", "${odd}x" to "y", "padding" to "2px"), read("QLabel { color: red; ${odd}x: y; padding: 2px }"))
            assertEquals(name, both + ("${odd}margin" to "3px"), read("QLabel { color: red; padding: 2px; ${odd}margin: 3px }"))
            assertEquals(name, listOf("font-family" to "a${odd}b"), read("QLabel { font-family: a${odd}b }"))
        }
        // So a sheet that starts with a byte-order mark starts with a name: a block's, or a selector no label has
        assertEquals(listOf("\uFEFFcolor" to "red", "padding" to "2px"), read("\uFEFFcolor: red; padding: 2px"))
        assertEquals(listOf("padding" to "2px"), read("\uFEFFQLabel { color: red } QLabel { padding: 2px }"))
        // An escape is the character, in a name, a selector or a string; in hex digits, one that may end a value
        assertEquals(listOf("color" to "red", "qproperty-alignment" to "AlignRight"), read("QLabel { color: red; \\qproperty-alignment: AlignRight }"))
        assertEquals(listOf("color" to "red", "padding" to "2px"), read("QLabel { color: red } QW\\idget { padding: 2px }"))
        assertEquals(listOf("font-family" to "\"a\"b\"", "padding" to "2px"), read("QLabel { font-family: \"a\\\"b\"; padding: 2px }"))
        assertEquals(listOf("font-family" to "a:b", "padding" to "2px"), read("QLabel { font-family: a\\:b; padding: 2px }"))
        assertEquals(listOf("font-family" to "a", "padding" to "2px"), read("QLabel { font-family: a\\3b padding: 2px }"))
    }

    @Test
    fun qtStopsAtAControlCharacterAndAtWhatOnlyLooksLikeAValue() {
        fun read(sheet: String) = LabelStyle.declarations(sheet)
        val first = listOf("color" to "red")
        // A control that is no space ends the sheet, and in a string a NUL or a line's end does: the rule it is in is
        // not closed
        for (control in listOf("\u000B", "\u0002", "\u0000")) {
            assertEquals(control, first, read("QLabel { color: red } QLabel { font-family: a${control}b } QLabel { padding: 2px }"))
        }
        assertEquals(3, read("QLabel { color: red } QLabel { font-family: \"a\u000Bb\" } QLabel { padding: 2px }").size)
        for (end in listOf("\u0000", "\r", "\u000C")) {
            assertEquals(first, read("QLabel { color: red } QLabel { font-family: \"a${end}b\" } QLabel { padding: 2px }"))
        }
        // So does a `%` after a name, where it is not a number's
        assertEquals(first, read("QLabel { color: red } QLabel { font-family: \u0663% } QLabel { padding: 2px }"))
        assertEquals(first, read("QLabel { color: red } QLabel { font-family: a a-5% } QLabel { padding: 2px }"))
        // A function where a declaration starts, or a string in a selector, is where Qt stops reading rules
        assertEquals(first, read("QLabel { color: red } QLabel { margin: 3px; rgb(1, 2, 3); padding: 2px } QLabel { color: blue }"))
        assertEquals(first, read("QLabel { color: red } QLabel\"x\" { margin: 3px } QLabel { padding: 2px }"))
        // A sign with neither a number nor a name after it is no value
        assertEquals(listOf("color" to "red", "padding" to "2px"), read("QLabel { color: red; font-family: - x; padding: 2px }"))
    }

    @Test
    fun qtSkipsTheRulesOfMediaAndOtherKeywords() {
        fun read(sheet: String) = LabelStyle.declarations(sheet)
        val both = listOf("color" to "red", "padding" to "2px")
        val first = listOf("color" to "red")
        // What is in them it never draws on a widget
        assertEquals(both, read("QLabel { color: red } @media screen, print { QLabel { margin: 3px } } QLabel { padding: 2px }"))
        assertEquals(both, read("QLabel { color: red } @page :first { margin: 3px } QLabel { padding: 2px }"))
        assertEquals(both, read("QLabel { color: red } @font-face { a { b } } QLabel { padding: 2px }"))
        // It tells media and a page by how the keyword ends, and reads their blocks as their own
        assertEquals(first, read("QLabel { color: red } @MEDIA screen { margin: 3px } QLabel { padding: 2px }"))
        assertEquals(first, read("QLabel { color: red } @media { QLabel { margin: 3px } } QLabel { padding: 2px }"))
        assertEquals(first, read("QLabel { color: red } @xmedia screen { margin: 3px } QLabel { padding: 2px }"))
        assertEquals(both, read("QLabel { color: red } @mediax screen { margin: 3px } QLabel { padding: 2px }"))
        assertEquals(first, read("QLabel { color: red } @page { margin: 3px; bogus } QLabel { padding: 2px }"))
        // A charset first and imports after it are read, and anywhere else an import is a keyword like any
        assertEquals(both, read("@charset \"utf-8\"; @import url(a.css) screen; @import 'b.css'; QLabel { color: red } QLabel { padding: 2px }"))
        assertEquals(emptyList<Pair<String, String>>(), read("@charset \"utf-8\" ; QLabel { color: red } QLabel { padding: 2px }"))
        assertEquals(first, read("QLabel { color: red } @import \"a.css\"; QLabel { padding: 2px }"))
        // In a selector a keyword is where Qt stops, which an escaped `@` is not
        assertEquals(first, read("QLabel { color: red } x@media x { a { b: c } } QLabel { padding: 2px }"))
        assertEquals(first, read("QLabel { color: red } x @y { b: c } QLabel { padding: 2px }"))
        assertEquals(both, read("QLabel { color: red } a\\@b { b: c } QLabel { padding: 2px }"))
    }

    @Test
    fun qtPassesOverHtmlsCommentMarksBetweenRules() {
        // Each as Qt 6.4.2 draws a label with it
        assertEquals(LabelFill.Solid(0xFF0000FF.toInt()), LabelStyle.parse("<!-- QLabel { background: blue } -->").background)
        val after = LabelStyle.parse("QLabel { color: red } <!--\nQLabel { background: blue } -->")
        assertEquals(0xFFFF0000.toInt(), after.color)
        assertEquals(LabelFill.Solid(0xFF0000FF.toInt()), after.background)
        assertEquals(LabelFill.Solid(0xFF0000FF.toInt()), LabelStyle.parse("QLabel { color: red }-->QLabel { background: blue }").background)
        // Inside a rule they are not space, so Qt stops there
        assertNull(LabelStyle.parse("QLabel { <!-- background: blue }").background)
    }

    @Test
    fun eachPropertyReadsItsColourAsQtDoes() {
        // Each as Qt 6.4.2 draws a label with it
        val red = 0xFFFF0000.toInt()
        val black = CssColor.INVALID
        fun s(sheet: String) = LabelStyle.parse(sheet)
        // One colour, and one Qt cannot read sets the background back to none
        assertNull(s("background-color: red; background-color: rgba(1,2,3)").background)
        assertNull(s("background-color: red blue").background)
        assertNull(s("background-color: red; background-color: red,blue").background)
        assertNull(s("background-color: red; background-color: red/blue").background)
        assertNull(s("background-color: red; background-color: none").background)
        assertEquals(LabelFill.Solid(black), s("background-color: red; background-color: nosuch").background)
        // The shorthand's last word that is not a file, a repeat or another of Qt's own words is its colour
        assertNull(s("background: red 3px").background)
        assertNull(s("background: red; background: none").background)
        assertEquals(LabelFill.Solid(black), s("background: red nosuch").background)
        assertEquals(LabelFill.Solid(0), s("background: red transparent").background)
        assertEquals(LabelFill.Solid(red), s("background: red \"REPEAT-X\" center").background)
        assertEquals(LabelFill.Solid(red), s("background: red solid").background)
        // The text's colour goes back to the label's own where Qt reads none
        for (none in listOf("rgba(1,2,3)", "red blue", "none", "red, blue")) assertNull(none, s("color: red; color: $none").color)
        assertEquals(black, s("color: red; color: nosuch").color)
        // The border reads a width, then a style, then a colour
        assertEquals(red, s("border: 10px solid red nosuch").borderColor)
        assertEquals(10f, s("border: 10 solid red").drawnBorderWidth)
        assertEquals(0f, s("border: red 10px solid").drawnBorderWidth)
        assertEquals(0f, s("border: solid red 10px").drawnBorderWidth)
        assertEquals(0f, s("border: 10px red").drawnBorderWidth)
        // `native` is a style there too, so the colour after it is read
        assertEquals(0xFF0000FF.toInt(), s("border: 3px native blue").borderColor)
        assertEquals(black, s("border: 10px solid nosuch").borderColor)
        assertEquals(black, s("border: 10px solid red; border-color: nosuch").borderColor)
        // A `#` and a name that is not a colour stops Qt reading the declaration, and takes away a gradient
        assertEquals(red, s("color: red; color: #12345").color)
        assertEquals(LabelFill.Solid(red), s("background: red; background: blue #ggg").background)
        assertEquals(red, s("border: 10px solid red; border-color: #12345").borderColor)
        assertNull(s("color: red; color: #").color)
        assertNull(s("background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 #12345, stop:1 red)").background)
        // A gradient's stop Qt reads no colour off is black
        for (stop in listOf("nosuch", "rgba(1,2,3)")) {
            assertEquals(
                LabelFill.Gradient(0f, 0f, 0f, 1f, listOf(0f to black, 1f to black)),
                s("background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 $stop, stop:1 $stop)").background,
            )
        }
    }

    @Test
    fun aBorderColourQtCannotReadIsTheSheetsColourOrNone() {
        // Each as Qt 6.4.2 draws a label with it: one it reads no colour off is the `color` the sheet ends with, and
        // with none clear
        val red = 0xFFFF0000.toInt()
        fun s(sheet: String) = LabelStyle.parse(sheet)
        val blue = 0xFF0000FF.toInt()
        for (unread in listOf(
            "border: 10px solid rgba(1,2,3)",
            "border: 10px solid red; border-color: none",
            "border: 1px solid none red",
            // A gradient Qt cannot read too
            "border: 10px solid qlineargradientx(x1:0, y1:0, x2:1, y2:0, stop:0 red, stop:1 blue)",
            "border: 10px solid red; border-color: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop: red, stop:1 blue)",
        )) {
            assertEquals(unread, blue, s("$unread; color: blue").borderColor)
            assertEquals(unread, blue, s("color: blue; $unread").borderColor)
            assertEquals(unread, 0, s(unread).borderColor)
            assertEquals(unread, 0, s("$unread; color: blue; color: none").borderColor)
        }
        assertEquals(blue, s("QLabel { border: 10px solid none } * { color: blue }").borderColor)
        assertEquals(red, s("border: 10px solid none; border-color: red").borderColor)
        // One not named stays none, which the app draws in the text's colour and Qt black (docs/divergences.md)
        for (named in listOf("border: 10px solid", "border: 10px solid none; border: 10px solid", "border-width: 10px; border-style: solid")) {
            assertNull(named, s("$named; color: blue").borderColor)
        }
    }

    @Test
    fun aGradientBorderIsTheColourQtDrawsAtTheMiddleOfEachEdge() {
        // Each as Qt 6.4.2 draws a label with it at the middle of each edge, to within its rounding, whatever the `color`
        fun g(arguments: String) = "qlineargradient($arguments)"
        val across = "x1:0, y1:0, x2:1, y2:0"
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        for ((gradient, colour) in listOf(
            g("$across, stop:0 red, stop:1 blue") to 0xFF800080.toInt(),
            g("x1:0, y1:0, x2:0, y2:1, stop:0 #c0c0c0, stop:1 #404040") to 0xFF808080.toInt(),
            g("$across, stop:0 red, stop:0.5 green, stop:1 blue") to 0xFF008000.toInt(),
            g("$across, stop:0.3 red, stop:1 blue") to 0xFFB60049.toInt(),
            g("$across, stop:0.4 green, stop:0 red, stop:1 blue") to 0xFF006B2A.toInt(),
            g("$across, stop:0 rgba(255, 0, 0, 0), stop:1 blue") to 0x800000FF.toInt(),
            g("$across, stop:0 transparent, stop:1 rgba(0, 0, 255, 0)") to 0,
            "QLinearGradient($across, stop:0 red, stop:1 blue)" to 0xFF800080.toInt(),
            // Qt leaves out a stop past 0 to 1, reads a place that is not a number as 0, puts a later stop at a place in
            // the earlier one's, and with no stops draws black to white
            g("$across, stop:-1 red, stop:1 blue") to blue,
            g("$across, stop:-1 red, stop:0.5 lime, stop:1 blue") to 0xFF00FF00.toInt(),
            g("$across, stop:a red, stop:1 blue") to 0xFF800080.toInt(),
            g("$across, stop:0 red, stop:0 lime, stop:1 blue") to 0xFF008080.toInt(),
            g("$across, stop:0 red, stop:1 blue, stop:1 lime") to 0xFF808000.toInt(),
            g("$across, stop:2 red") to 0xFF808080.toInt(),
            "qlineargradient()" to 0xFF000000.toInt(),
            // Past the line between its points, or with no line, the colour of the end
            g("x1:0.6, y1:0, x2:1, y2:0, stop:0 red, stop:1 blue") to red,
            g("x1:0, y1:0, x2:0.25, y2:0, stop:0 red, stop:1 blue") to blue,
            g("$across, stop:0 red, stop:0.2 blue") to blue,
            g("stop:0 red, stop:1 blue") to red,
            g("$across, stop:0 red") to red,
            "qradialgradient(cx:0.5, cy:0.5, radius:0.5, fx:0.5, fy:0.5, stop:0 red, stop:1 blue)" to red,
            // As a background is drawn, its first stop, where Qt has another colour at each edge (docs/divergences.md)
            "qconicalgradient(cx:0.5, cy:0.5, angle:0, stop:0 red, stop:1 blue)" to red,
        )) {
            for (sheet in listOf("border: 11px solid $gradient", "border: 11px solid $gradient; color: yellow", "border: 11px solid green; border-color: $gradient")) {
                assertEquals(sheet, colour, LabelStyle.parse(sheet).borderColor)
                assertEquals(sheet, colour, LabelStyle.parse(sheet, LabelStyle.TEXT_BOX_SELECTORS).borderColor)
                assertEquals(sheet, colour, ButtonStyle.of("", sheet, ButtonState()).style.borderColor)
                assertEquals(sheet, colour, ButtonStyle.bar(sheet).borderColor)
            }
        }
    }

    @Test
    fun aGradientQtCannotReadIsNoColour() {
        // Each as Qt 6.4.2 draws a label with it, with no background
        val across = "x1:0, y1:0, x2:1, y2:0"
        for (gradient in listOf(
            "qlineargradientx($across, stop:0 red, stop:1 blue)",
            "qradialgradientx(stop:0 red, stop:1 blue)",
            "qlineargradient($across, stop: red, stop:1 blue)",
            "qlineargradient($across, stop:0, stop:1 blue)",
            "qlineargradient($across, stop:0 red blue, stop:1 blue)",
            "qlineargradient($across, 0, stop:0 red, stop:1 blue)",
            "qconicalgradient(angle:0, stop: red, stop:1 blue)",
        )) {
            assertNull(gradient, LabelStyle.parse("background: $gradient").background)
        }
    }

    @Test
    fun aGradientIsDrawnWithTheStopsQtKeepsInTheirOrder() {
        val red = 0xFFFF0000.toInt()
        val lime = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        assertEquals(
            LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to lime, 0.5f to red, 1f to blue)),
            LabelStyle.parse("background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:1 blue, stop:0.5 red, stop:0 red, stop:-1 blue, stop:0 lime)").background,
        )
        assertEquals(
            LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to lime, 1f to blue)),
            LabelStyle.parse("background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:-0 red, stop:0 lime, stop:1 blue)").background,
        )
        assertEquals(
            LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to red, 1f to blue)),
            LabelStyle.parse("background: qlineargradient(x1:0, y1:0, x2:1, y2:0, STOP:0 red, Stop:1 blue)").background,
        )
        assertEquals(
            LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to 0xFF000000.toInt(), 1f to 0xFFFFFFFF.toInt())),
            LabelStyle.parse("background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:2 red)").background,
        )
        // A radial one is drawn as its first stop, the one Qt has at its centre
        assertEquals(
            LabelFill.Solid(blue),
            LabelStyle.parse("background: qradialgradient(cx:0.5, cy:0.5, radius:0.5, fx:0.5, fy:0.5, stop:0.5 red, stop:0.2 blue)").background,
        )
    }

    @Test
    fun aStopAtAPercentageIsAtItsNumber() {
        // Each as Qt 6.4.2 draws it, which leaves out 50% as past 1
        val red = 0xFFFF0000.toInt()
        fun g(stops: String) = LabelStyle.parse("background: qlineargradient(x1:0, y1:0, x2:1, y2:0, $stops)").background
        assertEquals(LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to red, 1f to 0xFF0000FF.toInt())), g("stop:0 red, stop:50% lime, stop:1 blue"))
        assertEquals(LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to red, 0.5f to 0xFF00FF00.toInt(), 1f to 0xFF0000FF.toInt())), g("stop:0 red, stop:0.5% lime, stop:1 blue"))
    }

    @Test
    fun theCommaAfterAGradientsArgumentMayBeLeftOut() {
        // Each as Qt 6.4.2 draws it
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        fun g(arguments: String) = LabelStyle.parse("background: qlineargradient($arguments)").background
        assertEquals(LabelFill.Gradient(0f, 0f, 1f, 1f, listOf(0f to red, 1f to blue)), g("x1:0 y1:0 x2:1 y2:1 stop:0 red stop:1 blue"))
        assertEquals(LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to red, 1f to blue)), g("x1 : 0, y1:0, x2:1, y2:0, stop : 0 red stop :1 blue"))
        assertEquals(
            LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to red, 0.5f to 0xFF00FF00.toInt(), 1f to blue)),
            g("x1:0, y1:0, x2:1, y2:0, stop:0 red, stop:0.5 lime stop:1 blue"),
        )
        // A word that is no argument's still takes the gradient away
        for (arguments in listOf(
            "x1:0, y1:0, x2:1, y2:0, stop:0 red, stop:1 blue, x1:0 0",
            "x1:0, y1:0, x2:1, y2:0, stop:0 red, stop:1 blue :0",
            "x1:0, y1:0, x2:1, y2:0, stop:0 red blue stop:1 blue",
            "x1:0, y1:0, x2:1, y2:0, stop:0 red, stop:1 blue, y2 1",
        )) {
            assertNull(arguments, g(arguments))
        }
    }

    @Test
    fun aGradientWithOnlySpaceBeforeACommaIsNoColour() {
        // Each as Qt 6.4.2 draws it, which reads a comma at the very end only with the `)` right after it
        val across = "x1:0, y1:0, x2:1, y2:0"
        fun g(arguments: String) = LabelStyle.parse("background: qlineargradient($arguments)").background
        for (arguments in listOf("$across, stop:0 red, stop:1 blue, ", "$across, stop:0 red,, stop:1 blue", "$across, stop:0 red, stop:1 blue,,", ", $across, stop:0 red, stop:1 blue", ",")) {
            assertNull(arguments, g(arguments))
        }
        val gradient = LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to 0xFFFF0000.toInt(), 1f to 0xFF0000FF.toInt()))
        for (arguments in listOf("$across, stop:0 red, stop:1 blue,", " $across, stop:0 red, stop:1 blue ,")) {
            assertEquals(arguments, gradient, g(arguments))
        }
    }

    @Test
    fun aGradientWithAWordThatIsNotOneOfQtsTermsIsNoColour() {
        // Each as Qt 6.4.2 draws it: a stop's colour, its place and another argument's value are each one term, and a
        // name is a name, so a stray mark after one takes the gradient away where the app drew a stop black
        val across = "x1:0, y1:0, x2:1, y2:0"
        fun g(arguments: String) = LabelStyle.parse("background: qlineargradient($arguments)").background
        for (arguments in listOf(
            "$across, stop:0 red; stop:1 blue",
            "x1:0; y1:0; x2:1; y2:0; stop:0 red; stop:1 blue",
            "x1:0, y1:0, x2:0, y2:1,\n  stop:0 #333333;\n  stop:1 #0000ff",
            "$across, stop:0 red, stop:1 blue.",
            "$across, stop:0 red, stop:1 blue>",
            "$across, stop:0 red, stop:1 blue:",
            "$across, stop:0 red, stop:1 \"blue\"x",
            "$across, stop:0 red, stop:1 rgb(0, 0, 255)x",
            "$across, stop:0 +red, stop:1 blue",
            "$across, stop:0. red, stop:1 blue",
            "x1:0, y1:0, x2:1., y2:0, stop:0 red, stop:1 blue",
            "$across, 1x:1, stop:0 red, stop:1 blue",
        )) {
            assertNull(arguments, g(arguments))
        }
        val blue = 0xFF0000FF.toInt()
        // A name that is no colour is one term, a sign makes one with a number, and Qt passes over a sign alone
        assertEquals(LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to CssColor.INVALID, 1f to blue)), g("$across, stop:0 -red, stop:1 blue"))
        assertEquals(LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0.5f to 0xFFFF0000.toInt(), 1f to blue)), g("$across, stop:+0.5 red, stop:1 'blue'"))
        assertEquals(LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to 0xFFFF0000.toInt(), 1f to blue)), g("x1:+, y1:0, x2:+1, y2:0, -x:1, stop:-0 red, stop:1 blue"))
    }

    @Test
    fun aGradientsArgumentMayHaveSpaceAfterItsColon() {
        // As Qt 6.4.2 draws them, written as Geyser's gauges and Mudlet's own tests write them
        assertEquals(
            LabelFill.Gradient(0f, 0f, 1f, 0f, listOf(0f to 0xFFFF0000.toInt(), 1f to 0xFF0000FF.toInt())),
            LabelStyle.parse("background: qlineargradient(x1: 0, y1: 0, x2: 1, y2: 0, stop: 0 red, stop: 1 blue)").background,
        )
        assertEquals(
            LabelFill.Gradient(
                0f,
                0f,
                0f,
                1f,
                listOf(0f to 0xFF98F041.toInt(), 0.1f to 0xFF8CF029.toInt(), 0.49f to 0xFF66CC00.toInt(), 0.5f to 0xFF52A300.toInt(), 1f to 0xFF66CC00.toInt()),
            ),
            LabelStyle.parse(
                "background-color: QLinearGradient( x1: 0, y1: 0, x2: 0, y2: 1, stop: 0 #98f041, stop: 0.1 #8cf029, stop: 0.49 #66cc00, stop: 0.5 #52a300, stop: 1 #66cc00)",
            ).background,
        )
    }

    @Test
    fun aGradientWhosePointsAreTheSameIsItsFirstStop() {
        // As Qt 6.4.2 draws it, where Android draws its last
        for ((gradient, colour) in listOf(
            "qlineargradient(stop:0 red, stop:1 blue)" to 0xFFFF0000.toInt(),
            "qlineargradient(x1:0.3, y1:0.2, x2:0.3, y2:0.2, stop:0.6 blue, stop:0.4 red)" to 0xFFFF0000.toInt(),
            "qlineargradient(  )" to 0xFF000000.toInt(),
        )) {
            assertEquals(gradient, LabelFill.Solid(colour), LabelStyle.parse("background: $gradient").background)
        }
    }

    @Test
    fun aSheetDrawsTheBoxItselfOrLeavesItToTheLabelsColour() {
        // Qt fills a label with the colour `setBackgroundColor()` gave it only where its sheet draws no background,
        // no image and no border but the native one
        for (leaves in listOf(
            "", "color: yellow", "padding: 10px", "margin: 10px", "font-size: 12pt", "qproperty-alignment: 'AlignCenter'",
            // No style for the top edge
            "border-width: 4px", "border-color: yellow", "border-width: 4px; border-color: yellow", "border-left: 4px solid yellow", "border-bottom-style: solid",
            "border-style: native", "border: native", "border: 4px native yellow", "border-top-style: native", "border-style: native solid",
            "border: 4px solid yellow; border-style: native", "border-radius: -3px",
            // No file, colour or gradient
            "border-image: none", "border-image: url(frame.png); border-image: none", "background: none", "background-color: red; background-color: none",
            "background-image: none", "background: url(tile.png) none", "background-image: url(tile.png); background: none", "background-repeat: no-repeat",
            "outline: 4px solid yellow", "selection-background-color: navy",
        )) {
            assertFalse(leaves, LabelStyle.parse(leaves).drawsBox)
            assertFalse(leaves, LabelStyle.parse("QLabel { $leaves }").drawsBox)
        }
        for (draws in listOf(
            "border: 4px solid yellow", "border: none", "border: 0", "border: nonsense", "border: yellow native", "border: solid", "border-style: solid", "border-style: none",
            "border-style: solid native", "border-top: 4px solid yellow", "border-top: 4px", "border-top: none", "border-top-style: solid",
            "border-radius: 20px", "border-radius: 0", "border-top-left-radius: 20px", "border-bottom-right-radius: 5px", "border-style: native; border-radius: 4px",
            "border-image: url(frame.png)", "background-image: url(tile.png)", "background: url(tile.png)", "background-image: url(tile.png); background-image: none",
            "background: none url(tile.png)", "background: transparent", "background-color: rgba(0, 0, 255, 100)", "background: navy",
            "background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 red, stop:1 blue)", "image: url(icon.png)", "image: none",
        )) {
            assertTrue(draws, LabelStyle.parse(draws).drawsBox)
            assertTrue(draws, LabelStyle.parse("QLabel { $draws }").drawsBox)
        }
        // The colour Mudlet writes after a sheet of rules is not read, so it is drawn where the rules leave the box
        val added = "\nbackground-color: rgba(255, 0, 0, 255);"
        assertFalse(LabelStyle.parse("QLabel { color: yellow }$added").drawsBox)
        assertTrue(LabelStyle.parse("QLabel { border: 2px solid white; border-radius: 6px }$added").drawsBox)
        assertNull(LabelStyle.parse("QLabel { border: 2px solid white; border-radius: 6px }$added").background)
    }

    @Test
    fun gradientsAndImages() {
        val style = LabelStyle.parse(
            "background-color: qlineargradient(spread:pad, x1:0, y1:0, x2:0, y2:1, stop:0 rgba(255, 0, 0, 255), stop:1 #0000ff);" +
                "border-image: url(\"/data/hp.png\") 0 0 0 0 stretch stretch;",
        )
        assertEquals(LabelFill.Gradient(0f, 0f, 0f, 1f, listOf(0f to 0xFFFF0000.toInt(), 1f to 0xFF0000FF.toInt())), style.background)
        assertEquals("/data/hp.png", style.borderImage)
        val tiled = LabelStyle.parse("background: #222 url(tile.png) repeat-x;")
        assertEquals(LabelFill.Solid(0xFF222222.toInt()), tiled.background)
        assertEquals("tile.png", tiled.backgroundImage)
        // `repeat` is not one of Qt's repeats, so it reads it as a colour it does not know, which it draws black
        assertEquals(LabelFill.Solid(CssColor.INVALID), LabelStyle.parse("background: #222 url(tile.png) repeat;").background)
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
    fun aBoxsLengthsAreInPixelsAndAnyOtherUnitIsNone() {
        // Qt reads a box's lengths in px or with no unit, and one in pt, or cm, as 0, which takes away the one before
        assertEquals(Edges.ZERO, LabelStyle.parse("padding: 30pt").padding)
        assertEquals(Edges.ZERO, LabelStyle.parse("padding: 10px; padding: 30pt").padding)
        assertEquals(Edges(0f, 5f, 0f, 5f), LabelStyle.parse("padding: 10px; padding: 30pt 5px").padding)
        assertEquals(Edges.ZERO, LabelStyle.parse("padding: 10px; padding: 2cm").padding)
        assertEquals(Edges.ZERO, LabelStyle.parse("margin: 10px; margin: 30PT").margin)
        assertEquals(Edges(30f, 30f, 30f, 30f), LabelStyle.parse("margin: 30").margin)
        assertEquals(0f, LabelStyle.parse("border: 8pt solid red").drawnBorderWidth)
        assertEquals(0f, LabelStyle.parse("border: 4px solid red; border: 8pt solid lime").drawnBorderWidth)
        assertEquals(0f, LabelStyle.parse("border: 4px solid red; border-width: 8pt").drawnBorderWidth)
        assertEquals(8f, LabelStyle.parse("border: 8 solid red").drawnBorderWidth)
        assertEquals(0f, LabelStyle.parse("border-radius: 40px; border-radius: 20pt").borderRadius)
        assertEquals(40f, LabelStyle.parse("border-radius: 40").borderRadius)
        // However long the number
        assertEquals(Edges.ZERO, LabelStyle.parse("padding: 10px; padding: ${"9".repeat(40)}pt").padding)
        // The app has no font of Qt's to measure em and ex by, so those leave what was set; the `border` shorthand
        // has set the width back to none by then
        assertEquals(Edges(10f, 10f, 10f, 10f), LabelStyle.parse("padding: 10px; padding: 2em").padding)
        assertEquals(4f, LabelStyle.parse("border: 4px solid red; border-width: 1ex").drawnBorderWidth)
        assertEquals(0f, LabelStyle.parse("border: 4px solid red; border: 2em solid lime").drawnBorderWidth)
        assertEquals(0f, LabelStyle.parse("border: 2em solid red").drawnBorderWidth)
        assertEquals(Edges(30f, 30f, 30f, 30f), LabelStyle.parse("padding: 30px", LabelStyle.TEXT_BOX_SELECTORS).padding)
        assertEquals(Edges.ZERO, LabelStyle.parse("padding: 10px; padding: 30pt", LabelStyle.TEXT_BOX_SELECTORS).padding)
    }

    @Test
    fun aFontSizeNeedsItsUnit() {
        // Qt takes a number with no unit, or another than px and pt, for no size, and keeps the one before it
        assertNull(LabelStyle.parse("font-size: 30").fontSizePx)
        assertEquals(20f, LabelStyle.parse("font-size: 20px; font-size: 30").fontSizePx)
        assertEquals(20f, LabelStyle.parse("font-size: 20px; font-size: 30.0").fontSizePx)
        assertEquals(20f, LabelStyle.parse("font-size: 20px; font-size: 2em").fontSizePx)
        assertEquals(20f, LabelStyle.parse("font-size: 20px; font-size: 5mm").fontSizePx)
        assertEquals(40f, LabelStyle.parse("font-size: 20px; font-size: 30PT").fontSizePx)
        assertEquals(30f, LabelStyle.parse("font-size: 20pt; font-size: 30px").fontSizePx)
        assertNull(LabelStyle.parse("font-size: 30", LabelStyle.TEXT_BOX_SELECTORS).fontSizePx)
        assertEquals(30f, LabelStyle.fontSize("30px"))
        assertEquals(40f, LabelStyle.fontSize(" 30pt "))
        assertNull(LabelStyle.fontSize("30"))
        assertNull(LabelStyle.fontSize("large"))
        // A space before the unit ends the number, which then has none
        assertNull(LabelStyle.fontSize("30 pt"))
        assertNull(LabelStyle.parse("font-size: 30 px").fontSizePx)
        assertEquals(20f, LabelStyle.parse("font-size: 20px; font-size: 30 pt").fontSizePx)
        assertEquals(20f, LabelStyle.parse("font-size: 20px; font-size: 30\tpx").fontSizePx)
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
        assertEquals(10f, LabelStyle.parse("font-size: 10px; font-size: 0px").fontSizePx)
        assertEquals(10f, LabelStyle.parse("font-size: 10px; font-size: -2pt").fontSizePx)
        assertEquals(
            LabelFill.Solid(0xFF00FF00.toInt()),
            LabelStyle.parse("background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0.5 lime)").background,
        )
        // A size in px is a whole number to Qt, so one under half a pixel is 0 and keeps the one before it too
        assertEquals(10f, LabelStyle.parse("font-size: 10px; font-size: 0.3px").fontSizePx)
        assertEquals(10f, LabelStyle.parse("font-size: 10px; font-size: .0001px").fontSizePx)
        assertEquals(1f, LabelStyle.parse("font-size: 0.5px").fontSizePx)
        assertEquals(13f, LabelStyle.parse("font-size: 12.5px").fontSizePx)
        assertEquals(10f * LabelStyle.PX_PER_PT, LabelStyle.parse("font-size: 10PT").fontSizePx)
        // A rich text's `style` is read by the same rule
        assertEquals(13f, LabelStyle.fontSize("12.5px"))
        assertEquals(12.5f * LabelStyle.PX_PER_PT, LabelStyle.fontSize("12.5pt"))
        for (none in listOf("0px", "0pt", "-5px", "-2pt", "0.4px")) assertNull(none, LabelStyle.fontSize(none))
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
        // It takes away the file named before it in the same shorthand, and not one named after it
        assertNull(LabelStyle.parse("background: url(tile.png) none").backgroundImage)
        assertEquals("tile.png", LabelStyle.parse("background: none url(tile.png)").backgroundImage)
        val green = LabelStyle.parse("background: #00ff00 url(tile.png) NONE")
        assertEquals(LabelFill.Solid(0xFF00FF00.toInt()), green.background)
        assertNull(green.backgroundImage)
        assertEquals("tile.png", LabelStyle.parse("background: #00ff00 none url(tile.png)").backgroundImage)
        assertNull(LabelStyle.parse("background: url(tile.png) none", LabelStyle.TEXT_BOX_SELECTORS).backgroundImage)
        assertNull(LabelStyle.parse("background-color: red; background-color: none").background)
        assertNull(LabelStyle.parse("border-image: url(frame.png); border-image: none").borderImage)
    }

    @Test
    fun aLongSheetDoesNotOverflowTheStack() {
        // Each 100 kB, read in a thread with a stack smaller than the app's, and the rule after it still applies
        val n = 100 * 1024
        val after = " QLabel { background: red }"
        val sheets = listOf(
            "Q".repeat(n) + " { color: blue }$after",
            "QWidget ".repeat(n / 8) + "{ color: blue }$after",
            "@charset \"" + "x".repeat(n) + "\";$after",
            "@import \"" + "x".repeat(n) + "\";$after",
            "@import url(\"" + "x".repeat(n) + "\");$after",
            "@import \"x\" " + "a,".repeat(n / 2) + "a;$after",
            "@media " + "a,".repeat(n / 2) + "a { QLabel { color: blue } }$after",
        )
        for (sheet in sheets) {
            var style: LabelStyle? = null
            var thrown: Throwable? = null
            val thread = Thread(null, { runCatching { LabelStyle.parse(sheet) }.onSuccess { style = it }.onFailure { thrown = it } }, "parse", 512L * 1024)
            thread.start()
            thread.join()
            assertNull(sheet.take(16), thrown)
            assertEquals(sheet.take(16), LabelFill.Solid(0xFFFF0000.toInt()), style?.background)
        }
    }

    @Test
    fun manyDeclarationsQtSkipsTakeAsLongAsTheyAreLong() {
        // Each skipped one looking for the end of its block afresh would take 160 kB of them seconds, not milliseconds
        val sheet = "QLabel { color: red; " + "a b; ".repeat(32 * 1024) + "}"
        val start = System.nanoTime()
        val style = LabelStyle.parse(sheet)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$ms ms", ms < 2000)
        assertEquals(0xFFFF0000.toInt(), style.color)
    }

    @Test
    fun manyRulesEachOpeningWithADeclarationQtSkipsTakeAsLongAsTheyAreLong() {
        // Each skipped one looking for a `;` past its block would take 256 kB of them seconds, not milliseconds
        val sheet = "Q{a}".repeat(64 * 1024) + "QLabel { color: red }"
        val start = System.nanoTime()
        val style = LabelStyle.parse(sheet)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$ms ms", ms < 2000)
        assertEquals(0xFFFF0000.toInt(), style.color)
    }

    @Test
    fun manyKeywordsWithNoBlockTakeAsLongAsTheyAreLong() {
        // Each looking for a block's brace to the sheet's end would take 2 MB of them seconds, not milliseconds
        val sheet = "@a}".repeat(768 * 1024) + "QLabel { color: red }"
        val start = System.nanoTime()
        val style = LabelStyle.parse(sheet)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$ms ms", ms < 2000)
        assertEquals(0xFFFF0000.toInt(), style.color)
    }

    @Test
    fun aLongNumberTakesAsLongAsItIsLong() {
        // A number split every way between the parts that could take its digits would take 32 kB of them seconds
        val digits = "1".repeat(32 * 1024)
        val start = System.nanoTime()
        val padded = LabelStyle.parse("padding: 3px; padding: ${digits}_")
        val bordered = LabelStyle.parse("border: 10px solid red; border: ${digits}_ solid red")
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$ms ms", ms < 2000)
        assertEquals(Edges(3f, 3f, 3f, 3f), padded.padding)
        assertEquals(0f, bordered.drawnBorderWidth)
        assertEquals(10f, LabelStyle.parse("border: ${"0".repeat(32 * 1024)}10px solid red").drawnBorderWidth)
    }

    @Test
    fun whatItCannotReadLeavesTheRestAlone() {
        val style = LabelStyle.parse("color: nonsense; font-size: large; padding: 1px 2px 3px 4px 5px; ; ; background-color: #00ff00 !important")
        // A colour Qt does not know is its invalid one, which it draws black
        assertEquals(CssColor.INVALID, style.color)
        assertNull(style.fontSizePx)
        assertEquals(Edges.ZERO, style.padding)
        assertEquals(LabelFill.Solid(0xFF00FF00.toInt()), style.background)
        assertEquals(LabelStyle(), LabelStyle.parse(""))
    }
}
