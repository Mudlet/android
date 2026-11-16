package org.mudlet.android.protocol

/**
 * A label's text as the app draws it (docs/phase-f.md, "Labels"): Qt's rich text, of which [parse] reads the tags
 * Geyser and scripts write, `b`, `i`, `u`, `s`, `font`, `span` and any tag's `style`, `br`, `p`, `div`, `center` and
 * `a`. Any other tag keeps its text without its formatting; one that holds no text to show (`head`, `style`,
 * `script`, `title`) is dropped with it.
 */
data class RichText(val blocks: List<Block>) {
    /** A paragraph: [align] across the label, null for the label's own. */
    data class Block(val align: HAlign?, val spans: List<Span>)

    data class Span(val text: String, val style: Style)

    /** A span's formatting; colours are 0xAARRGGBB, null for what is inherited from the label. */
    data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val color: Int? = null,
        val background: Int? = null,
        /** In Mudlet's pixels. */
        val sizePx: Float,
        val family: String? = null,
        /** The link's target, for a span in an `a` with an `href`. */
        val href: String? = null,
    )

    /** The text without its formatting, a line for each block, as a screen reader is given it. */
    val plain: String get() = blocks.joinToString("\n") { block -> block.spans.joinToString("") { it.text } }

    /** The links, in order, each its text and its target. */
    val links: List<Pair<String, String>>
        get() {
            val out = ArrayList<Pair<String, String>>()
            for (block in blocks) {
                var href: String? = null
                val text = StringBuilder()
                for (span in block.spans + Span("", Style(sizePx = 0f))) {
                    if (span.style.href != href) {
                        if (href != null) out.add(text.toString().trim() to href)
                        text.clear()
                        href = span.style.href
                    }
                    text.append(span.text)
                }
            }
            return out
        }

    companion object {
        /** [html] with text [baseSizePx] Mudlet pixels high where it sets no size. */
        fun parse(html: String, baseSizePx: Float): RichText = Parser(html, baseSizePx).parse()

        // HTML's font sizes 1 to 7 against the base size, 3
        private val FONT_SIZE_SCALE = floatArrayOf(0.6f, 0.8f, 1f, 1.2f, 1.5f, 2f, 3f)
        private val BLOCK_TAGS = setOf(
            "p", "div", "center", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "tr", "table", "blockquote",
            "pre", "hr", "dl", "dt", "dd", "body", "html",
        )
        private val VOID_TAGS = setOf("br", "hr", "img", "meta", "link", "input", "col", "area", "base", "wbr")
        private val HIDDEN_TAGS = setOf("head", "style", "script", "title")

        // A value in double quotes, in single quotes or unquoted, as Qt's QTextHtmlParser::parseWord() reads it
        private val ATTRIBUTE = Regex("([^\\s=/]+)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s<>=]+))?")
        private val WHITESPACE = Regex("[ \\t\\r\\n\\u000C]+")

        // Qt reads &#128; to &#159; as Windows-1252's characters
        private const val WINDOWS_1252 =
            "\u20AC\u0081\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u008D\u017D\u008F" +
                "\u0090\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u009D\u017E\u0178"

        // The longest name between & and ; that Qt reads
        private const val MAX_ENTITY = 8

        // What tagEnd() gives for a tag with a quoted value that has no closing quote
        private const val UNCLOSED = -2

        /**
         * [text] with its character references, `&amp;`, `&#65;` and `&#x41;`, as the characters, as Qt's
         * `QTextHtmlParser::parseEntity()` reads them: the names in [HTML_ENTITIES], in their case, and numbers; one
         * without its `;` only at [atEnd], the end of the whole text; anything else as it is.
         */
        fun decodeEntities(text: String, atEnd: Boolean = false): String {
            if ('&' !in text) return text
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i++]
                if (c != '&') {
                    out.append(c)
                    continue
                }
                var end = i
                while (end < text.length && end - i < MAX_ENTITY && text[end] != ';' && !text[end].isWhitespace()) end++
                val closed = end < text.length && text[end] == ';'
                val decoded = if (closed || (atEnd && end == text.length)) entity(text.substring(i, end)) else null
                if (decoded == null) {
                    out.append(c)
                } else {
                    out.append(decoded)
                    i = if (closed) end + 1 else end
                }
            }
            return out.toString()
        }

        private fun entity(name: String): String? {
            HTML_ENTITIES[name]?.let { return it.toString() }
            if (name.length < 2 || name[0] != '#') return null
            val hex = name[1] == 'x' || name[1] == 'X'
            val code = number(name.substring(if (hex) 2 else 1), hex) ?: return null
            return when {
                code in 0x80..0x9F -> WINDOWS_1252[code - 0x80].toString()

                // As QChar::highSurrogate() and lowSurrogate() make them, even past U+10FFFF
                code >= 0x10000 -> charArrayOf(((code shr 10) + 0xD7C0).toChar(), ((code and 0x3FF) + 0xDC00).toChar()).concatToString()

                else -> code.toChar().toString()
            }
        }

        // QString::toUInt(): a + before the digits, and for hex a 0x
        private fun number(text: String, hex: Boolean): Int? {
            var digits = text.removePrefix("+")
            if (hex && digits.startsWith("0x", ignoreCase = true)) digits = digits.substring(2)
            if (digits.isEmpty() || digits.startsWith("+")) return null
            return digits.toIntOrNull(if (hex) 16 else 10)?.takeIf { it >= 0 }
        }
    }

    // One element open around the text: its style for the text in it, and its alignment if it is a block
    private class Open(val tag: String, val style: Style, val align: HAlign?)

    private class Parser(private val html: String, baseSizePx: Float) {
        private val blocks = ArrayList<Block>()
        private val spans = ArrayList<Span>()
        private val open = ArrayList<Open>().apply { add(Open("", Style(sizePx = baseSizePx), null)) }
        private var hidden = 0

        // At a block's start, and after a space or a line break, a space is dropped
        private var atSpace = true

        fun parse(): RichText {
            var i = 0
            while (i < html.length) {
                val lt = html.indexOf('<', i)
                if (lt < 0) {
                    text(html.substring(i), atEnd = true)
                    break
                }
                text(html.substring(i, lt))
                i = tag(lt)
            }
            flush()
            return RichText(blocks)
        }

        // The tag at [lt]; returns where the text after it starts
        private fun tag(lt: Int): Int {
            if (html.startsWith("<!--", lt)) return html.indexOf("-->", lt + 4).let { if (it < 0) html.length else it + 3 }
            val gt = tagEnd(lt)
            // A quoted value Qt reads to the end of the text, and the rest with it
            if (gt == UNCLOSED) return html.length
            if (gt < 0) {
                // Not a tag: its text as it is
                text(html.substring(lt))
                return html.length
            }
            val inner = html.substring(lt + 1, gt).trim()
            if (inner.startsWith("!") || inner.startsWith("?")) return gt + 1
            val closing = inner.startsWith("/")
            val body = inner.removePrefix("/").removeSuffix("/").trim()
            val name = body.takeWhile { !it.isWhitespace() }.lowercase()
            if (name.isEmpty() || !name[0].isLetter()) {
                text(html.substring(lt, gt + 1))
                return gt + 1
            }
            if (closing) close(name) else open(name, attributes(body.substring(name.length)), selfClosing = inner.endsWith("/"))
            return gt + 1
        }

        // The > that ends the tag at [lt], -1 for none, or UNCLOSED: a > in a quoted value is the value's, as Qt reads it
        private fun tagEnd(lt: Int): Int {
            var afterEquals = false
            var i = lt + 1
            while (i < html.length) {
                val c = html[i]
                when {
                    c == '>' -> return i

                    afterEquals && (c == '"' || c == '\'') -> {
                        i = html.indexOf(c, i + 1)
                        if (i < 0) return UNCLOSED
                        afterEquals = false
                    }

                    c == '=' -> afterEquals = true

                    !c.isWhitespace() -> afterEquals = false
                }
                i++
            }
            return -1
        }

        // A value in double quotes or unquoted has its entities decoded; one in single quotes Qt keeps as written
        private fun attributes(text: String): Map<String, String> = ATTRIBUTE.findAll(text).associate { match ->
            val value = match.groupValues[2]
            match.groupValues[1].lowercase() to when (value.firstOrNull()) {
                '"' -> decodeEntities(value.substring(1, value.length - 1))
                '\'' -> value.substring(1, value.length - 1)
                else -> decodeEntities(value)
            }
        }

        private fun open(name: String, attributes: Map<String, String>, selfClosing: Boolean) {
            if (name in HIDDEN_TAGS) {
                if (!selfClosing) hidden++
                return
            }
            if (name == "br") {
                if (hidden == 0) append("\n", current().style)
                atSpace = true
                return
            }
            val block = name in BLOCK_TAGS
            if (block) flush()
            if (name in VOID_TAGS || selfClosing) return
            val parent = current()
            var style = parent.style
            var align = parent.align
            when (name) {
                "b", "strong" -> style = style.copy(bold = true)

                "i", "em" -> style = style.copy(italic = true)

                "u" -> style = style.copy(underline = true)

                "s", "strike", "del" -> style = style.copy(strike = true)

                // An empty href is no link, as in Qt
                "a" -> attributes["href"]?.takeIf { it.isNotEmpty() }?.let { style = style.copy(href = it) }

                "center" -> align = HAlign.CENTER

                "font" -> {
                    attributes["color"]?.let { CssColor.parse(it) }?.let { style = style.copy(color = it) }
                    attributes["face"]?.let { style = style.copy(family = it.split(',').first().trim()) }
                    attributes["size"]?.let { fontSize(it, parent.style.sizePx) }?.let { style = style.copy(sizePx = it) }
                }
            }
            if (block) attributes["align"]?.let(::hAlign)?.let { align = it }
            attributes["style"]?.let { css ->
                for ((property, value) in LabelStyle.parseDeclarations(css)) {
                    style = styled(style, property, value)
                    if (block && property == "text-align") hAlign(value)?.let { align = it }
                }
            }
            open.add(Open(name, style, align))
        }

        private fun close(name: String) {
            if (name in HIDDEN_TAGS) {
                hidden = (hidden - 1).coerceAtLeast(0)
                return
            }
            // Closes the innermost element of that name, and any left open inside it
            val at = open.indexOfLast { it.tag == name }
            if (at <= 0) return
            if (name in BLOCK_TAGS || open.subList(at, open.size).any { it.tag in BLOCK_TAGS }) flush()
            while (open.size > at) open.removeAt(open.size - 1)
        }

        private fun current() = open.last()

        private fun text(raw: String, atEnd: Boolean = false) {
            if (raw.isEmpty() || hidden > 0) return
            var text = WHITESPACE.replace(raw, " ")
            if (atSpace) text = text.trimStart(' ')
            if (text.isEmpty()) return
            text = decodeEntities(text, atEnd)
            append(text, current().style)
            atSpace = text.endsWith(' ')
        }

        private fun append(text: String, style: Style) {
            val last = spans.lastOrNull()
            if (last != null && last.style == style) spans[spans.size - 1] = Span(last.text + text, style) else spans.add(Span(text, style))
        }

        // Ends the block being written, without its trailing spaces
        private fun flush() {
            while (spans.isNotEmpty()) {
                val last = spans.last()
                val trimmed = last.text.trimEnd(' ')
                if (trimmed.isNotEmpty()) {
                    spans[spans.size - 1] = Span(trimmed, last.style)
                    break
                }
                spans.removeAt(spans.size - 1)
            }
            if (spans.isNotEmpty()) blocks.add(Block(current().align, spans.toList()))
            spans.clear()
            atSpace = true
        }

        private fun hAlign(value: String): HAlign? = when (value.trim().lowercase()) {
            "left" -> HAlign.LEFT
            "right" -> HAlign.RIGHT
            "center", "middle" -> HAlign.CENTER
            "justify" -> HAlign.JUSTIFY
            else -> null
        }

        // HTML's size: 1 to 7, or relative as +1 or -2 from 3
        private fun fontSize(value: String, base: Float): Float? {
            val v = value.trim()
            val n = v.toIntOrNull() ?: return LabelStyle.length(v)
            val logical = if (v.startsWith("+") || v.startsWith("-")) 3 + n else n
            return base * FONT_SIZE_SCALE[logical.coerceIn(1, 7) - 1]
        }

        private fun styled(style: Style, property: String, value: String): Style = when (property) {
            "color" -> CssColor.parse(value)?.let { style.copy(color = it) } ?: style

            "background-color", "background" -> CssColor.parse(value)?.let { style.copy(background = it) } ?: style

            "font-size" -> LabelStyle.length(value)?.let { style.copy(sizePx = it) } ?: style

            "font-weight" -> LabelStyle.fontWeight(value)?.let { style.copy(bold = it >= 600) } ?: style

            "font-style" -> style.copy(italic = value.trim().lowercase() in setOf("italic", "oblique"))

            "font-family" -> style.copy(family = value.split(',').first().trim().trim('"', '\''))

            "text-decoration" -> value.lowercase().let {
                style.copy(underline = "underline" in it, strike = "line-through" in it)
            }

            else -> style
        }
    }
}
