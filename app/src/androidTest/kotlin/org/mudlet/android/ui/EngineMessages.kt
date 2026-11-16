package org.mudlet.android.ui

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

/** The fake engine sends the script button bars, as a `buttonBars` message (docs/protocol.md). */
fun FakeEngine.Session.buttonBars(vararg bars: JSONObject) = send(JSONObject().put("t", "buttonBars").put("bars", JSONArray(bars.toList())))

/** Messages of docs/protocol.md that the tests of more than one part of the game screen send through the fake engine. */
object EngineMessages {
    /** A bar of a `buttonBars` message, at [place] (top, bottom, left, right or floating). */
    fun bar(id: Int, name: String, place: String, buttons: List<JSONObject>, vertical: Boolean = false, columns: Int = 1): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("styleSheet", "").put("place", place).put("vertical", vertical).put("columns", columns)
        .put("fillerOffset", 0).put("buttons", JSONArray(buttons))

    /** A button of a bar; [children] make it a menu. */
    fun button(
        id: Int,
        name: String,
        icon: String = "",
        pushDown: Boolean = false,
        checked: Boolean = false,
        children: List<JSONObject> = emptyList(),
    ): JSONObject = JSONObject().put("id", id).put("name", name).put("icon", icon).put("styleSheet", "").put("pushDown", pushDown)
        .put("checked", checked).put("menu", children.isNotEmpty()).put("children", JSONArray(children))

    /** An `appMap` message: a white map [w] by [h] of the map's pixels, at [scale] device pixels to each. */
    fun appMap(w: Int, h: Int, scale: Double = 1.0, viewOnly: Boolean = true): JSONObject = JSONObject().put("t", "appMap")
        .put("png", Base64.getEncoder().encodeToString(whitePng((w * scale).toInt(), (h * scale).toInt()))).put("w", w).put("h", h)
        .put("viewOnly", viewOnly)

    private fun whitePng(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
            .compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
