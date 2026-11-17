package org.mudlet.android.protocol

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
                RichText.Span("small", base.copy(sizePx = 12f * 0.6f)),
                RichText.Span("x", base.copy(strike = true, italic = true)),
            ),
            text.blocks.single().spans,
        )
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
        assertEquals("celltwo\nonbold", text.plain)
        assertTrue(text.blocks.last().spans.last().style.bold)
        assertEquals(RichText(emptyList()), parse("   <p> </p> "))
    }
}
