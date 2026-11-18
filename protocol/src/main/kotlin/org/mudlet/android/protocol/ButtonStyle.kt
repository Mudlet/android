package org.mudlet.android.protocol

import kotlin.math.roundToInt

/**
 * What a bar's button is in, of what its style sheet's pseudo-states name: [checked] while a push-down button is down,
 * [pressed] while it is touched or its menu is open, and [enabled] while there is an engine to press it.
 */
data class ButtonState(val checked: Boolean = false, val pressed: Boolean = false, val enabled: Boolean = true)

/** The room around a button's content, in the sheet's pixels: its [margin], its [border]'s width and its [padding]. */
data class ButtonRoom(val margin: Edges, val border: Float, val padding: Edges)

/**
 * What the app draws of a bar's button (docs/phase-f.md, "Button bars and commands"): [style], the label style sheet's
 * subset, from the button's own sheet over its bar's, and the [padding] and [margin] those set; null where they set
 * none, which keeps the app's own spacing. [nativeBorder] is whether the sheets leave the button its native frame, as
 * [LabelStyle.nativeFrame] has it.
 */
data class ButtonStyle(val style: LabelStyle, val padding: Edges?, val margin: Edges?, val nativeBorder: Boolean) {
    /**
     * Whether the app draws its own button's colour under it: where the desktop draws its native button, with no
     * background colour of the sheets'; a `background-image` alone is drawn over it, as over the desktop's. A sheet
     * with a border of its own fills only with its background, so what is around the button shows through the rest.
     */
    val appFill: Boolean get() = nativeBorder && style.background == null

    /**
     * The room the button's margin, border and padding take where there is [width] by [height] of the sheet's pixels
     * for them, with [padding] and [margin] where the sheets set none. Where they ask for more than there is, those
     * across, or those down, all shrink by the same share to what there is: a sheet's lengths are made for a
     * desktop's window, and a button whose content they push off the screen could be neither read nor touched.
     */
    fun room(width: Float, height: Float, padding: Edges, margin: Edges): ButtonRoom {
        val inside = this.padding ?: padding
        val outside = this.margin ?: margin
        val border = style.drawnBorderWidth
        val across = share(width, outside.left, outside.right, border, border, inside.left, inside.right)
        val down = share(height, outside.top, outside.bottom, border, border, inside.top, inside.bottom)
        return ButtonRoom(outside.shrunk(across, down), border * minOf(across, down), inside.shrunk(across, down))
    }

    /**
     * The colour of the button's text, 0xAARRGGBB, on the bar whose sheet [Companion.bar] read as [bar]. [appColour]
     * is the opaque colour the app draws under the sheets': its button's where [appFill], or else its bars', and
     * [appText] the app's text on it.
     *
     * Where the sheets drew what the text is on, a colour or an image, the text is in the sheets' `color`, as on the
     * desktop, or, on a colour of theirs without one, in black or white, whichever reads on it. Where what it is on is
     * the app's, which changes with the system's light or dark theme and which the sheets never saw, their `color` is
     * drawn only where it reads on it, and the app's [appText] where it does not. Where a mostly see-through colour
     * of the sheets' tints the app's, the text is on neither's colour: [appText] too is drawn only where it reads, and
     * black or white where it does not.
     */
    fun text(bar: LabelStyle, appColour: Int, appText: Int): Int {
        val barFill = bar.background.takeUnless { appFill }
        val under = barFill?.let { over(it, appColour) } ?: appColour
        val backdrop = style.background?.let { over(it, under) } ?: under
        val sheetsColour = opaque(style.background) || opaque(barFill)
        val sheetsImage = style.backgroundImage != null || style.borderImage != null || (!appFill && (bar.backgroundImage != null || bar.borderImage != null))
        val tinted = style.background != null || barFill != null
        val colour = style.color
        fun reads(argb: Int) = MapBehindText.contrast(blend(backdrop, argb), backdrop) >= MapBehindText.MIN_CONTRAST
        return when {
            sheetsColour -> colour ?: blackOrWhite(backdrop)
            sheetsImage -> colour ?: appText
            else -> colour?.takeIf(::reads) ?: appText.takeIf { !tinted || reads(it) } ?: blackOrWhite(backdrop)
        }
    }

    companion object {
        /**
         * The style of a button with the sheet [buttonSheet] on a bar with [barSheet], in [state], as Mudlet's
         * `TFlipButton` has it: the bar's sheet is on the bar's widgets, around the button, so its rules for a
         * `QPushButton` and its bare declarations, which Qt reads as `* { }`, reach the button; the button's own sheet
         * wins over its bar's whatever the rules' selectors, as Qt ranks a widget's own sheet over one it inherits.
         */
        fun of(barSheet: String, buttonSheet: String, state: ButtonState): ButtonStyle {
            val states = states(state)
            val declarations = LabelStyle.declarations(barSheet, BUTTON_SELECTORS, states) + LabelStyle.declarations(buttonSheet, BUTTON_SELECTORS, states)
            // Told apart from a sheet that sets them to 0 by being the same object afterwards
            val unset = Edges(0f, 0f, 0f, 0f)
            val read = LabelStyle.of(declarations, LabelStyle(padding = unset, margin = unset))
            // Left as `unset` where the sheets set none, which is Edges.ZERO's value
            return ButtonStyle(read, read.padding.takeIf { it !== unset }, read.margin.takeIf { it !== unset }, read.nativeFrame)
        }

        /**
         * What a bar draws of its own [sheet]: the widget Mudlet fills a bar with is a plain `QWidget`, so its bare
         * declarations and its `QWidget` and `*` rules, ranked as a button's are, in the states of one at rest.
         */
        fun bar(sheet: String): LabelStyle = LabelStyle.of(LabelStyle.declarations(sheet, BAR_SELECTORS, states(ButtonState())))

        /**
         * The pseudo-states Qt gives a `TFlipButton` by name, of those a touch screen has: it draws itself with a
         * style option of its own, which is never `:off` or `:unchecked`, `:flat` or `:default`, nor horizontal, so
         * always `:vertical`; it is `:active` while its window is, as the app's is while it is shown; and nothing is
         * under a pointer or focused. A state not named here leaves its rule out.
         */
        private fun states(state: ButtonState): Map<String, Boolean> {
            val open = state.checked || state.pressed
            return mapOf(
                "checked" to state.checked,
                "on" to state.checked,
                "unchecked" to false,
                "off" to false,
                "pressed" to state.pressed,
                "open" to open,
                "closed" to !open,
                "enabled" to state.enabled,
                "disabled" to !state.enabled,
                "hover" to false,
                "focus" to false,
                "flat" to false,
                "default" to false,
                "vertical" to true,
                "horizontal" to false,
                "active" to true,
            )
        }

        // TFlipButton has no Q_OBJECT, so Qt's selectors know it as a QPushButton
        private val BUTTON_SELECTORS = setOf("QPushButton", ".QPushButton", "QAbstractButton", "QWidget", "*")
        private val BAR_SELECTORS = setOf("QWidget", "*")

        /** The share of the lengths [asked] that [room] has for them, counting none below 0: 1 where it has it all. */
        private fun share(room: Float, vararg asked: Float): Float {
            val sum = asked.sumOf { it.coerceAtLeast(0f).toDouble() }
            return if (sum > room) (room.coerceAtLeast(0f) / sum).toFloat() else 1f
        }

        private fun Edges.shrunk(across: Float, down: Float) = Edges(top * down, right * across, bottom * down, left * across)

        // Over this relative luminance black stands out from a colour more than white does
        private const val BLACK_OR_WHITE = 0.179
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        private fun blackOrWhite(on: Int): Int = if (MapBehindText.luminance(on) > BLACK_OR_WHITE) BLACK else WHITE

        // A fill mostly see-through shows what is under it more than its own colour
        private fun opaque(fill: LabelFill?): Boolean = fill != null && colours(fill).map { (it ushr 24) / 255.0 }.average() >= 0.5

        private fun colours(fill: LabelFill): List<Int> = when (fill) {
            is LabelFill.Solid -> listOf(fill.argb)
            is LabelFill.Gradient -> fill.stops.map { it.second }
        }

        /** [fill] drawn on the opaque [under], as one 0xRRGGBB colour: a gradient's colours averaged. */
        private fun over(fill: LabelFill, under: Int): Int {
            val drawn = colours(fill).map { blend(under, it) }
            var rgb = 0
            for (shift in intArrayOf(16, 8, 0)) rgb = rgb or (drawn.map { it shr shift and 0xFF }.average().roundToInt() shl shift)
            return rgb
        }

        /** [argb] drawn at its own alpha on the opaque [under]. */
        private fun blend(under: Int, argb: Int): Int = MapBehindText.blend(under, argb, (argb ushr 24) / 255f)
    }
}
