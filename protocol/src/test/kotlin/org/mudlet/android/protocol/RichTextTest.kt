package org.mudlet.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rich text subset the app draws of a label (docs/phase-f.md, "Labels"). */
class RichTextTest {
    private val base = RichText.Style(sizePx = 12f)

    private fun parse(html: String) = RichText.parse(html, 12f)

    @Test
    fun whatGeyserLabelEchoWrites() {
        // Geyser.Label:echo("HP <b>full</b>") with the label's format centred and its colour gold at 9 pt
        val text = parse("""<div align="center" style="color: #ffd700; font-size: 9pt; "><font face ="Ubuntu Mono"><i>HP <b>full</b></i></font></div>""")
        val style = base.copy(color = 0xFFFFD700.toInt(), sizePx = 12f, family = "Ubuntu Mono", italic = true)
        assertEquals(
            listOf(RichText.Block(HAlign.CENTER, listOf(RichText.Span("HP ", style), RichText.Span("full", style.copy(bold = true))))),
            text.blocks,
        )
        assertEquals("HP full", text.plain)
    }

    @Test
    fun spacesCollapseAsInHtmlAndLineBreaksAndParagraphsSplitLines() {
        val text = parse("  one \n  two<br>  three<p>four</p><center>five</center>six&nbsp;&nbsp;seven")
        assertEquals("one two\nthree\nfour\nfive\nsix  seven", text.plain)
        assertEquals(listOf(null, null, HAlign.CENTER, null), text.blocks.map { it.align })
    }

    @Test
    fun spansAndFonts() {
        val text = parse(
            "<u>u</u><s>s</s><span style=\"background-color: red; font-weight: 700; font-size: 6px\">big</span>" +
                "<font color=\"#00ff00\" size=\"+2\">green</font><font size=1>small</font>" +
                "<span style='text-decoration: line-through; font-style: italic'>x</span>",
        )
        assertEquals(
            listOf(
                RichText.Span("u", base.copy(underline = true)),
                RichText.Span("s", base.copy(strike = true)),
                RichText.Span("big", base.copy(background = 0xFFFF0000.toInt(), bold = true, sizePx = 6f)),
                RichText.Span("green", base.copy(color = 0xFF00FF00.toInt(), sizePx = 18f)),
                RichText.Span("small", base.copy(sizePx = 12f * 0.7f)),
                RichText.Span("x", base.copy(strike = true, italic = true)),
            ),
            text.blocks.single().spans,
        )
    }

    @Test
    fun aStylesFontSizeNeedsItsUnit() {
        // Qt reads a `style`'s `font-size` as a sheet's: with no unit it is no size
        val text = parse("<span style=\"font-size: 30\">a</span><span style=\"font-size: 30px\">b</span><span style=\"font-size: 30pt\">c</span>")
        assertEquals(
            listOf(RichText.Span("a", base), RichText.Span("b", base.copy(sizePx = 30f)), RichText.Span("c", base.copy(sizePx = 40f))),
            text.blocks.single().spans,
        )
        // Nor is it one with a space before the unit, or of 0 or less, which would hide the text; px are whole
        for (none in listOf("30 px", "30 pt", "0px", "0pt", "-5px", "0.4px")) {
            assertEquals(none, listOf(RichText.Span("a", base)), parse("<span style=\"font-size: $none\">a</span>").blocks.single().spans)
        }
        assertEquals(listOf(RichText.Span("a", base.copy(sizePx = 13f))), parse("<span style=\"font-size: 12.5px\">a</span>").blocks.single().spans)
        // A `font`'s `size` Qt reads as a whole number, else 0, the smallest size, 0.7 of the label's, from 3 only with a
        // + or - first, and over a size set in it: as Qt 6.4.2 sizes them in a label
        fun font(size: String) = parse("<font size=\"$size\">a<span style=\"font-size: 30px\">b</span></font>").blocks.single().spans.single().style.sizePx
        assertEquals(listOf(0.7f, 0.7f, 0.7f, 1.5f, 1f, 2.4f).map { 12f * it }, listOf("0px", "12px", " +1", " 5", "-0px", "7").map(::font))
    }

    @Test
    fun coloursAsQtReadsThemInText() {
        // Each as Qt 6.4.2 draws it inside a span whose text is blue and whose background is blue
        val blue = 0xFF0000FF.toInt()
        fun inner(css: String) = parse("<span style=\"color: blue; background: blue\"><span style=\"$css\">a</span></span>").blocks.single().spans.single().style
        // Text it reads no colour for is black, and a `#` that is not a colour leaves the declaration out
        for (black in listOf("nosuch", "rgba(255,0,0)", "rgb(300,0,0)", "red blue", "none", "3px", "RGB(255,0,0)")) {
            assertEquals(black, CssColor.INVALID, inner("color: $black").color)
        }
        assertEquals(0xFF01FF01.toInt(), inner("color: hsl(120,255,128)").color)
        assertEquals(blue, inner("color: #12345").color)
        // A background it reads no colour off is as it was, and one it cannot name is black
        for (kept in listOf("rgba(255,0,0)", "red blue", "none", "3px", "#12345")) {
            assertEquals(kept, blue, inner("background-color: $kept").background)
        }
        assertEquals(CssColor.INVALID, inner("background: nosuch").background)
        // Not read: Qt takes these from the palette, which the app does not have (docs/divergences.md)
        assertEquals(CssColor.INVALID, inner("color: palette(highlight)").color)
        assertEquals(blue, inner("background: palette(highlight)").background)
        // `<font color>` takes only a name or a `#` and digits
        for (black in listOf("nosuch", "hsl(120,255,128)", "rgb(255,0,0)", "#12345")) {
            assertEquals(black, CssColor.INVALID, parse("<font color=\"$black\">a</font>").blocks.single().spans.single().style.color)
        }
        // An attribute given an empty value Qt passes over, also after one of the same name; one given no value at all,
        // or a space, is no colour, so black
        fun font(attributes: String) = parse("<span style=\"color: blue\"><font $attributes>a</font></span>").blocks.single().spans.single().style.color
        for (kept in listOf("color=\"\"", "color=''", "color=", "color= ", "face=\"Mono\" color=\"\"")) assertEquals(kept, blue, font(kept))
        assertEquals(0xFFFF0000.toInt(), font("color=\"red\" color=\"\""))
        for (black in listOf("color", "color=\" \"", "color=\"red\" color")) assertEquals(black, CssColor.INVALID, font(black))
    }

    @Test
    fun linksKeepTheirTargets() {
        val text = parse("""Go <a href="send:north">north</a> or <a href='prompt:say '>speak</a>.<a name="anchor">here</a>""")
        assertEquals(listOf("north" to "send:north", "speak" to "prompt:say "), text.links)
        assertEquals("Go north or speak.here", text.plain)
    }

    @Test
    fun linksKeepTheirTargetsAsQtReadsThem() {
        // test_mudlet_engine.py's QUOTED_LINKS and QUOTED_LINK_TARGETS: the engine follows only the href Qt reads
        val quoted = parse(
            """<a href="send:echo 'hi'">hi</a> <a href="send:echo hp > 50">hp</a> """ +
                """<a href="labelSaid = 'caf&eacute; &#233;'">cafe</a> <a href='labelSingle = "&amp;"'>single</a>""",
        )
        assertEquals(listOf("send:echo 'hi'", "send:echo hp > 50", "labelSaid = 'caf\u00E9 \u00E9'", "labelSingle = \"&amp;\""), quoted.links.map { it.second })
        assertEquals("hi hp cafe single", quoted.plain)
        // ScriptLabelsTest's tapped link
        assertEquals(listOf("order" to "send:say 'caf\u00E9 > tea'"), parse("""<a href="send:say 'caf&eacute; > tea'">order</a>""").links)
        // Each as QTextDocument reads it
        val qt = listOf(
            """<a href='prompt:tell bob "x"'>x</a>""" to "prompt:tell bob \"x\"",
            "<a href=send:caf&eacute;>x</a>" to "send:caf\u00E9",
            "<a href=send:x=1>x</a>" to "send:x",
            "<a href=\"&AMP;&LT;&GT;&QUOT;&apos;&Eacute;&thetasym;&euro;&rarr;&nbsp;&and;\">x</a>" to "&<>\"'\u00C9\u03D1\u20AC\u2192\u00A0\u22A5",
            "<a href=\"&#128;&#150;&#x9f;&#65;&#x1F600;&#x+0x41;&#+65;&#X41;\">x</a>" to "\u20AC\u2013\u0178A\uD83D\uDE00AAA",
            "<a href=\"&foo; &amp &Amp; &thetasymx; &#; &#x; &#-1; &#0x41; &#00000065; & ;\">x</a>" to
                "&foo; &amp &Amp; &thetasymx; &#; &#x; &#-1; &#0x41; &#00000065; & ;",
            "<a href=\"a&amp;b&ampc &eacute&eacute;\">x</a>" to "a&b&ampc &eacute\u00E9",
            "<a href=\"  spaced  \">x</a>" to "  spaced  ",
            "<a href=\"x\" href=\"y\">x</a>" to "y",
        )
        for ((html, href) in qt) assertEquals(html, listOf(href), parse(html).links.map { it.second })
        // A quoted value with no closing quote takes the rest of the text with it
        val unclosed = parse("""<a href="send:echo hp > 50">hp</a> <a href="unclosed>x</a> after""")
        assertEquals(listOf("hp" to "send:echo hp > 50"), unclosed.links)
        assertEquals("hp", unclosed.plain)
        // test_mudlet_engine.py's RAW_LT_LINKS: Qt reads a raw < in a quoted href, and a space after the tag's <
        val rawLt = parse("""<a href="labelLt = 1 < 2">lt</a> < a href="labelSpaced = true">spaced</a>""")
        assertEquals(listOf("lt" to "labelLt = 1 < 2", "spaced" to "labelSpaced = true"), rawLt.links)
        assertEquals("lt spaced", rawLt.plain)
        // An empty href is no link, to Qt as to the engine
        val empty = parse("""<a href="">x</a> <a href=''>y</a>""")
        assertEquals(emptyList<Pair<String, String>>(), empty.links)
        assertEquals("x y", empty.plain)
    }

    @Test
    fun entitiesAsQtReadsThem() {
        assertEquals("caf\u00E9 & \u2013 fish &", parse("caf&eacute; &AMP; &#150; fish &amp").plain)
        // Without its ; only at the end of the text
        assertEquals("&amp chips &ampx", parse("&amp chips &amp<b>x</b>").plain)
        assertEquals("a bold c", parse("""a <b title="x > y">bold</b> c""").plain)
    }

    @Test
    fun entitiesAndWhatIsNotATag() {
        val text = parse("a &lt;b&gt; &amp; &#65;&#x42; &unknown; 3 < 4 > 2 <3")
        assertEquals("a <b> & AB &unknown; 3 < 4 > 2 <3", text.plain)
    }

    @Test
    fun aWholeDocumentShowsOnlyItsBody() {
        val text = parse(
            "<!DOCTYPE HTML><html><head><title>t</title><style>p { color: red }</style></head>" +
                "<body style=\"font-size: 15px\"><!-- note --><p align=right>body</p></body></html>",
        )
        assertEquals(listOf(RichText.Block(HAlign.RIGHT, listOf(RichText.Span("body", base.copy(sizePx = 15f))))), text.blocks)
    }

    @Test
    fun otherTagsKeepTheirTextAndUnclosedOnesEndWithTheText() {
        val text = parse("<table><tr><td>cell</td><td>two</td></tr></table><blink>on</blink><b>bold")
        assertEquals("cell    two\nonbold", text.plain)
        assertTrue(text.blocks.last().spans.last().style.bold)
        assertEquals(RichText(emptyList()), parse("   <p> </p> "))
    }

    @Test
    fun headingsAsQtSizesThem() {
        // As Qt 6.4.2's QTextDocument sizes them against a 10 pt default font: 20, 15, 12, 10 and 8 pt, bold, and h6 as text
        val text = parse("body<h1>a</h1><h2>b</h2><h3>c</h3><h4>d</h4><h5>e</h5><h6>f</h6>")
        assertEquals(
            listOf(base, base.copy(bold = true, sizePx = 24f), base.copy(bold = true, sizePx = 18f), base.copy(bold = true, sizePx = 12f * 1.2f), base.copy(bold = true), base.copy(bold = true, sizePx = 12f * 0.8f), base),
            text.blocks.map { it.spans.single().style },
        )
        assertEquals("body\na\nb\nc\nd\ne\nf", text.plain)
        // Against the label's own size, over a size around it or in the heading's style, h4 too (Qt 6.4.2, 12 px font)
        val sized = parse("<div style=\"font-size: 20px\"><h1>x</h1><h4>y</h4></div><h2 style=\"font-size: 30px\">z</h2>")
        assertEquals(listOf(24f, 12f, 18f), sized.blocks.map { it.spans.single().style.sizePx })
        assertTrue(sized.blocks.all { it.spans.single().style.bold })
        // And over a size a style sets in it, but a `font size`'s, which Qt also takes against the label's own size
        val inside = parse(
            "<h1>a<span style=\"font-size: 30px\">b</span><font size=+2>c<span style=\"font-size: 30px\">d</span></font></h1>" +
                "<h1><b>g<span style=\"font-size: 30px\">h</span></b></h1>" +
                "<p style=\"font-size: 20px\"><font size=+1>e<span style=\"font-size: 30px\">f</span></font></p>",
        )
        assertEquals(listOf("ab" to 24f, "cd" to 18f, "gh" to 24f, "ef" to 12f * 1.2f), inside.blocks.flatMap { b -> b.spans.map { it.text to it.style.sizePx } })
    }

    @Test
    fun subscriptsAndSuperscriptsAreSmallerAndShifted() {
        val spans = parse("E = mc<sup>2</sup> H<sub>2</sub>O <sup><sub>q</sub></sup>").blocks.single().spans
        assertEquals(listOf("E = mc", "2", " H", "2", "O ", "q"), spans.map { it.text })
        assertEquals(listOf(0f, 0.5f, 0f, -1f / 6, 0f, -1f / 6), spans.map { it.style.raise })
        // At 2/3 of the size around them, once, as Qt draws them
        assertEquals(listOf(12f, 8f, 12f, 8f, 12f, 8f), spans.map { it.style.drawnSizePx })
        assertEquals(12f, spans[1].style.sizePx)
    }

    @Test
    fun imagesAreSpansOfTheirOwn() {
        val text = parse(
            "a<img src=\"/data/key.png\" width=20 height=\"10\">b<img src='qrc:/icons/x.png'><img src='qrc:/icons/x.png'>" +
                "<img src=\"file:///sdcard/i.png\" width=\" 30\"><img src=\"file:/sdcard/i.png\" width=-1><img alt=\"none\"><a href=\"send:look\"><img src=\"eye.png\" height=8></a>",
        )
        val images = text.images
        assertEquals(
            listOf(
                RichText.Image("/data/key.png", 20f, 10f),
                RichText.Image(":/icons/x.png", null, null),
                RichText.Image(":/icons/x.png", null, null),
                RichText.Image("/sdcard/i.png", 30f, null),
                RichText.Image("/sdcard/i.png", null, null),
                RichText.Image("eye.png", null, 8f),
            ),
            images,
        )
        assertEquals(listOf("\uFFFC"), text.blocks.single().spans.filter { it.style.image != null }.map { it.text }.distinct())
        // Read as nothing, as Mudlet reads a label, but a link of one is still a link
        assertEquals("ab", text.plain)
        assertEquals("x\ny", parse("<p><img src=\"a.png\"></p>x<p><img src=\"b.png\"></p>y").plain)
        assertEquals(emptyList<RichText.Image>(), parse("<img alt=\"no src\">x").images)
        assertEquals(listOf("" to "send:look"), text.links)
        assertEquals("send:look", text.blocks.single().spans.last().style.href)
        // Sized as Qt sizes them, of an image 8 by 4 pixels
        assertEquals(20f to 10f, images[0].size(8, 4))
        assertEquals(20f to 10f, images[0].size(null, null))
        assertEquals(8f to 4f, images[1].size(8, 4))
        assertEquals(null, images[1].size(null, null))
        assertEquals(30f to 15f, images[3].size(8, 4))
        assertEquals(null, images[3].size(null, null))
        assertEquals(16f to 8f, images[5].size(8, 4))
        assertEquals(" b", parse("<img src=\"a.png\"> b").plain)
        // Qt reads a number alone; along a side it reads none or a negative one off it draws the file's own size, as
        // QPainter::drawPixmap() draws a negative width, and a 0 alone hides it: as a QLabel of Qt 6.4.2 draws a 40 by
        // 20 file
        val odd = parse(
            "<img src=a.png width=\"8px\"><img src=a.png width=-5 height=10><img src=a.png width=10 height=\"50%\">" +
                "<img src=a.png width=0><img src=a.png width=0 height=10><img src=a.png width=1e1 height=+2><img src=a.png width=80f>",
        ).images
        assertEquals(listOf(-2f, -5f, 10f, 0f, 0f, 10f, -2f), odd.map { it.widthPx })
        assertEquals(listOf(null, 10f, -2f, null, 10f, 2f, null), odd.map { it.heightPx })
        assertEquals(listOf(40f to 20f, 40f to 10f, 10f to 20f, 0f to 0f, 0f to 10f, 10f to 2f, 40f to 20f), odd.map { it.size(40, 20) })
        // Before the file comes, room only for both sides given and drawn
        assertEquals(listOf(null, null, null, null, 0f to 10f, 10f to 2f, null), odd.map { it.size(null, null) })
        // Qt rounds a side, a side it works out from the other that rounds to 0 hides the image but keeps its room along
        // the side given, and -1 is Qt's own for a side not given: as a QLabel of Qt 6.4.2 draws a 200 by 20 file
        val wide = parse(
            "<img src=a.png width=abc><img src=a.png width=4><img src=a.png width=5><img src=a.png width=-5><img src=a.png width=-4>" +
                "<img src=a.png width=-1 height=10><img src=a.png width=-1.0><img src=a.png width=1 height=-1><img src=a.png width=10.6 height=5>" +
                "<img src=a.png width=-0.4><img src=a.png width=25 height=abc>",
        ).images
        assertEquals(
            listOf(0f to 0f, 4f to 0f, 5f to 1f, 200f to 20f, 0f to 0f, 100f to 10f, 200f to 20f, 1f to 0f, 11f to 5f, 0f to 0f, 25f to 20f),
            wide.map { it.size(200, 20) },
        )
        assertEquals(1f to 1f, wide[7].size(40, 20))
        assertEquals(50f to 0f, parse("<img src=a.png width=50>").images.single().size(200, 1))
        assertEquals(11f to 6f, parse("<img src=a.png width=10.6>").images.single().size(40, 20))
        // A side at most a sheet's longest length, whatever the script or the file gives, so the app can lay it out
        assertEquals(4096f to 4096f, parse("<img src=a.png width=1e9 height=1e99>").images.single().size(null, null))
        assertEquals(4000f to 4096f, parse("<img src=a.png width=4000>").images.single().size(1, 4096))
        assertEquals(4096f to 4096f, parse("<img src=a.png>").images.single().size(100000, 5000))
        // Its `alt`, else its `title`, for a screen reader
        assertEquals(listOf("Look", "eye", ""), parse("<img src=a.png alt=Look title=x><img src=b.png title=eye><img src=c.png>").images.map { it.alt })
    }

    @Test
    fun aDataUrlsImageIsReadFromItsBase64() {
        // As Qt's qDecodeDataUrl() reads one: its percent-escapes first, its base64 past what is not base64, and `data:`
        // and `;base64` in any case
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val images = parse(
            "<img src='data:image/png;base64,iVBORw0K\n Ggo='><img src=\"DATA:;BASE64 ,iVBORw0KGgo=\">" +
                "<img src='data:image/png%3Bbase64,iVBORw0KGgo%3d'><img src='data:;base64,%2B%2F8%3D'>" +
                "<img src='data:image/png,iVBORw0KGgo='><img src='data:image/png;base64'><img src=a.png><img src='data:;base64,a'>",
        ).images
        assertArrayEquals(png, images[0].data())
        assertArrayEquals(png, images[1].data())
        assertArrayEquals(png, images[2].data())
        assertArrayEquals(byteArrayOf(0xFB.toByte(), 0xFF.toByte()), images[3].data())
        // Nor bad base64, which does not throw
        assertEquals(listOf(null, null, null, null), images.drop(4).map { it.data() })
    }

    @Test
    fun aTablesRowsAreLinesWithTheirCellsApart() {
        val text = parse("<table><tr><th>Name</th><td> HP </td></tr><tr><td></td><td>b<td>c</tr></table>after")
        assertEquals("Name    HP\n    b    c\nafter", text.plain)
        assertEquals(listOf(true, false), text.blocks.first().spans.map { it.style.bold })
        // A cell or row left open ends at the next cell, row or the table's end, so a `th`'s bold goes no further, as
        // in Qt
        val unclosed = parse("<table><tr><th>a<td>b<tr><td><b>c<td>d</table>e")
        assertEquals("a    b\nc    d\ne", unclosed.plain)
        assertEquals(listOf("a" to true, "    b" to false, "c" to true, "    d" to false, "e" to false), unclosed.blocks.flatMap { b -> b.spans.map { it.text to it.style.bold } })
        // A row ends a cell left open in the row before, so its formatting stays in that row
        assertEquals(listOf("a" to true, "b" to false), parse("<table><tr><th>a<tr><td>b</table>").blocks.flatMap { b -> b.spans.map { it.text to it.style.bold } })
        // A paragraph in a cell, as Qt's toHtml() writes every cell's, keeps its row a line, a space from its cell's text
        val paragraphs = parse("<table><tr><td><p>a</p></td><td><p>b</p><div align=center> c</div></td></tr><tr><td>d</table>")
        assertEquals("a    b c\nd", paragraphs.plain)
        // A table in a cell keeps the cell's `th` bold in it and after it, as Qt's QTextHtmlParser
        val nested = parse("<table><tr><th>a<table><tr><td>b</td></tr></table>c</th></tr></table>")
        assertEquals(listOf("a" to true, "b" to true, "c" to true), nested.blocks.flatMap { b -> b.spans.map { it.text to it.style.bold } })
        // The spaces between cells are dropped, and a table without rows starts its own
        assertEquals("a    b\nc", parse("<table>\n <tr>\n  <td>a</td>\n  <td>b</td>\n </tr>\n</table><table><td>c</td></table>").plain)
    }

    @Test
    fun preformattedTextKeepsItsSpacesInMonospace() {
        val text = parse("<pre>\n  a   b\r\n\tc &amp; <b>d</b>  \n</pre>after  it")
        assertEquals("  a   b\n\tc & d  \nafter it", text.plain)
        val mono = base.copy(family = "monospace")
        assertEquals(
            listOf(RichText.Span("  a   b\n\tc & ", mono), RichText.Span("d", mono.copy(bold = true)), RichText.Span("  ", mono)),
            text.blocks.first().spans,
        )
        assertEquals(listOf(RichText.Span("after it", base)), text.blocks.last().spans)
    }
}
