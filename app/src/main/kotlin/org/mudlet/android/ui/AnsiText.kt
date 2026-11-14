package org.mudlet.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.mudlet.android.protocol.RUN_BOLD
import org.mudlet.android.protocol.RUN_CONCEALED
import org.mudlet.android.protocol.RUN_ITALIC
import org.mudlet.android.protocol.RUN_REVERSE
import org.mudlet.android.protocol.RUN_RGB
import org.mudlet.android.protocol.RUN_STRIDE
import org.mudlet.android.protocol.RUN_STRIKE
import org.mudlet.android.protocol.RUN_UNDERLINE

/** The colours game text is drawn in until the engine says otherwise: on [CONSOLE_BACKGROUND], in [CONSOLE_FOREGROUND]. */
val CONSOLE_BACKGROUND = Color.Black
val CONSOLE_FOREGROUND = Color(0xFFC0C0C0)

/** The console's colours where a run sets none (its -1): status `consoleColors`, or the defaults above. */
data class ConsoleColors(val foreground: Color = CONSOLE_FOREGROUND, val background: Color = CONSOLE_BACKGROUND) {
    companion object {
        private val format = Regex("#([0-9a-fA-F]{6}) #([0-9a-fA-F]{6})")

        /** Status `consoleColors`, `<fg> <bg>` each `#rrggbb`; the defaults when it is missing or malformed. */
        fun of(status: String?): ConsoleColors {
            val (fg, bg) = status?.let { format.matchEntire(it) }?.destructured ?: return ConsoleColors()
            return ConsoleColors(Color(0xFF000000 or fg.toLong(16)), Color(0xFF000000 or bg.toLong(16)))
        }
    }
}

// ANSI black is lifted to dark grey so it stays readable on the black console
private val ansi = listOf(
    0xFF5C5C5C, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00, 0xFF2060EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
    0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00, 0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
).map { Color(it) }
private val markerColour = Color(0xFFFFB000)

private fun runColour(code: Int): Color? = when {
    code in 0..15 -> ansi[code]
    code >= RUN_RGB -> Color(0xFF000000.toInt() or (code and 0xFFFFFF))
    else -> null
}

/**
 * A line with its runs' colours and styles (docs/protocol.md, "Runs"), on [colors] where they set none; the app's own
 * marker lines in amber.
 */
fun ansiText(line: UiLine, colors: ConsoleColors = ConsoleColors()): AnnotatedString = buildAnnotatedString {
    if (line.marker) {
        pushStyle(SpanStyle(color = markerColour))
        append(line.text)
        pop()
        return@buildAnnotatedString
    }
    append(line.text)
    var i = 0
    while (i + RUN_STRIDE - 1 < line.runs.size) {
        val start = line.runs[i]
        val end = (start + line.runs[i + 1]).coerceAtMost(line.text.length)
        val attributes = line.runs[i + 4]
        var fg = runColour(line.runs[i + 2]) ?: colors.foreground
        var bg = runColour(line.runs[i + 3])
        if (bg == null && fg == Color.Black) fg = ansi[0]
        if (attributes and RUN_REVERSE != 0) {
            val swapped = bg ?: colors.background
            bg = fg
            fg = swapped
        }
        if (attributes and RUN_CONCEALED != 0) fg = bg ?: Color.Transparent
        val decorations = listOfNotNull(
            TextDecoration.Underline.takeIf { attributes and RUN_UNDERLINE != 0 },
            TextDecoration.LineThrough.takeIf { attributes and RUN_STRIKE != 0 },
        )
        addStyle(
            SpanStyle(
                color = fg,
                background = bg ?: Color.Unspecified,
                fontWeight = if (attributes and RUN_BOLD != 0) FontWeight.Bold else null,
                fontStyle = if (attributes and RUN_ITALIC != 0) FontStyle.Italic else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            ),
            start,
            end,
        )
        i += RUN_STRIDE
    }
}
