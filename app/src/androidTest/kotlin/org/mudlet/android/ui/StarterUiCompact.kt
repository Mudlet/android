package org.mudlet.android.ui

import androidx.compose.ui.geometry.Rect
import org.json.JSONObject

/**
 * The starter UI in a compact main window (scripts/mudlet-patches/0050), as a fake engine replays it on the emulator's
 * upright phone: its bars along the top and a button for each chat tab along the bottom, in a main window the size
 * of the game screen's area in dp, at a pixelScale of 1, so that it is drawn at the size it would be on the device. The
 * strips and what is on them are labels, a gauge three; Geyser's containers send no window. Each tab is a hidden mini
 * console over the console's text.
 */
class StarterUiCompact private constructor(private val w: Int, private val h: Int) {
    private val textH = h - BAR_H - ROW_H

    fun pane(key: String, open: Boolean): JSONObject = FakeEngine.window("BaseUI_chat_$key", y = BAR_H, w = w, h = textH, columns = w / CHAR_W, rows = textH / CHAR_H).put("visible", open).put("shown", open)

    private fun draw(session: FakeEngine.Session) {
        session.mainArea(w, h, w / CHAR_W, textH / CHAR_H, 0, BAR_H, w, textH, adaptive = true)
        session.windows(windows(), full = true)
        session.label("BaseUI_vitals_baradjLabel", styleSheet = STRIP_STYLE)
        session.label("BaseUI_chat_buttonsadjLabel", styleSheet = STRIP_STYLE)
        for ((key, name) in GAUGES) {
            session.label("BaseUI_${key}_gauge_back", accessibleName = name, styleSheet = "background-color: #303030")
            session.label("BaseUI_${key}_gauge_front", styleSheet = "background-color: #a03030")
            session.label("BaseUI_${key}_gauge_text", text = "50/100")
        }
        paintButton(session, "all", "All", "All chat")
        paintButton(session, "tells", "Tells (2)", "Tells chat, 2 new")
        paintButton(session, "channels", "Channels", "Channels chat")
    }

    private fun windows(): List<JSONObject> {
        val gaugeW = (w - 2 * BAR_PADDING - BAR_GAP) / 2
        val gauges = GAUGES.flatMapIndexed { i, (key, _) ->
            val x = BAR_PADDING + i * (gaugeW + BAR_GAP)
            listOf(
                FakeEngine.window("BaseUI_${key}_gauge_back", kind = "label", x = x, y = BAR_PADDING, w = gaugeW, h = GAUGE_H),
                FakeEngine.window("BaseUI_${key}_gauge_front", kind = "label", x = x, y = BAR_PADDING, w = gaugeW / 2, h = GAUGE_H),
                FakeEngine.window("BaseUI_${key}_gauge_text", kind = "label", x = x, y = BAR_PADDING, w = gaugeW, h = GAUGE_H),
            )
        }
        val buttonW = (w - 2 * ROW_PADDING - (TABS.size - 1) * BUTTON_GAP) / TABS.size
        val buttons = TABS.mapIndexed { i, key ->
            FakeEngine.window(button(key), kind = "label", x = ROW_PADDING + i * (buttonW + BUTTON_GAP), y = h - ROW_H + ROW_PADDING, w = buttonW, h = BUTTON_H)
        }
        return listOf(FakeEngine.window("BaseUI_vitals_baradjLabel", kind = "label", w = w, h = BAR_H)) + gauges +
            FakeEngine.window("BaseUI_chat_buttonsadjLabel", kind = "label", y = h - ROW_H, w = w, h = ROW_H) + buttons +
            TABS.map { pane(it, open = false) }
    }

    companion object {
        val GAUGES = listOf("hp" to "Health", "mp" to "Mana")
        val TABS = listOf("all", "tells", "channels")

        // 0050's compact sizes, in Mudlet's pixels at a pixelScale of 1
        private const val GAUGE_H = 26
        private const val BAR_PADDING = 3
        private const val BAR_GAP = 6
        private const val BAR_H = GAUGE_H + 2 * BAR_PADDING
        private const val BUTTON_H = 48
        private const val ROW_PADDING = 4
        private const val BUTTON_GAP = 4
        private const val ROW_H = BUTTON_H + 2 * ROW_PADDING

        private const val CHAR_W = 8
        private const val CHAR_H = 16
        private const val STRIP_STYLE = "background-color: #202020; border: 0px;"
        private const val BUTTON_STYLE = "background-color: #303030; border: 1px solid #505050; border-radius: 6px;"

        /**
         * The starter UI as it is first laid out in the game screen's [area], in screen pixels at [density] to the dp,
         * every tab closed and Tells with 2 unread, each gauge at half. The main window is the area's whole dp, rounded
         * down, so the fitted layout draws it no smaller than in dp.
         */
        fun show(session: FakeEngine.Session, area: Rect, density: Float): StarterUiCompact = StarterUiCompact((area.width / density).toInt(), (area.height / density).toInt()).also { it.draw(session) }

        fun paintButton(session: FakeEngine.Session, key: String, text: String, accessibleName: String) = session.label(button(key), text = text, accessibleName = accessibleName, accessibleButton = true, styleSheet = BUTTON_STYLE)

        fun button(key: String) = "BaseUI_chat_button_$key"
    }
}
