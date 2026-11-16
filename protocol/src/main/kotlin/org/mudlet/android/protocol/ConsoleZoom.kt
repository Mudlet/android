package org.mudlet.android.protocol

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The text size the player chose for a script console (a mini console, user window or MXP frame) by pinching it, in
 * the points `setFontSize(name, size)` takes, against the size the console's scripts give it. The player's choice
 * wins: a size a script sets later, or a console made again with another, is set back to [chosen], until the player
 * resets it, which gives the console the size its scripts last gave it, [scripts].
 *
 * [inFlight] are the sizes sent since the engine last reported [chosen]: a report of one of those is the answer to an
 * older send, not a script's size.
 */
data class ConsoleZoom(val chosen: Int, val scripts: Int?, val inFlight: Set<Int> = emptySet()) {
    /** The engine reports the console at [size] points: the state after it, and the size to send, if any. */
    fun reported(size: Int): Pair<ConsoleZoom, Int?> = when {
        size <= 0 -> this to null
        size == chosen -> copy(inFlight = emptySet()) to null
        size in inFlight -> this to null
        else -> ConsoleZoom(chosen, size, setOf(chosen)) to chosen
    }

    /** The player picks [size]: the state after it; [size] is sent unless the engine already reports it. */
    fun chosen(size: Int): ConsoleZoom = if (size == chosen) this else copy(chosen = size, inFlight = inFlight + size)

    companion object {
        /** The sizes the player reaches, within the 1 to 1000 that `setFontSize()` takes. */
        const val MIN_SIZE = 4
        const val MAX_SIZE = 40

        /**
         * [size], which the player reached from [start] points, from [MIN_SIZE] to [MAX_SIZE], or as far as [start]
         * when a script gave a size outside them.
         */
        fun clamp(size: Int, start: Int): Int = size.coerceIn(min(MIN_SIZE, start), max(MAX_SIZE, start))

        /** A size the engine reports, from `windows`' `fontSize`: whole points, or 0 for none (a font sized in pixels). */
        fun points(fontSize: Double): Int = if (fontSize > 0) fontSize.roundToInt() else 0

        /**
         * The Lua that gives the console [name] the size [size], as a script would with `setFontSize()`, which raises
         * `sysFontChangeEvent` and then `sysConsoleSizeChanged` for its new grid. A Geyser console with auto wrap on
         * wraps to its new width, as `Geyser.MiniConsole:setFontSize()` has it, without that method's own resize of a
         * window whose constraints are in characters.
         */
        fun setFontSizeLua(name: String, size: Int): String = """
            |do
            |  local name, size = ${LabelLink.luaString(name)}, $size
            |  if setFontSize(name, size) and type(Geyser) == "table" then
            |    local seen = {}
            |    local function walk(container)
            |      if seen[container] then return end
            |      seen[container] = true
            |      for _, w in pairs(container.windowList or {}) do
            |        if type(w) == "table" then
            |          if w.name == name and w.autoWrap and type(w.resetAutoWrap) == "function" then pcall(w.resetAutoWrap, w) end
            |          walk(w)
            |        end
            |      end
            |    end
            |    pcall(walk, Geyser)
            |  end
            |end
        """.trimMargin()
    }
}
