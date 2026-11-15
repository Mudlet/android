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
