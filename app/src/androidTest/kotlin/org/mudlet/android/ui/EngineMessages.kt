package org.mudlet.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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

    /**
     * An `appMap` message: a map [w] by [h] of the map's pixels all in [colour], white unless given, at [scale] device
     * pixels to each, laid out as [view] says; with [rooms], squares of that colour a tenth of its narrow side across, a
     * gap as wide between them, on [colour] as the map's background.
     */
    fun appMap(
        w: Int,
        h: Int,
        scale: Double = 1.0,
        viewOnly: Boolean = true,
        view: JSONObject? = null,
        colour: Int = android.graphics.Color.WHITE,
        rooms: Int? = null,
    ): JSONObject = JSONObject()
        .put("t", "appMap").put("png", Base64.getEncoder().encodeToString(png((w * scale).toInt(), (h * scale).toInt(), colour, rooms))).put("w", w).put("h", h)
        .put("viewOnly", viewOnly).apply { if (view != null) put("view", view) }

    /**
     * The `view` of a frame [w] by [h]: 20 rooms across its narrow side of the area Castle's [level], of its [levels],
     * the player's room [player] in its middle, which it follows unless [following] is false, having drawn the `moveMap`
     * [moved], with [rooms] (id to its middle) drawn.
     */
    fun mapView(
        w: Int,
        h: Int,
        following: Boolean = true,
        moved: Int = 0,
        rooms: Map<Int, Pair<Double, Double>> = mapOf(1 to (w / 2.0 to h / 2.0)),
        level: Int = 0,
        levels: List<Int> = listOf(0),
        player: Int = 1,
    ): JSONObject {
        val list = JSONArray(rooms.map { (id, at) -> JSONObject().put("id", id).put("x", at.first).put("y", at.second).put("name", "Room $id") })
        return JSONObject().put("area", 1).put("areaName", "Castle").put("level", level).put("zoom", 20.0).put("minZoom", 3.0)
            .put("maxZoom", 30.0).put("following", following).put("player", player).put("roomSize", minOf(w, h) / 20.0).put("moved", moved)
            .put("rooms", list).put("levels", JSONArray(levels))
    }

    /**
     * The engine's `room`, the answer to [request]: room [id] of Castle at [x], [y], [z], [steps] from the player's, with
     * [exits], and what the map's editing sets of it.
     */
    fun room(
        request: JSONObject,
        id: Int,
        name: String,
        steps: Int,
        exits: List<JSONObject> = emptyList(),
        x: Int = 0,
        y: Int = 0,
        z: Int = 0,
        env: Int = -1,
        weight: Int = 1,
        locked: Boolean = false,
    ): JSONObject = JSONObject().put("t", "room")
        .put("id", id).put("name", name).put("areaId", 1).put("area", "Castle").put("x", x).put("y", y).put("z", z).put("steps", steps)
        .put("exits", JSONArray(exits)).put("symbol", "").put("env", env).put("color", "#808080").put("weight", weight).put("locked", locked)
        .put("re", request.get("id"))

    /** The engine's `envs`, the answer to [request]: each environment's number to its colour, `#rrggbb`. */
    fun envs(request: JSONObject, vararg envs: Pair<Int, String>): JSONObject = JSONObject().put("t", "envs")
        .put("envs", JSONArray(envs.map { (env, color) -> JSONObject().put("env", env).put("color", color) })).put("re", request.get("id"))

    /** An exit of a `room`: a direction ("n" to "out") or a special exit's command, to room [to]. */
    fun exit(direction: String, to: Int, toName: String, locked: Boolean = false): JSONObject = JSONObject().put("direction", direction).put("to", to)
        .put("toName", toName).put("locked", locked)

    /** The engine's `areas`, the answer to [request]: id to name. */
    fun areas(request: JSONObject, vararg areas: Pair<Int, String>): JSONObject = JSONObject().put("t", "areas")
        .put("areas", JSONArray(areas.map { (id, name) -> JSONObject().put("id", id).put("name", name) })).put("re", request.get("id"))

    /** The engine's `rooms`, the answer to the findRooms [request] for [text]: each room found's id, name and area's name. */
    fun rooms(request: JSONObject, text: String, found: List<Triple<Int, String, String>>, more: Boolean = false): JSONObject {
        val list = JSONArray(found.map { (id, name, area) -> JSONObject().put("id", id).put("name", name).put("area", area) })
        return JSONObject().put("t", "rooms").put("text", text).put("rooms", list).put("more", more).put("re", request.get("id"))
    }

    private fun png(w: Int, h: Int, colour: Int, rooms: Int?): ByteArray {
        val out = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        if (rooms != null) {
            val side = maxOf(1, minOf(w, h) / 10)
            val paint = Paint().apply { color = rooms }
            val canvas = Canvas(bitmap)
            for (y in side until h - side step 2 * side) {
                for (x in side until w - side step 2 * side) canvas.drawRect(x.toFloat(), y.toFloat(), (x + side).toFloat(), (y + side).toFloat(), paint)
            }
        }
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
