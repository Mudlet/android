package org.mudlet.android.protocol

import java.util.Base64

/**
 * A label's text as the app draws it (docs/phase-f.md, "Labels"): Qt's rich text, of which [parse] reads the tags
 * Geyser and scripts write, `b`, `i`, `u`, `s`, `font`, `span` and any tag's `style`, `br`, `p`, `div`, `center` and
 * `a`, and also `h1` to `h5`, `sub`, `sup`, `img`, `pre` and a table's rows (`tr`), each a line with its cells (`td`,
 * `th`) apart. Any other tag keeps its text without its formatting; one that holds no text to show (`head`, `style`,
 * `script`, `title`) is dropped with it.
 */
data class RichText(val blocks: List<Block>) {
    /** A paragraph: [align] across the label, null for the label's own. */
    data class Block(val align: HAlign?, val spans: List<Span>)

    data class Span(val text: String, val style: Style) {
        /** Its text as a screen reader is given it: none for an image, as Mudlet's `TLabelModel` reads a label. */
        val readText: String get() = if (style.image == null) text else ""
    }

    /**
     * An `img`: the engine's file at [path], or the image a `data:` URL holds, its `width` and `height` in Mudlet's
     * pixels where they are given, a number alone as Qt reads them, rounded to a whole pixel as `QTextImageHandler`
     * rounds it, and -2 for one that is no number, as Qt keeps it (but -1, Qt's own for a side not given, is null), and
     * its `alt` or else its `title`, for a screen reader. Its span's text is Qt's object replacement character.
     */
    data class Image(val path: String, val widthPx: Float?, val heightPx: Float?, val alt: String = "") {
        /**
         * Its size in Mudlet's pixels, as Qt's `QTextImageHandler` sizes it, of an image [w] by [h] pixels, or null
         * while it has not loaded: both attributes as given, one with the other kept in proportion and rounded,
         * neither the image's own size; and along a side whose size is negative the image's own, as
         * `QPainter::drawPixmap()` draws a negative width or height, which draws nothing for a side of 0, so a size
         * of 0, given or rounded to, hides it but keeps its room along the other side, as Qt's layout does. Null to
         * draw nothing: an image that does not load takes room only where
         * both are given and neither is negative. Each side [LabelStyle.MAX_LENGTH] at most, so a script's size is
         * never one the app cannot lay out.
         */
        fun size(w: Int?, h: Int?): Pair<Float, Float>? {
            if (widthPx != null && heightPx != null && widthPx >= 0f && heightPx >= 0f) return bounded(widthPx, heightPx)
            if (w == null || h == null || w <= 0 || h <= 0) return null
            val across = widthPx ?: heightPx?.let { qRound(it * (w.toDouble() / h)) } ?: w.toFloat()
            val down = heightPx ?: widthPx?.let { qRound(it * (h.toDouble() / w)) } ?: h.toFloat()
            if (across == 0f || down == 0f) return bounded(across.coerceAtLeast(0f), down.coerceAtLeast(0f))
            return bounded(if (across < 0f) w.toFloat() else across, if (down < 0f) h.toFloat() else down)
        }

        /**
         * The bytes of the image a `data:` URL in base64 at [path] holds, as Qt's `qDecodeDataUrl()` reads them, its
         * percent-escapes decoded first and then past what is not base64; null for any other path, as for one that is
         * not base64, which the app does not read.
         */
        fun data(): ByteArray? {
            if (!path.startsWith("data:", ignoreCase = true)) return null
            val url = PERCENT_ESCAPE.replace(path.substring(5)) { it.groupValues[1].toInt(16).toChar().toString() }
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).trim().endsWith(";base64", ignoreCase = true)) return null
            return try {
                Base64.getMimeDecoder().decode(url.substring(comma + 1))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun bounded(across: Float, down: Float) = across.coerceAtMost(LabelStyle.MAX_LENGTH) to down.coerceAtMost(LabelStyle.MAX_LENGTH)
    }

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
        /**
         * How far its baseline is raised, in heights of its font as drawn: in a `sup` a half up and in a `sub` a sixth
         * down, Qt's `QTextCharFormat::superScriptBaseline()` and `subScriptBaseline()` (50 and 16.67 percent).
         */
        val raise: Float = 0f,
        /** The image this span is, for an `img`; never an open element's, and part of [equals] so no text joins it. */
        val image: Image? = null,
    ) {
        /** The size it is drawn at, in Mudlet's pixels: Qt's `QTextEngine` draws sub- and superscripts at 2/3 of theirs. */
        val drawnSizePx: Float get() = if (raise == 0f) sizePx else sizePx * 2 / 3
    }

    /** The text without its formatting, a line for each block but one of only images, as a screen reader is given it. */
    val plain: String
        get() = blocks.map { block -> block.spans.joinToString("") { it.readText } }.filter { it.isNotEmpty() }.joinToString("\n")

    /** Its images, in order; their text as drawn, Qt's object replacement character, [plain] leaves out. */
    val images: List<Image> get() = blocks.flatMap { block -> block.spans.mapNotNull { it.style.image } }

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
                    text.append(span.readText)
                }
            }
            return out
        }

    companion object {
        /** [html] with text [baseSizePx] Mudlet pixels high where it sets no size. */
        fun parse(html: String, baseSizePx: Float): RichText = Parser(html, baseSizePx).parse()

        // HTML's font sizes 1 to 7 against the base size, 3, by Qt's factors (QTextFormatPrivate::resolveFont()); Qt's
        // headings h1 to h5 are 6, 5, 4, 3 and 2 of them
        private val FONT_SIZE_SCALE = floatArrayOf(0.7f, 0.8f, 1f, 1.2f, 1.5f, 2f, 2.4f)
        private val BLOCK_TAGS = setOf(
            "p", "div", "center", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "tr", "table", "blockquote",
            "pre", "hr", "dl", "dt", "dd", "body", "html",
        )
        private val VOID_TAGS = setOf("br", "hr", "img", "meta", "link", "input", "col", "area", "base", "wbr")
        private val HIDDEN_TAGS = setOf("head", "style", "script", "title")

        // Between a row's cells, which Qt draws in a grid, so they read apart
        private const val CELL_GAP = "    "

        // What Qt's text holds for an image
        private const val OBJECT = "\uFFFC"

        // A value in double quotes, in single quotes or unquoted, as Qt's QTextHtmlParser::parseWord() reads it
        private val ATTRIBUTE = Regex("([^\\s=/]+)(?:\\s*(=)\\s*(\"[^\"]*\"|'[^']*'|[^\\s<>=]+)?)?")
        private val WHITESPACE = Regex("[ \\t\\r\\n\\u000C]+")

        // As QByteArray::fromPercentEncoding() decodes one: not URLDecoder's, which reads a +, base64's, as a space
        private val PERCENT_ESCAPE = Regex("%([0-9A-Fa-f]{2})")

        // Qt reads &#128; to &#159; as Windows-1252's characters
        private const val WINDOWS_1252 =
            "\u20AC\u0081\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u008D\u017D\u008F" +
                "\u0090\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u009D\u017E\u0178"

        // Qt's qRound(): half away from 0, and a size past an Int's one past what a label can draw
        private fun qRound(v: Double) = (if (v >= 0) v + 0.5 else v - 0.5).toInt().toFloat()

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

    // One element open around the text: its style for the text in it, its alignment if it is a block, whether it or
    // one around it is a heading or a `font size`, whose size Qt gives all in it, whether it is in a table's cell, and
    // whether it is a block that ends the line, as a block in a cell does not
    private class Open(val tag: String, val style: Style, val align: HAlign?, val sized: Boolean, val inCell: Boolean, val breaks: Boolean)

    private class Parser(private val html: String, private val baseSizePx: Float) {
        private val blocks = ArrayList<Block>()
        private val spans = ArrayList<Span>()
        private val open = ArrayList<Open>().apply { add(Open("", Style(sizePx = baseSizePx), null, false, false, false)) }
        private var hidden = 0

        // The `pre` elements open, so a text's spaces are kept without a walk of [open]
        private var pres = 0

        // Where in [open] each table, row and cell open is, innermost last
        private val tables = ArrayList<Int>()

        // At a block's start, and after a space or a line break, a space is dropped
        private var atSpace = true

        // The cells begun in the table row being written
        private var cells = 0

        // Right after a `pre`'s start, where Qt drops a line break
        private var preStart = false

        // After a paragraph's start or end in a table's cell, which keeps the row a line: a space before the next text
        private var spaceBefore = false

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

        // A value in double quotes or unquoted has its entities decoded; one in single quotes Qt keeps as written. Qt
        // passes over an attribute given an empty value, so it changes nothing, nor one of the same name before it
        private fun attributes(text: String): Map<String, String> = ATTRIBUTE.findAll(text).mapNotNull { match ->
            val (name, equals, written) = match.destructured
            val value = when (written.firstOrNull()) {
                '"' -> decodeEntities(written.substring(1, written.length - 1))
                '\'' -> written.substring(1, written.length - 1)
                else -> decodeEntities(written)
            }
            if (equals.isNotEmpty() && value.isEmpty()) null else name.lowercase() to value
        }.toMap()

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
            val cell = name == "td" || name == "th"
            val grid = name == "tr" || name == "table"
            // A cell ends the cell open in its row, and a row the row open in its table, as Qt's
            // QTextHtmlParser::resolveParent() gives them a row and a table for their parent: looked for among the
            // tables, rows and cells open only, so many cells after many tags left open stay cheap
            if (cell || name == "tr") {
                val at = tables.lastOrNull { cell || (open[it].tag != "td" && open[it].tag != "th") }
                val tag = at?.let { open[it].tag }
                if (at != null && if (cell) tag == "td" || tag == "th" else tag == "tr") closeFrom(at)
            }
            val block = name in BLOCK_TAGS
            // A paragraph in a cell, as Qt's toHtml() writes every cell's, keeps its row a line, but a table in it
            val breaks = block && (!current().inCell || grid)
            if (breaks) flush()
            if (block && !breaks) spaceBefore = true
            if (grid) cells = 0
            if (cell) {
                if (cells++ > 0 && hidden == 0) append(CELL_GAP, current().style)
                atSpace = true
            }
            if (name == "img") return image(attributes)
            if (name in VOID_TAGS || selfClosing) return
            val parent = current()
            var style = parent.style
            var align = parent.align
            var adjusted: Float? = null
            when (name) {
                "b", "strong" -> style = style.copy(bold = true)

                "i", "em" -> style = style.copy(italic = true)

                "u" -> style = style.copy(underline = true)

                "s", "strike", "del" -> style = style.copy(strike = true)

                // A heading is sized against the label's own font, as below
                "th", "h1", "h2", "h3", "h4", "h5" -> {
                    style = style.copy(bold = true)
                    if (name != "th") adjusted = baseSizePx * FONT_SIZE_SCALE['6' - name[1]]
                }

                "sup" -> style = style.copy(raise = 0.5f)

                "sub" -> style = style.copy(raise = -1f / 6)

                "pre" -> {
                    style = style.copy(family = "monospace")
                    preStart = true
                    pres++
                }

                // An empty href is no link, as in Qt
                "a" -> attributes["href"]?.takeIf { it.isNotEmpty() }?.let { style = style.copy(href = it) }

                "center" -> align = HAlign.CENTER

                "font" -> {
                    attributes["color"]?.let { style = style.copy(color = CssColor.named(it)) }
                    attributes["face"]?.let { style = style.copy(family = it.split(',').first().trim()) }
                    adjusted = attributes["size"]?.let(::fontSize)
                }
            }
            if (block) attributes["align"]?.let(::hAlign)?.let { align = it }
            attributes["style"]?.let { css ->
                for ((property, value) in LabelStyle.parseDeclarations(css)) {
                    style = styled(style, property, value)
                    if (block && property == "text-align") hAlign(value)?.let { align = it }
                }
            }
            // Qt sizes a heading and a `font size` against the label's own font, and all in them, over a size a style
            // gives: their FontSizeAdjustment, h1 to h5's 3, 2, 1, 0 and -1, QTextFormatPrivate::resolveFont() applies
            // to the document's font, and over any size a format has; h6 Qt leaves as text
            (adjusted ?: parent.style.sizePx.takeIf { parent.sized })?.let { style = style.copy(sizePx = it) }
            if (cell || grid) tables.add(open.size)
            open.add(Open(name, style, align, adjusted != null || parent.sized, cell || (parent.inCell && !grid), breaks))
        }

        private fun close(name: String) {
            if (name in HIDDEN_TAGS) {
                hidden = (hidden - 1).coerceAtLeast(0)
                return
            }
            // Closes the innermost element of that name, and any left open inside it
            val at = open.indexOfLast { it.tag == name }
            if (at <= 0) return
            // Qt drops a line break right before a `pre`'s end
            if (name == "pre" && spans.lastOrNull()?.text?.endsWith('\n') == true) {
                val last = spans.removeAt(spans.size - 1)
                if (last.text.length > 1) spans.add(last.copy(text = last.text.dropLast(1)))
            }
            // After a cell, as at its start, Qt drops the spaces before the next
            if (name == "td" || name == "th") atSpace = true
            closeFrom(at)
        }

        // Closes the element open at [at] and any left open inside it
        private fun closeFrom(at: Int) {
            val closed = open.subList(at, open.size)
            when {
                closed.any { it.breaks } -> flush()
                closed.any { it.tag in BLOCK_TAGS } -> spaceBefore = true
            }
            while (open.size > at) if (open.removeAt(open.size - 1).tag == "pre") pres--
            while (tables.lastOrNull()?.let { it >= at } == true) tables.removeAt(tables.size - 1)
        }

        private fun current() = open.last()

        // An `img` with a `src`, as its own span; Qt takes `qrc:` for its resources and a `file:` URL for its file
        private fun image(attributes: Map<String, String>) {
            val src = attributes["src"]?.takeIf { it.isNotEmpty() } ?: return
            if (hidden > 0) return
            val path = when {
                src.startsWith("qrc:") -> ":" + src.substring(4)
                src.startsWith("file://") -> src.substring(7)
                src.startsWith("file:/") -> src.substring(5)
                else -> src
            }

            // A number alone, as Qt's QString::toDouble() reads one, else -2, as Qt keeps it: "8px", "50%" and "80f" are
            // none to Qt; and -1 is Qt's own for a side not given, as QTextHtmlImporter passes over it
            fun length(key: String): Float? {
                val v = attributes[key]?.trim() ?: return null
                val n = v.takeIf { t -> t.all { it.isDigit() || it in "+-.eE" } }?.toDoubleOrNull() ?: return -2f
                return if (n == -1.0) null else qRound(n)
            }
            val alt = attributes["alt"] ?: attributes["title"] ?: ""
            separate()
            spans.add(Span(OBJECT, current().style.copy(image = Image(path, length("width"), length("height"), alt))))
            atSpace = false
            preStart = false
        }

        private fun text(raw: String, atEnd: Boolean = false) {
            if (raw.isEmpty() || hidden > 0) return
            val pre = pres > 0
            // In a `pre`, spaces and line breaks as written, but the line break right after its start
            var text = if (pre) raw.replace("\r\n", "\n").let { if (preStart) it.removePrefix("\n") else it } else WHITESPACE.replace(raw, " ")
            preStart = false
            if ((atSpace || spaceBefore) && !pre) text = text.trimStart(' ')
            if (text.isEmpty()) return
            text = decodeEntities(text, atEnd)
            separate()
            append(text, current().style)
            atSpace = !pre && text.endsWith(' ')
        }

        // The space a paragraph's start or end in a cell leaves before what follows it in the row
        private fun separate() {
            if (spaceBefore && spans.lastOrNull()?.text?.lastOrNull()?.isWhitespace() == false) append(" ", current().style)
            spaceBefore = false
        }

        private fun append(text: String, style: Style) {
            val last = spans.lastOrNull()
            if (last != null && last.style == style) spans[spans.size - 1] = Span(last.text + text, style) else spans.add(Span(text, style))
        }

        // Ends the block being written, without its trailing spaces but in a `pre`
        private fun flush() {
            while (spans.isNotEmpty() && pres == 0) {
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
            spaceBefore = false
        }

        private fun hAlign(value: String): HAlign? = when (value.trim().lowercase()) {
            "left" -> HAlign.LEFT
            "right" -> HAlign.RIGHT
            "center", "middle" -> HAlign.CENTER
            "justify" -> HAlign.JUSTIFY
            else -> null
        }

        // HTML's size, 1 to 7, or from 3 as +1 or -2, as Qt's QTextHtmlParser reads it: a whole number or else 0, so
        // "12px" is 1's size, and from 3 only for a + or - written first
        private fun fontSize(value: String): Float {
            val n = value.trim().toIntOrNull() ?: 0
            val logical = if (value.startsWith("+") || value.startsWith("-")) 3 + n else n
            return baseSizePx * FONT_SIZE_SCALE[logical.coerceIn(1, 7) - 1]
        }

        private fun styled(style: Style, property: String, value: String): Style = when (property) {
            // Qt passes over a declaration with a `#` that is not a colour, draws text it reads no colour for black,
            // and leaves the background as it was
            "color" -> if (LabelStyle.hasUnreadableHex(value)) style else style.copy(color = LabelStyle.single(value)?.let(CssColor::parse) ?: CssColor.INVALID)

            "background-color", "background" ->
                LabelStyle.single(value)?.let(CssColor::parse)?.let { style.copy(background = it) } ?: style

            "font-size" -> LabelStyle.fontSize(value)?.let { style.copy(sizePx = it) } ?: style

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
