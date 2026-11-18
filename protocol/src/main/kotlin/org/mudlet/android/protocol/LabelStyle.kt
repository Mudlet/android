package org.mudlet.android.protocol

import kotlin.math.floor
import kotlin.math.roundToInt

/** How a label's text sits in it across and down: Qt's alignment flags, from `qproperty-alignment` or `align`. */
enum class HAlign { LEFT, CENTER, RIGHT, JUSTIFY }

enum class VAlign { TOP, CENTER, BOTTOM }

/** What fills a label's background: one colour, or a linear gradient across its box. Colours are 0xAARRGGBB. */
sealed interface LabelFill {
    data class Solid(val argb: Int) : LabelFill

    /** From ([x1], [y1]) to ([x2], [y2]), in fractions of the box; [stops] are positions from 0 to 1 and their colours. */
    data class Gradient(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val stops: List<Pair<Float, Int>>) : LabelFill
}

/** Four lengths in Mudlet's pixels, as CSS gives `padding` and `margin`. */
data class Edges(val top: Float, val right: Float, val bottom: Float, val left: Float) {
    companion object {
        val ZERO = Edges(0f, 0f, 0f, 0f)
    }
}

/**
 * What the app draws of a label's style sheet (docs/phase-f.md, "Labels"): the subset of Qt's style sheets for a
 * `QLabel` that Geyser and scripts write, made by [parse]. Lengths are in Mudlet's pixels, colours 0xAARRGGBB; null
 * is what the sheet did not set.
 */
data class LabelStyle(
    val background: LabelFill? = null,
    val color: Int? = null,
    val borderWidth: Float = 0f,
    /** Qt draws a border only with a style; any but `none` is drawn solid. */
    val borderStyled: Boolean = false,
    /**
     * Null where the sheet names none, which the app draws in the text's colour and Qt black (docs/divergences.md). One
     * named that Qt reads no colour off, such as `none`, it draws in the sheet's [color], and without one not at all,
     * as [of] reads it.
     */
    val borderColor: Int? = null,
    val borderRadius: Float = 0f,
    val padding: Edges = Edges.ZERO,
    val margin: Edges = Edges.ZERO,
    val fontSizePx: Float? = null,
    /** From 100 to 900; 400 is normal, 700 bold. */
    val fontWeight: Int? = null,
    val italic: Boolean? = null,
    val fontFamily: String? = null,
    val hAlign: HAlign? = null,
    val vAlign: VAlign? = null,
    /** The file `border-image` stretches over the box. */
    val borderImage: String? = null,
    /** The file `background-image` tiles the box with, at its own size. */
    val backgroundImage: String? = null,
    /** Whether the border's top edge still has the style Qt calls native, the widget's own: no sheet gave it another. */
    val nativeBorder: Boolean = true,
    /** Whether the sheet sets a radius, of 0 too, and not one below it. */
    val rounded: Boolean = false,
    /** Whether the sheet has an `image`, which the app does not draw. */
    val image: Boolean = false,
) {
    /**
     * The width of the border drawn: none without a style, nor where the widget keeps its [nativeFrame], which Qt
     * leaves no room for and draws no other edge of without a background (docs/divergences.md).
     */
    val drawnBorderWidth: Float get() = if (borderStyled && !nativeFrame) borderWidth else 0f

    /**
     * Whether the widget keeps its native frame, as Qt's `hasNativeBorder()` has it with a radius: no style for the
     * border's top edge but `native`, no radius, and no `border-image`. A button draws its desktop frame then, and a
     * label or a bar, which have none, draw no border.
     */
    val nativeFrame: Boolean get() = nativeBorder && !rounded && borderImage == null

    /**
     * Whether the sheet draws the label's box itself, as Qt counts it for a `QLabel` (its `hasDrawable()`): with a
     * background, an image, or a border that is not the native frame. The desktop fills a label with the colour
     * `setBackgroundColor()` gave it only where its sheet does not.
     */
    val drawsBox: Boolean get() = background != null || backgroundImage != null || image || !nativeFrame

    /**
     * This sheet with its [background] and [color] as a pair, for a widget that draws them over the opaque [under]:
     * where it sets only one, the other is white or black, whichever contrasts more with it ([ScrollThumb.whiteOn]),
     * not the widget's own, which could be as dark or as light. A background is judged as drawn, at its alpha over
     * [under]. A colour of alpha 0 (`transparent`), background or text, draws nothing, so it counts as not set, in
     * every case: text over a transparent background gets the background its colour would get alone, as on the
     * desktop what shows through is whatever is under the widget, for which [under] only stands in; a background with
     * transparent text gets the text that contrasts with it; and transparent text with no background is left out, for
     * the widget's own colour. The desktop draws such text as nothing; here it is not hidden, so that a line keeps what
     * is typed in it seen.
     */
    fun paired(under: Int): LabelStyle {
        fun contrasting(rgb: Int) = if (ScrollThumb.whiteOn(MapBehindText.luminance(rgb).toFloat())) -1 else 0xFF shl 24
        val text = color?.takeIf { it ushr 24 != 0 }
        val fill = background?.colour()?.takeIf { it ushr 24 != 0 }
        return when {
            text != null && fill == null -> copy(background = LabelFill.Solid(contrasting(text)))
            text == null && fill != null -> copy(color = contrasting(MapBehindText.blend(under, fill, (fill ushr 24) / 255f)))
            else -> copy(color = text)
        }
    }

    companion object {
        /**
         * The label's own [sheet]: its bare declarations and those of the rules that name a `TLabel` by one of its
         * classes or by `*`, each over those Qt ranks under it ([declarations]). A rule with any other selector, a
         * state or a sub-control is skipped, as is a declaration the app does not draw. With [selectors]
         * [TEXT_BOX_SELECTORS] or [COMMAND_LINE_SELECTORS], a text box's or a command line's sheet, the same way.
         */
        fun parse(sheet: String, selectors: Set<String> = LABEL_SELECTORS): LabelStyle = of(declarations(sheet, selectors))

        /**
         * [declarations], each over those before it, over [start]. A border colour they name that Qt reads no colour
         * off is drawn in the [color] the result ends with, and [start]'s border colour is taken as it is, so the
         * declarations Qt cascades together, a bar's and its button's, go in one call, not one call's result as
         * another's [start].
         */
        fun of(declarations: List<Pair<String, String>>, start: LabelStyle = LabelStyle()): LabelStyle {
            // Whether the border's colour was last named as one Qt reads no colour off. Qt then draws it in the
            // `color` the sheet ends with, which may come after it
            var unread = false
            val style = declarations.fold(start) { style, (property, value) ->
                style.with(property, value).also {
                    when (property) {
                        "border-color" -> unread = it.borderColor == null
                        "border" -> unread = it.borderColor == null && borderParts(value).colour != null
                    }
                }
            }
            return if (unread) style.copy(borderColor = style.color ?: 0) else style
        }

        /**
         * Whether [value] has a `#` and a name that is not a colour outside its parentheses and quotes, which stops Qt
         * reading the value, so that it passes over the whole declaration.
         */
        internal fun hasUnreadableHex(value: String): Boolean {
            var depth = 0
            var quote: Char? = null
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    quote != null -> if (c == '\\') {
                        i++
                    } else if (c == quote) {
                        quote = null
                    }

                    c == '"' || c == '\'' -> quote = c

                    c == '(' -> depth++

                    c == ')' -> depth--

                    c == '#' && depth == 0 -> {
                        val end = nameEnd(value, i + 1)
                        if (end > i + 1 && CssColor.parse(value.substring(i, end)) == null) return true
                        i = end - 1
                    }
                }
                i++
            }
            return false
        }

        /**
         * The declarations of [sheet] that apply to a label, or [selectors]' widget, each its lower-case property and
         * its value, in the order Qt ranks them, so each overrides those before it: a rule with more pseudo-states and
         * classes (`.QPushButton`) after one with fewer, then one naming a type after one for `*`, and in order where
         * they tie. A rule with pseudo-states applies where the widget is in each one named (`:checked`) and not in
         * each one negated (`:!checked`), by [states], the states it is or is not in by name; one naming a state not
         * in [states] is skipped.
         *
         * The sheet is read as Qt reads a widget's: as rules, up to the first thing in it that is not one, and where
         * there is such a thing, once more as the block of a `*` rule, which is how bare declarations apply. So a
         * sheet of rules loses what follows them, and one that starts with bare declarations its rules.
         */
        fun declarations(sheet: String, selectors: Set<String> = LABEL_SELECTORS, states: Map<String, Boolean> = emptyMap()): List<Pair<String, String>> {
            // Each rule's selectors and its declarations
            val rules = ArrayList<Pair<List<String>, List<Pair<String, String>>>>()
            // Scanned apart, as an unclosed comment keeps the last character of what is scanned: here the closing brace
            val read = readRules(scanned(sheet), rules) || readRules(scanned("* {$sheet}"), rules)
            // Rules Qt could read neither way it never puts in order, and of two that rank the same the first then wins
            return (if (read) rules else rules.asReversed())
                .mapNotNull { (group, declarations) -> group.mapNotNull { specificity(it, selectors, states) }.maxOrNull()?.let { it to declarations } }
                .sortedBy { it.first }.flatMap { it.second }
        }

        /**
         * [text] as Qt's parser gets it from its scanner, which reads nothing after a character outside a string that
         * starts none of its tokens, such as a `?`, an `&` or an accented letter of Latin-1, as an unquoted file name
         * may have: so the rule it is in is lost, and the rules after it. A comment is read as a space, and one with
         * no end takes all but the text's last character. Before that, as Qt's `Scanner::preprocess()`, a `\` and
         * up to 6 hex digits after it, of a number that fits 16 bits, are the character they number, so `\3b` is a
         * `;`; any other escape stays as it is, a character of the name or string it is in.
         */
        private fun scanned(sheet: String): String {
            val text = hexEscapesReplaced(sheet)
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val stops = when {
                    c == '"' || c == '\'' -> {
                        val end = stringEnd(text, i)
                        // What ends it short, a NUL or an escape of nothing, the scanner then stops at
                        out.append(text, i, end)
                        i = end
                        continue
                    }

                    c == '/' && text.startsWith("/*", i) -> {
                        val close = text.indexOf("*/", i + 2)
                        out.append(' ')
                        i = if (close >= 0) close + 2 else maxOf(i + 2, text.length - 1)
                        continue
                    }

                    c == '\\' -> escapeLength(text, i) == 0

                    c < ' ' -> !isSpace(c)

                    // A `<` starts only HTML's comment mark
                    c == '<' -> !text.startsWith("<!--", i)

                    c in '\u007f'..'\u00ff' || c in "$&?^`" -> true

                    // A percentage is a number's, which no name runs into; a `#` starts a colour or an object's name,
                    // an `@` a keyword
                    c == '%' -> {
                        var start = i
                        while (start > 0 && (text[start - 1] in '0'..'9' || text[start - 1] == '.')) start--
                        // A `-` before the number is its sign, or one of a name's after a name's character
                        val name = start > 0 && isNameChar(text[start - 1]) && (text[start - 1] != '-' || (start > 1 && isNameChar(text[start - 2])))
                        start == i || name
                    }

                    c == '#' -> nameUnit(text, i + 1) == 0

                    c == '@' -> !startsName(text, i + 1)

                    else -> false
                }
                if (stops) break
                val length = maxOf(nameUnit(text, i), 1)
                out.append(text, i, i + length)
                i += length
            }
            return out.toString()
        }

        // Qt's `Scanner::preprocess()`: a `\` and up to 6 hex digits after it, of a number that fits 16 bits, are
        // replaced with that character, which is not read as the start of another escape
        private fun hexEscapesReplaced(text: String): String {
            if ('\\' !in text) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                if (text[i] != '\\') {
                    out.append(text[i++])
                    continue
                }
                var end = i + 1
                // Qt counts a seventh digit, which then stays
                while (end < text.length && end - i <= 7 && isHexDigit(text[end])) end++
                val digits = minOf(end - i - 1, 6)
                val code = text.substring(i + 1, i + 1 + digits).toIntOrNull(16)?.takeIf { it <= 0xffff }
                if (code != null) {
                    out.append(code.toChar())
                    i += 1 + digits
                } else {
                    out.append('\\')
                    i++
                }
            }
            return out.toString()
        }

        /** Adds the rules [text] starts with to [rules], as Qt's parser reads them; false where it stops at what is not one. */
        private fun readRules(text: String, rules: MutableList<Pair<List<String>, List<Pair<String, String>>>>): Boolean {
            var i = skipPrelude(text) ?: return false
            while (true) {
                i = skipGap(text, i)
                if (i >= text.length) break
                if (text[i] == '@') {
                    i = skipAtRule(text, i) ?: return false
                    continue
                }
                val open = text.indexOf('{', i)
                if (open < 0) return false
                val group = selectorGroup(text, i, open) ?: return false
                val (declarations, end) = readBlock(text, open + 1) ?: return false
                rules.add(group.map(::unescaped) to declarations)
                i = skipSpace(text, end)
            }
            return true
        }

        // The selectors from [from] to [to] in [text], split at their commas; null where Qt reads no selector there
        private fun selectorGroup(text: String, from: Int, to: Int): List<String>? = text.substring(from, to).split(',').map { it.trim(::isSpace) }.takeIf { it.all(SELECTOR::matches) }

        /**
         * Where the charset and the imports at the start of [text] end, as Qt's `Parser::parse()` reads them; null
         * where Qt stops at one. The charset counts only as the very first thing, and neither changes what is drawn.
         */
        private fun skipPrelude(text: String): Int? {
            var i = 0
            if (text.startsWith('@') && atKeyword(text, 0).endsWith("charset")) {
                i = CHARSET.matchAt(text, nameEnd(text, 1))?.range?.last?.plus(1) ?: return null
            }
            i = skipGap(text, i)
            while (i < text.length && text[i] == '@' && atKeyword(text, i).endsWith("import")) {
                i = skipGap(text, IMPORT.matchAt(text, nameEnd(text, i + 1))?.range?.last?.plus(1) ?: return null)
            }
            return i
        }

        private fun atKeyword(text: String, at: Int) = unescaped(text.substring(at + 1, nameEnd(text, at + 1))).lowercase()

        /**
         * Where the rule of a keyword that starts at [at] in [text] ends, as Qt skips one: a block of rules for media,
         * a page's block of declarations, and any other keyword up to the end of its block; null where Qt stops at it.
         * Qt tells media and a page by how the keyword ends, and matches no widget by what is in them.
         */
        private fun skipAtRule(text: String, at: Int): Int? {
            val end = nameEnd(text, at + 1)
            val keyword = atKeyword(text, at)
            // Looked for only where the keyword has a block, as a sheet of many other keywords would take its square
            val open by lazy { text.indexOf('{', end) }
            return when {
                keyword.endsWith("media") -> {
                    if (open < 0 || !MEDIA.matches(text.substring(end, open))) return null
                    var i = skipSpace(text, open + 1)
                    while (i < text.length && text[i] != '}') {
                        val brace = text.indexOf('{', i)
                        if (brace < 0 || selectorGroup(text, i, brace) == null) return null
                        i = skipSpace(text, readBlock(text, brace + 1)?.second ?: return null)
                    }
                    if (i < text.length) skipSpace(text, i + 1) else null
                }

                keyword.endsWith("page") -> if (open >= 0 && PAGE.matches(text.substring(end, open))) readBlock(text, open + 1, strict = true)?.let { skipSpace(text, it.second) } else null

                else -> until(text, at, '}').let { (closed, after) -> if (closed) skipSpace(text, after) else null }
            }
        }

        /**
         * The declarations of the block that starts at [start] in [text], after its brace, and where the block ends,
         * as Qt's `Parser::parseRuleset()` reads one; null where Qt drops the rule and all after it. A declaration is
         * a name, a colon and a value, and after it comes a `;` or the closing brace; where what comes is neither,
         * Qt drops the rule. What starts with a name and is not a declaration is skipped up to the next `;` outside
         * braces, brackets and parentheses, and where the block has none, it ends there, with what it had. A [strict]
         * block, a page's, skips nothing: Qt drops it and all after it instead.
         */
        private fun readBlock(text: String, start: Int, strict: Boolean = false): Pair<List<Pair<String, String>>, Int>? {
            val declarations = ArrayList<Pair<String, String>>()
            // Found once, as each declaration skipped asks for it, so a block of many takes as long as it is long
            val blockEnd by lazy { until(text, start, '}', braces = 1) }
            var i = start
            while (true) {
                i = skipSpace(text, i)
                // A name with a `(` after it is a function's, which Qt does not take for a declaration's
                if (startsName(text, i) && text.getOrNull(nameEnd(text, i)) != '(') {
                    val end = readDeclaration(text, i, declarations)
                    if (end < 0) {
                        if (strict) return null
                        // Qt counts the braces from the one just before what it skips, so right after the block's own
                        // brace no `;` in the block ends the skipping. One past the block's end would end the block
                        // all the same, so the search stops there, and a sheet of many such blocks is read in one pass
                        val (closed, afterBlock) = blockEnd
                        val (semicolon, afterIt) = until(text, i, ';', braces = if (text[i - 1] == '{') 1 else 0, to = afterBlock)
                        if (!semicolon || afterIt >= afterBlock) return if (closed) declarations to afterBlock else null
                        i = afterIt - 1
                    } else {
                        i = end
                    }
                }
                if (i < text.length && text[i] == ';') i++ else break
            }
            return if (i < text.length && text[i] == '}') declarations to i + 1 else null
        }

        /**
         * Adds the declaration that starts at [start] in [text] to [declarations] and gives where it ends, or -1 for
         * what Qt does not read as one: no colon after the name, or no value. The value ends where Qt's does, at what
         * is no part of one: a `;`, a brace, a colon, and so on.
         */
        private fun readDeclaration(text: String, start: Int, declarations: MutableList<Pair<String, String>>): Int {
            var i = nameEnd(text, start)
            val property = unescaped(text.substring(start, i)).lowercase()
            i = skipSpace(text, i)
            if (i >= text.length || text[i] != ':') return -1
            val valueStart = skipSpace(text, i + 1)
            i = valueStart
            while (i < text.length) {
                val c = text[i]
                when {
                    // A string with no closing quote on its line is no part of a value
                    c == '"' || c == '\'' -> {
                        val end = stringEnd(text, i)
                        if (end - i < 2 || text[end - 1] != c) break
                        i = end
                    }

                    // A colour, or nothing Qt reads
                    c == '#' -> {
                        val end = nameEnd(text, i + 1)
                        if (end - i - 1 !in HEX_LENGTHS || (i + 1 until end).any { !isHexDigit(text[it]) }) return -1
                        i = end
                    }

                    // A sign is a number's, and a `-` starts a name too where no name goes on through it
                    (c == '+' || (c == '-' && (i == valueStart || !isNameChar(text[i - 1])) && !startsName(text, i))) &&
                        !(i + 1 < text.length && (text[i + 1] in '0'..'9' || text[i + 1] == '.')) -> return -1

                    // An escaped character is one of a name's, whatever it is
                    c == '\\' -> i += 2

                    // A function's arguments, to its closing parenthesis
                    c == '(' && i > valueStart && isNameChar(text[i - 1]) -> {
                        val (closed, after) = until(text, i + 1, ')', parentheses = 1)
                        if (!closed) return -1
                        i = after
                    }

                    c in VALUE_ENDS || (c == '.' && !(i + 1 < text.length && text[i + 1] in '0'..'9')) -> break

                    else -> i++
                }
            }
            val value = text.substring(valueStart, i).trim(::isSpace)
            if (value.isEmpty() || value[0] == ',' || value[0] == '/') return -1
            if (i < text.length && text[i] == '!') {
                val word = skipSpace(text, i + 1)
                if (text.startsWith("important", word, ignoreCase = true)) i = skipSpace(text, word + "important".length)
            }
            declarations.add(property to unescaped(value))
            return i
        }

        /**
         * Whether there is a [target] from [from] on in [text] that is outside braces, brackets and parentheses,
         * counted from [braces] and [parentheses] open ones, and the place after it; else where the search stopped,
         * which is at one closed that was not open, or at [to]. As Qt's `Parser::until()`.
         */
        private fun until(text: String, from: Int, target: Char, braces: Int = 0, parentheses: Int = 0, to: Int = text.length): Pair<Boolean, Int> {
            var brace = braces
            var bracket = 0
            var parenthesis = parentheses
            var i = from
            while (i < to) {
                val c = text[i++]
                when (c) {
                    '{' -> brace++
                    '}' -> brace--
                    '[' -> bracket++
                    ']' -> bracket--
                    '(' -> parenthesis++
                    ')' -> parenthesis--
                    '"', '\'' -> i = stringEnd(text, i - 1)
                    '\\' -> i++
                }
                if (c == target && brace <= 0 && bracket <= 0 && parenthesis <= 0) return true to i
                if (brace < 0 || bracket < 0 || parenthesis < 0) return false to i - 1
            }
            return false to i
        }

        /**
         * Where the string that starts at [start] in [text] ends: after its closing quote, or where Qt's string with no
         * closing quote ends, at the end of its line or at what Qt reads nothing after, a NUL or a `\\` at the end of
         * the text. A `\\` and the character after it, a line's end too, are a character of the string.
         */
        private fun stringEnd(text: String, start: Int): Int {
            var i = start + 1
            while (i < text.length && text[i] != text[start] && text[i] !in LINE_ENDS && text[i] != '\u0000') {
                if (text[i] == '\\') {
                    if (i + 1 >= text.length || text[i + 1] == '\u0000') return i
                    i++
                }
                i++
            }
            return if (i < text.length && text[i] == text[start]) i + 1 else i
        }

        private fun skipSpace(text: String, from: Int): Int {
            var i = from
            while (i < text.length && isSpace(text[i])) i++
            return i
        }

        // Past the spaces and HTML's comment marks, `<!--` and `-->`, which Qt passes over between rules
        private fun skipGap(text: String, from: Int): Int {
            var i = from
            while (true) {
                i = skipSpace(text, i)
                i += when {
                    text.startsWith("<!--", i) -> 4
                    text.startsWith("-->", i) -> 3
                    else -> return i
                }
            }
        }

        // Qt's space, and no other: to Qt one of Unicode's other spaces is a character of a name
        private fun isSpace(c: Char) = c == ' ' || c == '\t' || c in LINE_ENDS

        private fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        // Qt counts any character from U+0100 up as a name's, a letter or not
        private fun isNameChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c >= '\u0100'

        /** How many characters of [text] from [at] are one of a name's: 2 for an escape, 1 for another, else 0. */
        private fun nameUnit(text: String, at: Int): Int = when {
            at >= text.length -> 0
            text[at] == '\\' -> escapeLength(text, at)
            isNameChar(text[at]) -> 1
            else -> 0
        }

        // An escape is a `\\` and any character but a line's end or, as those Qt has read already, a hex digit;
        // Qt's scanner reads nothing after any other `\\`
        private fun escapeLength(text: String, at: Int): Int {
            val next = text.getOrNull(at + 1) ?: return 0
            return if (next in LINE_ENDS || next == '\u0000' || isHexDigit(next)) 0 else 2
        }

        // Where the name that starts at [start] in [text] ends
        private fun nameEnd(text: String, start: Int): Int {
            var i = start
            while (true) i += nameUnit(text, i).takeIf { it > 0 } ?: return i
        }

        // A name starts with a letter, a `_`, an escape or a character from U+0100 up, after a `-` or not
        private fun startsName(text: String, at: Int): Boolean {
            val i = if (at < text.length && text[at] == '-') at + 1 else at
            return nameUnit(text, i) > 0 && text[i] != '-' && text[i] !in '0'..'9'
        }

        // [text] with each escape's `\\` taken away, as Qt reads a name, a string or a value
        private fun unescaped(text: String): String {
            if ('\\' !in text) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                if (text[i] == '\\' && i + 1 < text.length) i++
                out.append(text[i++])
            }
            return out.toString()
        }

        /**
         * How [selector] ranks among the rules that apply to a widget of [selectors] in [states], as Qt counts it: ten
         * for each pseudo-state or class (`.QPushButton`), one for a type; null where it does not apply to it.
         */
        private fun specificity(selector: String, selectors: Set<String>, states: Map<String, Boolean>): Int? {
            if ("::" in selector) return null
            // Not trimmed: a space before a colon is Qt's descendant, and an escaped one a name's
            val parts = selector.split(':')
            val pseudo = parts.drop(1)
            // `:checked` alone is `*:checked`
            val type = parts[0].ifEmpty { if (pseudo.isEmpty()) return null else "*" }
            if (type !in selectors) return null
            for (state in pseudo) {
                val negated = state.startsWith('!')
                val inState = states[state.removePrefix("!").lowercase()] ?: return null
                if (inState == negated) return null
            }
            val typeRank = when {
                type == "*" -> 0
                type.startsWith('.') -> 10
                else -> 1
            }
            return pseudo.size * 10 + typeRank
        }

        /** `a: b; c: d`, as a style sheet's block or a rich text's `style` attribute has them. */
        fun parseDeclarations(block: String): List<Pair<String, String>> = splitTopLevel(block, ';').mapNotNull { declaration ->
            val colon = declaration.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val property = declaration.substring(0, colon).trim().lowercase()
            val value = declaration.substring(colon + 1).trim().removeSuffix("!important").trim()
            if (property.isEmpty() || value.isEmpty()) null else property to value
        }

        /**
         * A length in Mudlet's pixels: `px` or none, or `pt` at Qt's 96 dots per inch, [MAX_LENGTH] at most either
         * way; null for another unit, or for what is not a length.
         */
        fun length(value: String): Float? = measure(value) { unit ->
            when (unit) {
                "", "px" -> 1f
                "pt" -> PX_PER_PT
                else -> null
            }
        }

        /**
         * A `font-size` in Mudlet's pixels as Qt reads one, in a sheet or in a rich text's `style`: in `px`, rounded to a
         * whole number as Qt 6.8 and later do (6.4.2 ignores one with a fraction), or in `pt` at Qt's 96 dots per inch.
         * Null for a number with no unit or another, also with a space before the unit, which Qt takes for no size, and
         * for a size of 0 or less, which would hide the text: Qt keeps the size it had.
         */
        fun fontSize(value: String): Float? {
            val size = value.trim().lowercase()
            // To Qt a space ends the number, which then has no unit
            if (size.any { it.isWhitespace() } || !(size.endsWith("px") || size.endsWith("pt"))) return null
            val pixels = length(size) ?: return null
            return (if (size.endsWith("px")) pixels.roundToInt().toFloat() else pixels).takeIf { it > 0f }
        }

        /**
         * A length of a box's, its padding, margin, border or radius, in Mudlet's pixels as Qt reads one: `px` or
         * none, and 0 in a unit Qt does not know there, `pt` among them, which so takes away what was set before it.
         * Null for what is not a number, and for `em` and `ex`, which are the measures of a font the app does not have.
         */
        private fun boxLength(value: String): Float? = measure(value) { unit ->
            when (unit) {
                "", "px" -> 1f
                "em", "ex" -> null
                else -> 0f
            }
        }

        /** The number [value] is, times what [pixels] gives for the unit after it; null where either has none. */
        private fun measure(value: String, pixels: (String) -> Float?): Float? {
            val match = LENGTH.matchEntire(value.trim().lowercase()) ?: return null
            val n = match.groupValues[1].toFloatOrNull() ?: return null
            // Not multiplied, as a number of dozens of digits is infinite, and that times 0 is not a number
            return pixels(match.groupValues[2])?.let { if (it == 0f) 0f else bounded(n * it) }
        }

        /**
         * [n] as a number of a sheet's the app can draw with: null for one that is not a number, and [MAX_LENGTH] at
         * most either way. Every length and gradient coordinate the parser reads comes through here, as Compose and
         * Android throw on a size, a corner or a gradient that is infinite or not a number, which a length of
         * dozens of digits becomes once it is multiplied into the screen's pixels.
         */
        private fun bounded(n: Float?): Float? = n?.takeUnless { it.isNaN() }?.coerceIn(-MAX_LENGTH, MAX_LENGTH)

        /** A CSS `font-weight`: a keyword or a number from 100 to 900. */
        fun fontWeight(value: String): Int? = when (val v = value.trim().lowercase()) {
            "normal" -> 400
            "bold", "bolder" -> 700
            "lighter" -> 300
            else -> v.toIntOrNull()?.takeIf { it in 1..1000 }
        }

        private fun alignment(value: String): Pair<HAlign?, VAlign?> {
            var h: HAlign? = null
            var v: VAlign? = null
            for (flag in value.split('|', ' ', ',').map { it.trim().trim('\'', '"').removePrefix("Qt::") }) {
                when (flag) {
                    "AlignLeft", "AlignLeading" -> h = HAlign.LEFT

                    "AlignRight", "AlignTrailing" -> h = HAlign.RIGHT

                    "AlignHCenter" -> h = HAlign.CENTER

                    "AlignJustify" -> h = HAlign.JUSTIFY

                    "AlignTop" -> v = VAlign.TOP

                    "AlignBottom" -> v = VAlign.BOTTOM

                    "AlignVCenter" -> v = VAlign.CENTER

                    "AlignCenter" -> {
                        h = HAlign.CENTER
                        v = VAlign.CENTER
                    }
                }
            }
            return h to v
        }

        private fun edges(value: String): Edges? {
            val parts = splitTopLevel(value, ' ').map { boxLength(it) ?: return null }
            return when (parts.size) {
                1 -> Edges(parts[0], parts[0], parts[0], parts[0])
                2 -> Edges(parts[0], parts[1], parts[0], parts[1])
                3 -> Edges(parts[0], parts[1], parts[2], parts[1])
                4 -> Edges(parts[0], parts[1], parts[2], parts[3])
                else -> null
            }
        }

        private class BorderParts(val width: String?, val style: String?, val colour: String?)

        // The `border` shorthand [value]'s width, style and colour, as Qt reads them: in that order, each where it may
        // be, and what follows the colour not at all
        private fun borderParts(value: String): BorderParts {
            val parts = splitTopLevel(value, ' ')
            var i = 0
            val width = parts.getOrNull(i)?.takeIf { LENGTH.matches(it.lowercase()) }?.also { i++ }
            val style = parts.getOrNull(i)?.takeIf { it.lowercase() in BORDER_STYLES || it.equals("native", ignoreCase = true) }?.also { i++ }
            return BorderParts(width, style, parts.getOrNull(i))
        }

        private fun url(value: String): String? = URL.find(value)?.groupValues?.get(1)?.trim()?.trim('"', '\'')

        private fun LabelStyle.with(property: String, value: String): LabelStyle = when (property) {
            "background" -> {
                // The shorthand sets what it does not name back to none, as Qt's does, and reads its words in turn: a
                // file, `none` for no file (so one named after it stays), a repeat or another word of Qt's own pass,
                // and anything else is the colour, or none where Qt reads none off it
                var s = copy(background = null, backgroundImage = null)
                for (part in splitTopLevel(value, ' ')) {
                    when {
                        part.startsWith("url(", ignoreCase = true) -> s = s.copy(backgroundImage = url(part))
                        part.equals("none", ignoreCase = true) -> s = s.copy(backgroundImage = null)
                        part.equals("transparent", ignoreCase = true) -> s = s.copy(background = LabelFill.Solid(0))
                        part.removeSurrounding("\"").removeSurrounding("'").lowercase() in REPEATS || CssColor.isKeyword(part) -> {}
                        else -> s = s.copy(background = fill(part))
                    }
                }
                s
            }

            // Each takes one value, and one Qt reads no colour off sets it back to none
            "background-color" -> copy(background = single(value)?.let(::fill))

            "color" -> copy(color = single(value)?.let(CssColor::parse))

            // The shorthand sets what it does not name back to none, as Qt's does
            "border" -> borderParts(value).let { parts ->
                copy(
                    borderWidth = parts.width?.let(::boxLength) ?: 0f,
                    borderStyled = parts.style != null && parts.style.lowercase() !in setOf("none", "native"),
                    borderColor = parts.colour?.let(::borderColour),
                    nativeBorder = parts.style.equals("native", ignoreCase = true),
                )
            }

            "border-width" -> boxLength(splitTopLevel(value, ' ').first())?.let { copy(borderWidth = it) } ?: this

            // Qt reads the top edge's style off the first, which is native only by that name
            "border-style" -> (value.lowercase().takeIf { it in BORDER_STYLES }?.let { copy(borderStyled = it != "none") } ?: this)
                .copy(nativeBorder = splitTopLevel(value, ' ').firstOrNull().equals("native", ignoreCase = true))

            // Not drawn, but each takes the native border away as `border` and `border-style` do
            "border-top" -> copy(nativeBorder = borderParts(value).style.equals("native", ignoreCase = true))

            "border-top-style" -> copy(nativeBorder = value.trim().equals("native", ignoreCase = true))

            "border-color" -> copy(borderColor = borderColour(splitTopLevel(value, ' ').first()))

            // Qt draws a negative radius as none, and a shape's corner may not be negative
            "border-radius" -> (boxLength(splitTopLevel(value, ' ').first())?.let { copy(borderRadius = it.coerceAtLeast(0f)) } ?: this).copy(rounded = !value.startsWith('-'))

            // Not drawn, but a corner's radius makes the border the sheet's as `border-radius` does
            "border-top-left-radius", "border-top-right-radius", "border-bottom-left-radius", "border-bottom-right-radius" -> copy(rounded = rounded || !value.startsWith('-'))

            "padding" -> edges(value)?.let { copy(padding = it) } ?: this

            "margin" -> edges(value)?.let { copy(margin = it) } ?: this

            "font-size" -> fontSize(value)?.let { copy(fontSizePx = it) } ?: this

            "font-weight" -> fontWeight(value)?.let { copy(fontWeight = it) } ?: this

            "font-style" -> copy(italic = value.trim().lowercase() in setOf("italic", "oblique"))

            "font-family" -> copy(fontFamily = value.split(',').first().trim().trim('"', '\''))

            "qproperty-alignment" -> alignment(value).let { (h, v) -> copy(hAlign = h ?: hAlign, vAlign = v ?: vAlign) }

            "border-image" -> copy(borderImage = url(value))

            "image" -> copy(image = true)

            // Qt takes only a `url()` here, so `none` leaves the image a rule before it set
            "background-image" -> url(value)?.let { copy(backgroundImage = it) } ?: this

            else -> this
        }

        // Qt reads a gradient only by its very name, and a function of another name is no colour
        private fun fill(value: String): LabelFill? = when (value.substringBefore('(', "").lowercase()) {
            // Qt fills with the first colour where the gradient has one or its two points are the same, and Android's
            // gradient needs two colours and draws the last where its points are the same
            "qlineargradient" -> gradient(value)?.let { if (it.stops.size == 1 || it.length() == 0f) LabelFill.Solid(it.stops[0].second) else it }

            // Drawn as the colour of its first stop
            "qradialgradient", "qconicalgradient" -> gradient(value)?.let { LabelFill.Solid(it.stops.first().second) }

            else -> CssColor.parse(value)?.let { LabelFill.Solid(it) }
        }

        // A border's colour, a gradient too, which Qt fills each edge with across the edge's own box. The app draws one
        // colour, so a linear gradient is the one it has at the middle of its box, where every edge has the same
        private fun borderColour(value: String): Int? = fill(value)?.colour()

        private fun LabelFill.colour(): Int = when (this) {
            is LabelFill.Gradient -> middle()
            is LabelFill.Solid -> argb
        }

        // As Qt's gradient spreads: past the line between its points it keeps their colours, and between two stops it
        // blends their colours by their alpha
        private fun LabelFill.Gradient.middle(): Int {
            val at = ((0.5f - x1) * (x2 - x1) + (0.5f - y1) * (y2 - y1)) / length()
            val before = stops.lastOrNull { it.first <= at } ?: return stops.first().second
            val after = stops.firstOrNull { it.first > at } ?: return stops.last().second
            val f = (at - before.first) / (after.first - before.first)
            fun alpha(c: Int) = (c ushr 24) / 255f
            val a = alpha(before.second) * (1 - f) + alpha(after.second) * f
            if (a == 0f) return 0
            fun channel(shift: Int): Int {
                fun weighted(c: Int) = (c shr shift and 0xFF) * alpha(c)
                return ((weighted(before.second) * (1 - f) + weighted(after.second) * f) / a).roundToInt().coerceIn(0, 255) shl shift
            }
            return ((a * 255).roundToInt() shl 24) or channel(16) or channel(8) or channel(0)
        }

        // [value] where it is one value, as Qt's declarations of one colour take
        internal fun single(value: String): String? = value.takeIf { v -> listOf(' ', ',', '/').all { splitTopLevel(v, it).size == 1 } }

        private fun LabelFill.Gradient.length() = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)

        // A gradient's points and its stops, in order, as Qt keeps them: a position that is not a number is 0, but a
        // percentage is its number, one past 0 to 1 is left out, and a stop at a position one before it had takes its
        // place; with none left Qt draws black to white. Null where Qt reads no arguments, or a stop is not a position
        // and a colour, or has a `#` that is not a colour, which takes away Qt's whole gradient
        private fun gradient(value: String): LabelFill.Gradient? {
            val arguments = gradientArguments(value.substringAfter('(', "").substringBeforeLast(')', "")) ?: return null
            val stops = sortedMapOf<Float, Int>()
            for ((name, values) in arguments) {
                if (name != "stop") continue
                val (place, colour) = values
                if (hasUnreadableHex(colour)) return null
                val position = place.removeSuffix("%").toFloatOrNull() ?: 0f
                // Qt draws a stop it reads no colour off with its invalid one
                if (position in 0f..1f) stops[position + 0f] = CssColor.parse(colour) ?: CssColor.INVALID
            }
            if (stops.isEmpty()) stops += mapOf(0f to 0xFF000000.toInt(), 1f to 0xFFFFFFFF.toInt())
            val points = arguments.associate { (name, values) -> name to values[0] }
            fun coordinate(name: String) = bounded(points[name]?.toFloatOrNull()) ?: 0f
            return LabelFill.Gradient(coordinate("x1"), coordinate("y1"), coordinate("x2"), coordinate("y2"), stops.toList())
        }

        // Each of a gradient's arguments, its name and its value, a stop's two words. As Qt reads them, the comma after
        // one may be left out, and the last may end with one with the `)` right after it; null where an argument has
        // no name, a word is not one of Qt's terms, or there is only space before a comma or between the last and the
        // `)`, which takes away Qt's whole gradient
        private fun gradientArguments(inner: String): List<Pair<String, List<String>>>? {
            val parts = topLevelParts(inner.trimStart(::isSpace), ',').let { if (it.last().isEmpty()) it.dropLast(1) else it }
            if (parts.any { part -> part.all(::isSpace) }) return null
            val arguments = ArrayList<Pair<String, List<String>>>()
            for (part in parts) {
                val words = ArrayDeque(splitTopLevel(part, ' '))
                while (words.isNotEmpty()) {
                    val word = words.removeFirst()
                    val name = word.substringBefore(':')
                    val value = if (':' in word) word.substringAfter(':') else words.removeFirstOrNull()?.takeIf { it.startsWith(':') }?.drop(1)
                    if (!startsName(name, 0) || nameEnd(name, 0) != name.length || value == null) return null
                    if (value.isNotEmpty()) words.addFirst(value)
                    val stop = name.equals("stop", ignoreCase = true)
                    val values = List(if (stop) 2 else 1) { words.removeFirstOrNull().orEmpty() }
                    if (!values.all { isValue(it, stop) }) return null
                    arguments += name.lowercase() to values
                }
            }
            return arguments
        }

        // Whether Qt reads [word] as an argument's value: one of its terms and nothing after it, as a stop's two words
        // must be. Another argument's value may also be none, or a sign and what follows it, which Qt passes over
        private fun isValue(word: String, stop: Boolean): Boolean {
            val end = termEnd(word)
            return end == word.length || (!stop && end == null && (word.isEmpty() || word[0] == '+' || word[0] == '-'))
        }

        // Where the one of Qt's terms that [word] starts with ends: a name, or a function's name and its parentheses, a
        // string, a `#` and a name, or a number, a percentage or a length, after a sign or not; null where it starts
        // with none, as with a `;`, `.` or `>`
        private fun termEnd(word: String): Int? = when {
            word.startsWith('"') || word.startsWith('\'') -> STRING.matchAt(word, 0)?.range?.last?.plus(1)

            word.startsWith('#') -> nameEnd(word, 1).takeIf { it > 1 }

            startsName(word, 0) -> nameEnd(word, 0).let { end ->
                if (word.getOrNull(end) != '(') end else until(word, end + 1, ')', parentheses = 1).let { (closed, at) -> at.takeIf { closed } }
            }

            else -> NUMBER.matchAt(word, 0)?.range?.last?.plus(1)?.let { end ->
                when {
                    word.getOrNull(end) == '%' -> end + 1
                    startsName(word, end) -> nameEnd(word, end)
                    else -> end
                }
            }
        }

        /** [text] split at each [separator] outside parentheses and quotes, the parts trimmed and the empty ones dropped. */
        fun splitTopLevel(text: String, separator: Char): List<String> = topLevelParts(text, separator).map { it.trim(::isSpace) }.filter { it.isNotEmpty() }

        // [text] split at each [separator] outside parentheses and quotes, each part as it is
        private fun topLevelParts(text: String, separator: Char): List<String> {
            val parts = ArrayList<String>()
            var depth = 0
            var quote: Char? = null
            var start = 0
            for ((i, c) in text.withIndex()) {
                when {
                    quote != null -> if (c == quote) quote = null

                    c == '"' || c == '\'' -> quote = c

                    c == '(' -> depth++

                    c == ')' -> depth = (depth - 1).coerceAtLeast(0)

                    depth == 0 && (c == separator || (separator == ' ' && isSpace(c))) -> {
                        parts.add(text.substring(start, i))
                        start = i + 1
                    }
                }
            }
            parts.add(text.substring(start))
            return parts
        }

        /** Mudlet's pixels per point, at the 96 dots per inch Qt takes. */
        const val PX_PER_PT = 96f / 72f

        /** No length of a sheet's is longer than this, in Mudlet's pixels: past the side of any screen the app has. */
        const val MAX_LENGTH = 4096f

        // Mudlet's `TLabel` is a `QLabel` with its own `Q_OBJECT`, so Qt's selectors match it by each class it
        // inherits, and by `.TLabel`, the class it is
        private val LABEL_SELECTORS = setOf("TLabel", ".TLabel", "QLabel", "QFrame", "QWidget", "QObject", "*")

        /** The rules of a text box's sheet that apply to it: Mudlet's `TTextBox` is a `QPlainTextEdit` as its `TLabel` is a `QLabel`. */
        val TEXT_BOX_SELECTORS = setOf("TTextBox", ".TTextBox", "QPlainTextEdit", "QAbstractScrollArea", "QFrame", "QWidget", "QObject", "*")

        /** The rules of a command line's sheet that apply to it: Mudlet's `TCommandLine` is a `QPlainTextEdit` too. */
        val COMMAND_LINE_SELECTORS = setOf("TCommandLine", ".TCommandLine", "QPlainTextEdit", "QAbstractScrollArea", "QFrame", "QWidget", "QObject", "*")

        // Qt's repeats, which its `background` shorthand passes over
        private val REPEATS = setOf("no-repeat", "repeat-x", "repeat-xy", "repeat-y")
        private val BORDER_STYLES = setOf("none", "solid", "dashed", "dotted", "double", "groove", "ridge", "inset", "outset", "dot-dash", "dot-dot-dash")

        // A repeated group here is possessive: java.util.regex repeats one that is not by a call each time, which a
        // long sheet overflows the stack with, and none of these can match more by giving a repeat back
        private const val WS = "[ \\t\\n\\r\u000c]*"
        private const val IDENT = "[A-Za-z_-][\\w-]*"
        private const val MEDIA_LIST = "$IDENT(?:$WS,$WS$IDENT)*+"

        // The media a rule of media names, and a page's pseudo-page
        private val MEDIA = Regex("$WS$MEDIA_LIST$WS")
        private val PAGE = Regex("$WS(?::$IDENT)?$WS")
        private const val STRINGS = "\"(?:[^\"\\\\\n\r\u000c\u0000]|\\\\[^\u0000])*+\"|'(?:[^'\\\\\n\r\u000c\u0000]|\\\\[^\u0000])*+'"

        // A string and the `;` straight after it
        private val CHARSET = Regex("$WS(?:$STRINGS);")
        private val STRING = Regex(STRINGS)

        // A string or a url, then any media, then a `;`
        private val IMPORT = Regex(
            "$WS(?:$STRINGS|url\\($WS(?:$STRINGS|[^)\"' \\t\\n\\r\u000c]*)$WS\\))$WS" +
                "(?:$MEDIA_LIST)?$WS;",
        )

        // What ends a line to Qt, and so a string with no closing quote
        private const val LINE_ENDS = "\n\r\u000c"

        // What Qt can read as a selector, as far as telling one from a declaration takes: no `;`, brace, string or
        // keyword outside brackets, and a name after each colon, which a declaration's value after a space is not
        private val SELECTOR = Regex("(?:\\\\@|[^;:{}()\\[\\]*\"'@]|(?<![\\w-])\\*(?![\\w-])|\\[[^\\[\\]]*]|::?!?(?=[A-Za-z_-]))++")

        // What ends a declaration's value to Qt, besides the `;` and the brace that may follow it, and a `.` that
        // starts no number
        private const val VALUE_ENDS = ";{}:!*=[]>~|@()"

        // The digits of a colour Qt reads after a `#`
        private val HEX_LENGTHS = setOf(3, 6, 8, 9, 12)

        // A number, each of whose digits only one part can take, as a long run of them is otherwise tried split every way
        private val LENGTH = Regex("(-?(?:[0-9]++(?:\\.[0-9]++)?|\\.[0-9]++))\\s*([a-z]*)")
        private val NUMBER = Regex("[+-]?(?:[0-9]++(?:\\.[0-9]++)?|\\.[0-9]++)")
        private val URL = Regex("url\\(([^)]*)\\)", RegexOption.IGNORE_CASE)
    }
}

/** The colours Qt's style sheets and rich text take, as 0xAARRGGBB. */
object CssColor {
    /** Qt's invalid colour, which it draws as black. */
    const val INVALID = 0xFF000000.toInt()

    /**
     * [value] as Qt's style sheets read a colour (`parseColorValue()`): a name or a string, `#rgb`, `#rrggbb`,
     * `#aarrggbb` (Qt's order), or `rgb()`, `rgba()`, `hsl()`, `hsla()`, `hsv()` or `hsva()` of numbers and
     * percentages, the alpha of 1 or less a fraction. A name Qt does not know, or a number out of range, makes Qt's
     * [INVALID] colour. Null where Qt reads no colour at all: a word of its own such as `none` or `solid`, a function
     * it does not know or with too few or too many numbers, and `palette()`, the desktop's colours, which the app
     * does not have.
     */
    fun parse(value: String): Int? {
        val v = value.trim()
        if (v.length >= 2 && (v[0] == '"' || v[0] == '\'') && v.last() == v[0]) return named(v.substring(1, v.length - 1))
        if (v.startsWith("#")) return hex(v.substring(1))
        val open = v.indexOf('(')
        if (open >= 0) return if (v.endsWith(")")) function(v.substring(0, open), v.substring(open + 1, v.length - 1)) else null
        if (v.equals("transparent", ignoreCase = true)) return 0
        // Qt reads no colour off a number or a length, nor off a word of its own
        if (v.lowercase() in KEYWORDS || LEADING_NUMBER.containsMatchIn(v)) return null
        return named(v)
    }

    /** [value] as Qt's `QColor::fromString()` reads it, as `<font color>` does: a name or `#` and digits, else [INVALID]. */
    fun named(value: String): Int = if (value.startsWith("#")) hex(value.substring(1)) ?: INVALID else NAMES[value.lowercase().replace(" ", "")] ?: INVALID

    private fun hex(digits: String): Int? {
        if (digits.any { Character.digit(it, 16) < 0 }) return null
        val n = digits.toLongOrNull(16) ?: return null

        // Each channel's digits, widened to 16 bits as Qt keeps them and then taken to 8
        fun channel(index: Int, width: Int): Long {
            val bits = 4 * width
            val c = n shr (bits * (2 - index)) and ((1L shl bits) - 1)
            val wide = when (width) {
                1 -> c * 0x1111
                3 -> (c shl 4) or (c shr 8)
                else -> c
            }
            return eight(wide.toInt()).toLong()
        }
        return when (digits.length) {
            3, 9, 12 -> {
                val width = digits.length / 3
                (0xFF000000 or (channel(0, width) shl 16) or (channel(1, width) shl 8) or channel(2, width)).toInt()
            }

            6 -> (0xFF000000 or n).toInt()

            8 -> n.toInt()

            else -> null
        }
    }

    private fun function(name: String, arguments: String): Int? {
        // Qt's names are case-sensitive here, and an `a` is the alpha's
        val kind = name.take(3)
        if (kind !in setOf("rgb", "hsl", "hsv") || name.length > 4 || (name.length == 4 && name[3] != 'a')) return null
        val alpha = name.length == 4
        // Qt's terms and the operators between them: it checks the terms at even places, and only how many there are
        val tokens = ARGUMENT.findAll(arguments).map { it.value }.toList()
        if (tokens.size != (if (alpha) 7 else 5)) return null
        val numbers = (tokens.indices step 2).map { i ->
            val token = tokens[i]
            val percent = token.endsWith("%")
            val number = NUMBER.matchEntire(token.removeSuffix("%"))?.value?.toDouble() ?: return null
            if (percent) number * ((if (kind == "rgb" || i != 0) 255.0 else 359.0) / 100.0) else number
        }
        val (a, b, c) = numbers.take(3).map(::rounded)
        val a8 = numbers.getOrNull(3)?.let { if (rounded(it) <= 1) (it * 255.0).toInt() else rounded(it) } ?: 255
        if (a8 !in 0..255) return INVALID
        val rgb = when (kind) {
            "rgb" -> if (a in 0..255 && b in 0..255 && c in 0..255) (a shl 16) or (b shl 8) or c else return INVALID
            else -> if (a in -1..359 && b in 0..255 && c in 0..255) hueToRgb(kind == "hsl", a, b, c) else return INVALID
        }
        return (a8 shl 24) or rgb
    }

    // Qt's conversion of a variant's real number to an integer
    private fun rounded(x: Double): Int = floor(x + 0.5).toInt()

    // A 16-bit channel taken to 8, as Qt's `qt_div_257()`
    private fun eight(x: Int): Int = (x + 128 - ((x + 128) shr 8)) shr 8

    // Qt's `QColor::toRgb()` from hue, saturation and value or lightness, which it keeps in 16 bits
    private fun hueToRgb(lightness: Boolean, hue: Int, saturation: Int, third: Int): Int {
        val h16 = if (hue == -1) 0xFFFF else (hue % 360) * 100
        val s16 = saturation * 0x101
        val t16 = third * 0x101
        val channels = IntArray(3)
        if (s16 == 0 || h16 == 0xFFFF) {
            channels.fill(t16)
        } else if (lightness) {
            if (t16 != 0) {
                val h = if (h16 == 36000) 0f else h16 / 36000f
                val s = s16 / 65535f
                val l = t16 / 65535f
                val temp2 = if (l < 0.5f) l * (1f + s) else l + s - l * s
                val temp1 = 2f * l - temp2
                val temp3 = floatArrayOf(h + 1f / 3f, h, h - 1f / 3f)
                for (i in 0..2) {
                    if (temp3[i] < 0f) {
                        temp3[i] += 1f
                    } else if (temp3[i] > 1f) {
                        temp3[i] -= 1f
                    }
                    val six = temp3[i] * 6f
                    val x = when {
                        six < 1f -> temp1 + (temp2 - temp1) * six
                        temp3[i] * 2f < 1f -> temp2
                        temp3[i] * 3f < 2f -> temp1 + (temp2 - temp1) * (2f / 3f - temp3[i]) * 6f
                        else -> temp1
                    }
                    channels[i] = round16(x)
                }
            }
        } else {
            val h = if (h16 == 36000) 0f else h16 / 6000f
            val s = s16 / 65535f
            val v = t16 / 65535f
            val i = h.toInt()
            val f = h - i
            val p = v * (1f - s)
            val (r, g, b) = if (i and 1 == 1) {
                val q = v * (1f - s * f)
                when (i) {
                    1 -> Triple(q, v, p)
                    3 -> Triple(p, q, v)
                    else -> Triple(v, p, q)
                }
            } else {
                val t = v * (1f - s * (1f - f))
                when (i) {
                    0 -> Triple(v, t, p)
                    2 -> Triple(p, v, t)
                    else -> Triple(t, p, v)
                }
            }
            channels[0] = round16(r)
            channels[1] = round16(g)
            channels[2] = round16(b)
        }
        return (eight(channels[0]) shl 16) or (eight(channels[1]) shl 8) or eight(channels[2])
    }

    private fun round16(x: Float): Int = floor(x * 65535f + 0.5f).toInt()

    // Qt's own words for values, which are no colour's names
    private val KEYWORDS = (
        "active alternate-base always auto base bold bottom bright-text button button-text center circle dark dashed " +
            "decimal disabled disc dot-dash dot-dot-dash dotted double groove highlight highlighted-text inset italic " +
            "large left light line-through link link-visited lower-alpha lower-roman lowercase medium mid middle " +
            "midlight native none normal nowrap oblique off on outset overline pre pre-line pre-wrap ridge right " +
            "selected shadow small small-caps solid square sub super text top transparent underline upper-alpha " +
            "upper-roman uppercase wave window window-text x-large xx-large"
        ).split(' ').toSet()

    /** Whether [value] is a word of Qt's own, which its `background` shorthand passes over. */
    fun isKeyword(value: String): Boolean = value.lowercase() in KEYWORDS

    private val ARGUMENT = Regex("[,/]|[^,/ \\t\\n\\r\u000c]+")
    private val LEADING_NUMBER = Regex("^[+-]?\\.?[0-9]")
    private val NUMBER = Regex("[+-]?(?:[0-9]+|[0-9]*\\.[0-9]+)")

    private val NAMES: Map<String, Int> by lazy {
        val names = HashMap<String, Int>()
        names["transparent"] = 0
        for (entry in SVG_NAMES.split(' ')) {
            val (name, rgb) = entry.split(':')
            names[name] = (0xFF000000 or rgb.toLong(16)).toInt()
        }
        names
    }

    // SVG 1.1's colour keywords, which Qt's QColor takes by name
    private const val SVG_NAMES =
        "aliceblue:f0f8ff antiquewhite:faebd7 aqua:00ffff aquamarine:7fffd4 azure:f0ffff beige:f5f5dc bisque:ffe4c4 " +
            "black:000000 blanchedalmond:ffebcd blue:0000ff blueviolet:8a2be2 brown:a52a2a burlywood:deb887 cadetblue:5f9ea0 " +
            "chartreuse:7fff00 chocolate:d2691e coral:ff7f50 cornflowerblue:6495ed cornsilk:fff8dc crimson:dc143c cyan:00ffff " +
            "darkblue:00008b darkcyan:008b8b darkgoldenrod:b8860b darkgray:a9a9a9 darkgreen:006400 darkgrey:a9a9a9 " +
            "darkkhaki:bdb76b darkmagenta:8b008b darkolivegreen:556b2f darkorange:ff8c00 darkorchid:9932cc darkred:8b0000 " +
            "darksalmon:e9967a darkseagreen:8fbc8f darkslateblue:483d8b darkslategray:2f4f4f darkslategrey:2f4f4f " +
            "darkturquoise:00ced1 darkviolet:9400d3 deeppink:ff1493 deepskyblue:00bfff dimgray:696969 dimgrey:696969 " +
            "dodgerblue:1e90ff firebrick:b22222 floralwhite:fffaf0 forestgreen:228b22 fuchsia:ff00ff gainsboro:dcdcdc " +
            "ghostwhite:f8f8ff gold:ffd700 goldenrod:daa520 gray:808080 grey:808080 green:008000 greenyellow:adff2f " +
            "honeydew:f0fff0 hotpink:ff69b4 indianred:cd5c5c indigo:4b0082 ivory:fffff0 khaki:f0e68c lavender:e6e6fa " +
            "lavenderblush:fff0f5 lawngreen:7cfc00 lemonchiffon:fffacd lightblue:add8e6 lightcoral:f08080 lightcyan:e0ffff " +
            "lightgoldenrodyellow:fafad2 lightgray:d3d3d3 lightgreen:90ee90 lightgrey:d3d3d3 lightpink:ffb6c1 " +
            "lightsalmon:ffa07a lightseagreen:20b2aa lightskyblue:87cefa lightslategray:778899 lightslategrey:778899 " +
            "lightsteelblue:b0c4de lightyellow:ffffe0 lime:00ff00 limegreen:32cd32 linen:faf0e6 magenta:ff00ff maroon:800000 " +
            "mediumaquamarine:66cdaa mediumblue:0000cd mediumorchid:ba55d3 mediumpurple:9370db mediumseagreen:3cb371 " +
            "mediumslateblue:7b68ee mediumspringgreen:00fa9a mediumturquoise:48d1cc mediumvioletred:c71585 " +
            "midnightblue:191970 mintcream:f5fffa mistyrose:ffe4e1 moccasin:ffe4b5 navajowhite:ffdead navy:000080 " +
            "oldlace:fdf5e6 olive:808000 olivedrab:6b8e23 orange:ffa500 orangered:ff4500 orchid:da70d6 palegoldenrod:eee8aa " +
            "palegreen:98fb98 paleturquoise:afeeee palevioletred:db7093 papayawhip:ffefd5 peachpuff:ffdab9 peru:cd853f " +
            "pink:ffc0cb plum:dda0dd powderblue:b0e0e6 purple:800080 red:ff0000 rosybrown:bc8f8f royalblue:4169e1 " +
            "saddlebrown:8b4513 salmon:fa8072 sandybrown:f4a460 seagreen:2e8b57 seashell:fff5ee sienna:a0522d silver:c0c0c0 " +
            "skyblue:87ceeb slateblue:6a5acd slategray:708090 slategrey:708090 snow:fffafa springgreen:00ff7f " +
            "steelblue:4682b4 tan:d2b48c teal:008080 thistle:d8bfd8 tomato:ff6347 turquoise:40e0d0 violet:ee82ee " +
            "wheat:f5deb3 white:ffffff whitesmoke:f5f5f5 yellow:ffff00 yellowgreen:9acd32"
}
