package org.mudlet.android.protocol

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
    /** Null for the text's colour, as Qt's. */
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
) {
    /** The width of the border drawn: none without a style. */
    val drawnBorderWidth: Float get() = if (borderStyled) borderWidth else 0f

    companion object {
        /**
         * The label's own [sheet]: its bare declarations and those of a `QLabel` (or `TLabel`, or `*`) rule, in
         * order. A rule with any other selector, a state or a sub-control is skipped, as is a declaration the app does
         * not draw. With [selectors] [TEXT_BOX_SELECTORS], a text box's sheet, the same way.
         */
        fun parse(sheet: String, selectors: Set<String> = LABEL_SELECTORS): LabelStyle = of(declarations(sheet, selectors))

        /** [declarations], each over those before it, over [start]. */
        fun of(declarations: List<Pair<String, String>>, start: LabelStyle = LabelStyle()): LabelStyle = declarations.fold(start) { style, (property, value) -> style.with(property, value) }

        /**
         * The declarations of [sheet] that apply to a label, or [selectors]' widget, each its lower-case property and
         * its value, in order. With [states], the pseudo-states the widget is or is not in by name, a rule with
         * pseudo-states applies where it is in each one named (`:checked`) and not in each one negated (`:!checked`),
         * and the declarations come in the order Qt ranks them: a rule with more pseudo-states and classes
         * (`.QPushButton`) after one with fewer, then one naming a type after one for `*`, and in order where they
         * tie, so each overrides those before it. A rule naming a pseudo-state not in [states] is skipped, as is any
         * with one without [states].
         */
        fun declarations(sheet: String, selectors: Set<String> = LABEL_SELECTORS, states: Map<String, Boolean>? = null): List<Pair<String, String>> {
            val text = COMMENT.replace(sheet, " ")
            // Each block's declarations with the specificity of its selector; bare ones are Qt's `* { }`
            val blocks = ArrayList<Pair<Int, List<Pair<String, String>>>>()
            var i = 0
            var start = 0
            while (i < text.length) {
                when (text[i]) {
                    '{' -> {
                        // What is before the brace since the last declaration is the selector
                        val head = text.substring(start, i)
                        val selector = head.substringAfterLast(';')
                        blocks.add(0 to parseDeclarations(head.substring(0, head.length - selector.length)))
                        val end = text.indexOf('}', i + 1).let { if (it < 0) text.length else it }
                        selector.split(',').mapNotNull { specificity(it.trim(), selectors, states) }.maxOrNull()?.let { blocks.add(it to parseDeclarations(text.substring(i + 1, end))) }
                        i = end + 1
                        start = i
                        continue
                    }

                    '(' -> i = text.indexOf(')', i + 1).let { if (it < 0) text.length - 1 else it }

                    '"', '\'' -> i = text.indexOf(text[i], i + 1).let { if (it < 0) text.length - 1 else it }
                }
                i++
            }
            blocks.add(0 to parseDeclarations(text.substring(start.coerceAtMost(text.length))))
            return (if (states == null) blocks else blocks.sortedBy { it.first }).flatMap { it.second }
        }

        /**
         * How [selector] ranks among the rules that apply to a widget of [selectors] in [states], as Qt counts it: ten
         * for each pseudo-state or class (`.QPushButton`), one for a type; null where it does not apply to it.
         */
        private fun specificity(selector: String, selectors: Set<String>, states: Map<String, Boolean>?): Int? {
            if ("::" in selector) return null
            val parts = selector.split(':').map { it.trim() }
            val pseudo = parts.drop(1)
            // `:checked` alone is `*:checked`
            val type = parts[0].ifEmpty { if (pseudo.isEmpty()) return null else "*" }
            if (type !in selectors) return null
            for (state in pseudo) {
                val negated = state.startsWith('!')
                val inState = states?.get(state.removePrefix("!").lowercase()) ?: return null
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
        fun length(value: String): Float? {
            val match = LENGTH.matchEntire(value.trim().lowercase()) ?: return null
            val n = match.groupValues[1].toFloatOrNull() ?: return null
            return when (match.groupValues[2]) {
                "", "px" -> bounded(n)
                "pt" -> bounded(n * PX_PER_PT)
                else -> null
            }
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
            val parts = splitTopLevel(value, ' ').map { length(it) ?: return null }
            return when (parts.size) {
                1 -> Edges(parts[0], parts[0], parts[0], parts[0])
                2 -> Edges(parts[0], parts[1], parts[0], parts[1])
                3 -> Edges(parts[0], parts[1], parts[2], parts[1])
                4 -> Edges(parts[0], parts[1], parts[2], parts[3])
                else -> null
            }
        }

        private fun url(value: String): String? = URL.find(value)?.groupValues?.get(1)?.trim()?.trim('"', '\'')

        private fun LabelStyle.with(property: String, value: String): LabelStyle = when (property) {
            "background", "background-color" -> {
                // The shorthand sets what it does not name back to none, as Qt's does, and its `none` is the image's
                var s = if (property == "background") copy(background = null, backgroundImage = null) else this
                for (part in splitTopLevel(value, ' ')) {
                    val fill = fill(part)
                    when {
                        fill != null -> s = s.copy(background = fill)
                        property == "background" && part.startsWith("url(", ignoreCase = true) -> s = s.copy(backgroundImage = url(part))
                        property == "background-color" && part.equals("none", ignoreCase = true) -> s = s.copy(background = null)
                    }
                }
                s
            }

            "color" -> CssColor.parse(value)?.let { copy(color = it) } ?: this

            "border" -> {
                // The shorthand sets what it does not name back to none, as Qt's does
                var s = copy(borderWidth = 0f, borderStyled = false, borderColor = null)
                for (part in splitTopLevel(value, ' ')) {
                    val width = length(part)
                    val color = CssColor.parse(part)
                    when {
                        width != null -> s = s.copy(borderWidth = width)
                        part.lowercase() in BORDER_STYLES -> s = s.copy(borderStyled = part.lowercase() != "none")
                        color != null -> s = s.copy(borderColor = color)
                    }
                }
                s
            }

            "border-width" -> length(splitTopLevel(value, ' ').first())?.let { copy(borderWidth = it) } ?: this

            "border-style" -> value.lowercase().takeIf { it in BORDER_STYLES }?.let { copy(borderStyled = it != "none") } ?: this

            "border-color" -> CssColor.parse(splitTopLevel(value, ' ').first())?.let { copy(borderColor = it) } ?: this

            // Qt draws a negative radius as none, and a shape's corner may not be negative
            "border-radius" -> length(splitTopLevel(value, ' ').first())?.let { copy(borderRadius = it.coerceAtLeast(0f)) } ?: this

            "padding" -> edges(value)?.let { copy(padding = it) } ?: this

            "margin" -> edges(value)?.let { copy(margin = it) } ?: this

            // Qt reads a size in px as a whole number, and keeps the size it had for one of 0 or less, which would
            // hide the text
            "font-size" -> length(value)?.let { if (value.trim().endsWith("pt", ignoreCase = true)) it else it.roundToInt().toFloat() }
                ?.takeIf { it > 0f }?.let { copy(fontSizePx = it) } ?: this

            "font-weight" -> fontWeight(value)?.let { copy(fontWeight = it) } ?: this

            "font-style" -> copy(italic = value.trim().lowercase() in setOf("italic", "oblique"))

            "font-family" -> copy(fontFamily = value.split(',').first().trim().trim('"', '\''))

            "qproperty-alignment" -> alignment(value).let { (h, v) -> copy(hAlign = h ?: hAlign, vAlign = v ?: vAlign) }

            "border-image" -> copy(borderImage = url(value))

            // Qt takes only a `url()` here, so `none` leaves the image a rule before it set
            "background-image" -> url(value)?.let { copy(backgroundImage = it) } ?: this

            else -> this
        }

        private fun fill(value: String): LabelFill? {
            if (value.startsWith("qlineargradient", ignoreCase = true)) return linearGradient(value)
            if (value.startsWith("qradialgradient", ignoreCase = true) || value.startsWith("qconicalgradient", ignoreCase = true)) {
                // Drawn as the colour of its first stop
                return gradientStops(value).firstOrNull()?.let { LabelFill.Solid(it.second) }
            }
            return CssColor.parse(value)?.let { LabelFill.Solid(it) }
        }

        private fun gradientArguments(value: String): List<Pair<String, String>> {
            val inner = value.substringAfter('(', "").substringBeforeLast(')', "")
            return splitTopLevel(inner, ',').mapNotNull { argument ->
                val colon = argument.indexOf(':')
                if (colon <= 0) null else argument.substring(0, colon).trim().lowercase() to argument.substring(colon + 1).trim()
            }
        }

        private fun gradientStops(value: String): List<Pair<Float, Int>> = gradientArguments(value).filter { it.first == "stop" }.mapNotNull { (_, stop) ->
            val parts = splitTopLevel(stop, ' ')
            val position = bounded(parts.firstOrNull()?.toFloatOrNull()) ?: return@mapNotNull null
            val color = CssColor.parse(parts.drop(1).joinToString(" ")) ?: return@mapNotNull null
            position.coerceIn(0f, 1f) to color
        }

        private fun linearGradient(value: String): LabelFill? {
            val args = gradientArguments(value).toMap()
            val stops = gradientStops(value)
            if (stops.isEmpty()) return null
            // Qt fills with the one colour, and Android's gradient needs two
            if (stops.size == 1) return LabelFill.Solid(stops[0].second)
            fun coordinate(name: String) = bounded(args[name]?.toFloatOrNull()) ?: 0f
            return LabelFill.Gradient(coordinate("x1"), coordinate("y1"), coordinate("x2"), coordinate("y2"), stops)
        }

        /** [text] split at each [separator] outside parentheses and quotes, the parts trimmed and the empty ones dropped. */
        fun splitTopLevel(text: String, separator: Char): List<String> {
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

                    depth == 0 && (c == separator || (separator == ' ' && c.isWhitespace())) -> {
                        parts.add(text.substring(start, i))
                        start = i + 1
                    }
                }
            }
            parts.add(text.substring(start))
            return parts.map { it.trim() }.filter { it.isNotEmpty() }
        }

        /** Mudlet's pixels per point, at the 96 dots per inch Qt takes. */
        const val PX_PER_PT = 96f / 72f

        /** No length of a sheet's is longer than this, in Mudlet's pixels: past the side of any screen the app has. */
        const val MAX_LENGTH = 4096f

        private val LABEL_SELECTORS = setOf("QLabel", "TLabel", "*")

        /** The rules of a text box's sheet that apply to it: Mudlet's `TTextBox` is a `QPlainTextEdit`. */
        val TEXT_BOX_SELECTORS = setOf("QPlainTextEdit", "TTextBox", "*")
        private val BORDER_STYLES = setOf("none", "solid", "dashed", "dotted", "double", "groove", "ridge", "inset", "outset", "dot-dash", "dot-dot-dash")
        private val COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val LENGTH = Regex("(-?[0-9]*\\.?[0-9]+)\\s*([a-z]*)")
        private val URL = Regex("url\\(([^)]*)\\)", RegexOption.IGNORE_CASE)
    }
}

/** The colours Qt's style sheets and rich text take, as 0xAARRGGBB. */
object CssColor {
    /**
     * [value] as a colour: a name of SVG's (and `transparent`), `#rgb`, `#rrggbb`, `#aarrggbb` (Qt's order),
     * `rgb()` or `rgba()` with numbers or percentages, the alpha from 0 to 255, or from 0 to 1 when written with a
     * point; null for anything else.
     */
    fun parse(value: String): Int? {
        val v = value.trim().lowercase()
        if (v.startsWith("#")) return hex(v.substring(1))
        if (v.startsWith("rgb")) return rgb(v)
        return NAMES[v.replace(" ", "")]
    }

    private fun hex(digits: String): Int? {
        if (digits.any { Character.digit(it, 16) < 0 }) return null
        val n = digits.toLongOrNull(16) ?: return null
        return when (digits.length) {
            3 -> {
                val r = (n shr 8 and 0xF) * 17
                val g = (n shr 4 and 0xF) * 17
                val b = (n and 0xF) * 17
                (0xFF000000 or (r shl 16) or (g shl 8) or b).toInt()
            }

            6 -> (0xFF000000 or n).toInt()

            8 -> n.toInt()

            else -> null
        }
    }

    private fun rgb(v: String): Int? {
        val match = Regex("rgba?\\(([^)]*)\\)").matchEntire(v) ?: return null
        val parts = match.groupValues[1].split(',').map { it.trim() }
        if (parts.size !in 3..4) return null
        val channels = parts.take(3).map { channel(it, 255f) ?: return null }
        val alpha = parts.getOrNull(3)?.let { a ->
            if (!a.endsWith("%") && a.contains('.')) a.toFloatOrNull()?.let { (it * 255).roundToInt() } else channel(a, 255f)
        } ?: if (parts.size == 4) return null else 255
        val (r, g, b) = channels
        return (alpha.coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun channel(text: String, full: Float): Int? {
        val n = if (text.endsWith("%")) text.removeSuffix("%").trim().toFloatOrNull()?.let { it / 100 * full } else text.toFloatOrNull()
        return n?.roundToInt()?.coerceIn(0, 255)
    }

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
