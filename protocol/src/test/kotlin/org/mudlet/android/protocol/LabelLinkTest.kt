package org.mudlet.android.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who follows a tapped label link (docs/phase-f.md, "Upstream gaps", 1). */
class LabelLinkTest {
    @Test
    fun theEngineFollowsEachLinkButAWebOne() {
        // As the label's text gives it, its whitespace kept: the engine follows only that exact href
        for (href in listOf("send:north ", "prompt:say ", "echo('hi')", "mailto:a@b.c", " SEND:x", "tel:go()", "http", "HTTPS", ":http:x")) {
            assertEquals(LabelLink.Engine(href), LabelLink.of(href))
        }
        assertEquals(LabelLink.Web("https://mudlet.org"), LabelLink.of(" https://mudlet.org "))
        assertEquals(LabelLink.Web("HTTP://mudlet.org"), LabelLink.of("HTTP://mudlet.org"))
        assertEquals(LabelLink.Web("http:x"), LabelLink.of("http:x"))
        // Trimmed as Qt trims, as the engine reads the scheme: U+0085 is whitespace to Qt, U+001C is not
        assertEquals(LabelLink.Web("https://x"), LabelLink.of("\u0085https://x"))
        assertEquals(LabelLink.Engine("\u001chttps://x"), LabelLink.of("\u001chttps://x"))
    }

    @Test
    fun aClickThroughLabelsLinksAreNotFollowed() {
        fun label(clickThrough: Boolean?): LabelContent {
            val wire = JSONObject().put("t", "label").put("name", "nav").put("text", "<a href=\"send:north\">north</a>")
            return (FrameReader.parse(wire.putOpt("clickThrough", clickThrough)) as EngineMessage.Label).label
        }
        assertTrue(label(clickThrough = false).linksFollowable)
        assertTrue(label(clickThrough = null).linksFollowable)
        // As on the desktop, where the mouse passes through it to what is under it, and its links are out of reach
        assertFalse(label(clickThrough = true).linksFollowable)
    }

    @Test
    fun aPromptLinkIsReadAsTheEngineReadsIt() {
        // The scheme as TLabelModel::followLink() reads it: any case, after Qt's whitespace, with something before the colon
        for (href in listOf("prompt:say ", " PROMPT:say", "\u0085Prompt:", "prompt:send:x")) {
            assertTrue(href, (LabelLink.of(href) as LabelLink.Engine).prompts)
        }
        for (href in listOf("send:prompt:x", "prompt", ":prompt:x", "\u001cprompt:x", "prompts:x", "echo('prompt:')")) {
            assertFalse(href, (LabelLink.of(href) as LabelLink.Engine).prompts)
        }
    }
}
