package org.mudlet.android.protocol

/** Where the game screen shows the app's map to be read and used: not at all, in front of the console, or beside it. */
enum class MapPlace { NONE, IN_FRONT, BESIDE }

/**
 * What the game screen keeps of where the app's map is, which outlasts a rotation, a fold and a window resized: [inFront],
 * the title bar's map button pressed where the map cannot be beside the text, and [besideHidden], the map beside the
 * text put away with it. Where the screen is wide enough for both, as desktop Mudlet's mapper is docked at the main
 * window's side, the map is beside the text unless put away; a map in front that the screen widens under goes beside
 * it, and a map beside the text that the screen narrows under goes, leaving the text, as on a phone.
 */
data class MapPlacement(val inFront: Boolean = false, val besideHidden: Boolean = false) {
    /** Where the map is on a game screen it [fits] beside the text on or not ([fitsBeside]). */
    fun place(fits: Boolean): MapPlace = when {
        fits -> if (inFront || !besideHidden) MapPlace.BESIDE else MapPlace.NONE
        inFront -> MapPlace.IN_FRONT
        else -> MapPlace.NONE
    }

    /** What the map button, or desktop's Alt+M, makes of this on a game screen it [fits] beside the text on or not. */
    fun toggled(fits: Boolean): MapPlacement = when (place(fits)) {
        MapPlace.BESIDE -> MapPlacement(inFront = false, besideHidden = true)
        MapPlace.IN_FRONT -> copy(inFront = false)
        MapPlace.NONE -> if (fits) MapPlacement() else copy(inFront = true)
    }

    companion object {
        /** Material's expanded window size class starts here, as the app's list and detail panes do. */
        const val EXPANDED_WIDTH_DP = 840f

        /** The narrowest the console is left beside the map, and the narrowest the map is given. */
        const val CONSOLE_LEAST_DP = 480f
        const val MAP_LEAST_DP = 360f

        // The share of the screen the map is given where that leaves both their least
        private const val MAP_SHARE = 0.4f

        init {
            check(CONSOLE_LEAST_DP + MAP_LEAST_DP <= EXPANDED_WIDTH_DP) { "an expanded screen has room for the console and the map beside it" }
        }

        /**
         * Whether the map may stand beside the text on a game screen [widthDp] wide, with the setting [beside] ("Map
         * beside the text") and, with [scriptMapper], a script's mapper window on the screen, which is the map then, as
         * desktop's one mapper is.
         */
        fun fitsBeside(widthDp: Float, beside: Boolean, scriptMapper: Boolean): Boolean = beside && !scriptMapper && widthDp >= EXPANDED_WIDTH_DP

        /** How wide the map beside the text is on a game screen [widthDp] wide, which it [fitsBeside] on. */
        fun besideWidthDp(widthDp: Float): Float = (widthDp * MAP_SHARE).coerceAtLeast(MAP_LEAST_DP).coerceAtMost(widthDp - CONSOLE_LEAST_DP)

        /** Whether a script's mapper window is among [windows] and on the screen. */
        fun scriptMapperShown(windows: List<ScriptWindow>): Boolean = windows.any { it.kind == WindowKind.MAPPER && it.shown }
    }
}
