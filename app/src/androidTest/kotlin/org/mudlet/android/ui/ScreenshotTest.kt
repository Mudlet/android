package org.mudlet.android.ui

import android.Manifest
import android.app.UiModeManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.RUN_BOLD
import org.mudlet.android.ui.game.GAME_PILL_TAG
import org.mudlet.android.ui.game.GAME_SHEET_TAG
import org.mudlet.android.ui.scripts.LUA_EDITOR_TAG
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Base64
import java.util.concurrent.CompletableFuture

/**
 * Screenshots of the profile list, the game screen with the map behind its text, connected and after a drop, the
 * settings (the hub, a search and six pages, one as a change says it waits for the next connection, and in light mode
 * the hub and a page side by side on a tablet), the
 * map in front with a downloaded map and dragged off the player, a room's exits on it edited with the rooms found by
 * part of a name, the game sheet with two games open, and a trigger's page, at its top, at its Lua, with the keyboard
 * up and the Lua being typed, and with what is wrong with the Lua applied, light and dark, against a fake engine, for a
 * pull request that changes how they look. Each is saved in the app's
 * files as screenshots/<screen>-<light|dark>.png, which native/tests/android_instrumented.py copies into its output.
 * The app's own night mode needs API 31, so on older releases this takes none.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(NightMode())
        .around(compose)

    @Test
    fun light() = takeScreenshots("light")

    @Test
    fun dark() = takeScreenshots("dark")

    private fun takeScreenshots(mode: String) {
        if (Build.VERSION.SDK_INT < 31) return
        compose.runOnIdle {
            Settings.mapBehindText.reset()
            Settings.setAwaitsDefaults(PROFILES[0].name, false)
        }
        // A new engine instance, so no map or lines of an earlier test are left
        val first = fake.engine.nextSession()
        fake.engine.instance = "fake-screenshots-${System.nanoTime()}"
        first.close()
        val session = fake.engine.nextSession()
        session.clear()
        GAME_LINES.forEachIndexed { i, (text, runs) -> session.line(i + 1L, text, runs) }
        session.tail("HP:120/120 MP:80/80 > ")
        session.synced(GAME_LINES.size.toLong())
        session.profiles(PROFILES, session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == PROFILES }
        save("profiles-$mode")

        activate(hasContentDescription(string(R.string.profile_connect_to, PROFILES[0].name)))
        session.profiles(PROFILES, session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(GAME_LINES.last().first)).fetchSemanticsNodes().isNotEmpty() }
        // The first ask may come before the game screen's openProfile, which the test reads past
        session.send(appMap(session.receiveLast("setAppMapSize", readPast = true)))
        session.status("connection", "connected")
        compose.waitUntil(WAIT_MS) { EngineClient.front.status["connection"] == "connected" }
        save("game-$mode")
        session.status("connection", "disconnected")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription(string(R.string.connection_reconnect_to, PROFILES[0].name))).fetchSemanticsNodes().isNotEmpty() }
        save("game-lost-$mode")
        session.status("connection", "connected")

        // The game's settings show once its profile has loaded
        session.status("profile", "${PROFILES[0].name} loaded")
        val asked = session.receive("getSettings")
        session.settings(mapOf("announceIncomingText" to true, "advertiseScreenReader" to false, "blankLinesBehaviour" to "show"), asked.getLong("id"))
        activate(hasContentDescription(string(R.string.settings_title)))
        waitFor(hasText(string(R.string.settings_accessibility)))
        save("settings-$mode")
        compose.onNode(hasSetTextAction()).performTextInput("screen")
        waitFor(hasText(string(R.string.settings_search_all)))
        save("settings-search-$mode")
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_appearance)))
        activate(hasText(string(R.string.settings_appearance)))
        waitFor(hasText(string(R.string.settings_text_size)))
        save("settings-appearance-$mode")
        activate(hasContentDescription(string(R.string.navigate_back)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_connection)))
        activate(hasText(string(R.string.settings_connection)))
        waitFor(hasText(string(R.string.settings_gmcp)))
        save("settings-connection-$mode")
        // The game is connected, so a protocol's switch turned now waits for its next connection
        activate(hasText(string(R.string.settings_gmcp)))
        waitFor(hasText(string(R.string.settings_next_connection)))
        save("settings-connection-changed-$mode")
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasText(string(R.string.settings_accessibility)))
        waitFor(hasText(string(R.string.settings_blank_lines)))
        save("settings-accessibility-$mode")
        activate(hasContentDescription(string(R.string.navigate_back)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_advanced)))
        activate(hasText(string(R.string.settings_advanced)))
        waitFor(hasText(string(R.string.settings_fix_linebreaks)))
        save("settings-advanced-$mode")
        activate(hasContentDescription(string(R.string.navigate_back)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_display)))
        activate(hasText(string(R.string.settings_display)))
        waitFor(hasText(string(R.string.settings_undo_wrap_width)))
        save("settings-display-$mode")
        activate(hasContentDescription(string(R.string.navigate_back)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_security)))
        activate(hasText(string(R.string.settings_security)))
        waitFor(hasText(string(R.string.settings_accept_any_certificate)))
        save("settings-security-$mode")
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasContentDescription(string(R.string.navigate_back)))

        activate(hasContentDescription(string(R.string.app_map_show)))
        // The game's map, downloaded before this UI came, as after the app restarted
        session.send(
            JSONObject().put("t", "mapDownload").put("offered", true).put("host", "www.achaea.com").put("state", "loaded").put("label", "")
                .put("value", 0).put("maximum", 0).put("cancelable", false).put("error", ""),
        )
        // In front, it shows the map behind the text's frame until the engine draws one at its own size, with the
        // room's name along its top as Mudlet draws it
        val size = session.receiveLast("setAppMapSize")
        session.send(appMap(size, roomName = ROOM_NAME))
        val map = hasContentDescription(string(R.string.map_description))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(map).fetchSemanticsNodes().isNotEmpty() }
        save("map-$mode")

        // Dragged off the player, shown moved before the engine draws it, with the button that centres it on them
        session.send(appMap(size, roomName = ROOM_NAME, view = EngineMessages.mapView(size.getInt("w"), size.getInt("h"))))
        // Decoded on the reader's thread, and cut out there for the text behind it, which idle Compose does not wait for:
        // dragged before it comes, the map has no view to move off the player
        compose.waitUntil(WAIT_MS) { EngineClient.front.appMap.mover.view != null }
        compose.onNode(map).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(width * 0.02f, height * 0.015f)) }
            advanceEventTime(300)
            up()
        }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription(string(R.string.map_follow))).fetchSemanticsNodes().isNotEmpty() }
        save("map-moved-$mode")

        // Edited, a room's exits, those it has naming where they lead, and the rooms found by part of a name for one
        session.send(appMap(size, roomName = ROOM_NAME, view = EngineMessages.mapView(size.getInt("w"), size.getInt("h")), viewOnly = false))
        compose.runOnIdle { EngineClient.front.appMap.rooms.ask(1) }
        session.send(EngineMessages.room(session.receive("roomInfo"), 1, "The Crossroads", steps = 0, exits = EXITS))
        activate(hasText(string(R.string.room_edit_exits)))
        waitFor(hasText(string(R.string.exit_up)) and hasAnyAncestor(isDialog()))
        save("exits-$mode")
        activate(hasText(string(R.string.exit_up)) and hasAnyAncestor(isDialog()))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("hall")
        // Asked once the typing pauses, which the test's clock lets pass only while the test waits on Compose
        val find = CompletableFuture.supplyAsync { session.receive("findRooms") }
        compose.waitUntil(WAIT_MS) { find.isDone }
        session.send(EngineMessages.rooms(find.get(), "hall", listOf(Triple(8, "Guild hall", "Village"), Triple(9, "Hall of heroes", "Keep"), Triple(10, "Town hall", "Village"))))
        val found = hasText("Town hall") and hasAnyAncestor(isDialog())
        waitFor(found)
        compose.onNode(found).performScrollTo()
        save("exits-find-$mode")
        activate(hasText(string(R.string.room_edit_cancel)) and hasAnyAncestor(isDialog()))
        compose.runOnIdle { EngineClient.front.appMap.rooms.close() }

        activate(hasContentDescription(string(R.string.app_map_show)))

        fake.engine.openAlongside(PROFILES[1].name)
        session.openProfiles()
        val second = session.forProfile(PROFILES[1].name)
        second.synced(0)
        second.status("connection", "connected")
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.size == 2 && EngineClient.sessions.all { it.status["connection"] == "connected" } }
        compose.runOnIdle { EngineClient.setFront(PROFILES[0].name) }
        activate(hasTestTag(GAME_PILL_TAG))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_SHEET_TAG)).fetchSemanticsNodes().isNotEmpty() }
        save("games-$mode")
        activate(hasContentDescription(string(R.string.games_close)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_SHEET_TAG)).fetchSemanticsNodes().isEmpty() }

        if (mode == "light") {
            onATablet {
                activate(hasContentDescription(string(R.string.settings_title)))
                compose.scrollSettingsHubTo(hasText(string(R.string.settings_appearance)))
                activate(hasText(string(R.string.settings_appearance)))
                waitFor(hasText(string(R.string.settings_text_size)))
                save("settings-tablet-$mode")
            }
        }
    }

    @Test
    fun itemPageLight() = takeItemPageScreenshots("light")

    @Test
    fun itemPageDark() = takeItemPageScreenshots("dark")

    private fun takeItemPageScreenshots(mode: String) {
        if (Build.VERSION.SDK_INT < 31) return
        compose.runOnIdle { Settings.setAwaitsDefaults(PROFILES[0].name, false) }
        val first = fake.engine.nextSession()
        fake.engine.instance = "fake-screenshots-${System.nanoTime()}"
        first.close()
        val session = fake.engine.nextSession()
        session.clear()
        GAME_LINES.forEachIndexed { i, (text, runs) -> session.line(i + 1L, text, runs) }
        session.synced(GAME_LINES.size.toLong())
        session.profiles(PROFILES, session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == PROFILES }
        activate(hasContentDescription(string(R.string.profile_connect_to, PROFILES[0].name)))
        session.profiles(PROFILES, session.receive("openProfile"))
        waitFor(hasText(GAME_LINES.last().first))

        activate(hasContentDescription(string(R.string.scripts_open, PROFILES[0].name)))
        compose.waitForIdle()
        val greet = JSONObject().put("id", 1).put("parent", 0).put("name", "Wave back").put("folder", false).put("enabled", true).put("active", true)
            .put("package", "").put("error", "")
        repeat(3) {
            val ask = session.receive("scriptItems")
            val items = if (ask.getString("kind") == "trigger") JSONArray().put(greet) else JSONArray()
            session.send(JSONObject().put("t", "scriptItems").put("kind", ask.getString("kind")).put("items", items).put("re", ask.get("id")))
        }
        activate(hasText("Wave back"))
        compose.waitForIdle()

        fun fields(script: String, error: String = "") = JSONObject(greet.toString()).put("script", script).put("error", error).put("command", "").put("lineDelta", -1)
            .put("matchAll", false).put("filter", false).put("fireLength", 0).put("sound", false).put("soundFile", "").put("highlight", false)
            .put("highlightFg", "").put("highlightBg", "").put("patterns", JSONArray().put(JSONObject().put("text", "^(\\w+) waves at you").put("type", "regex")))

        fun answer(script: String, request: JSONObject, error: String = "") = session.send(JSONObject().put("t", "scriptItem").put("kind", "trigger").put("fields", fields(script, error)).put("re", request.get("id")))
        answer(ITEM_LUA, session.receive("scriptItem"))
        val lua = hasTestTag(LUA_EDITOR_TAG)
        waitFor(lua)
        save("script-item-$mode")

        compose.onNode(lua).performScrollTo()
        save("script-item-lua-$mode")

        // A name half typed, for Mudlet's functions to be offered over the keyboard's row of symbols
        val at = ITEM_LUA.indexOf("\n", ITEM_LUA.indexOf("send("))
        compose.onNode(lua).performSemanticsAction(SemanticsActions.SetSelection) { it(at, at, true) }
        compose.onNode(lua).performTextInput("\n")
        compose.onNode(lua).performTextInput("sen")
        waitFor(hasContentDescription(string(R.string.lua_indent)))
        // An emulator set up with keys of its own shows no keyboard, and this screenshot is then not taken
        if (keyboardComes()) save("script-item-keyboard-$mode")

        activate(hasText(string(R.string.scripts_apply)))
        compose.waitForIdle()
        val check = session.receive("checkLua")
        val message = "'=' expected near 'cecho'"
        val line = check.getString("code").substring(0, at).count { it == '\n' } + 3
        session.send(JSONObject().put("t", "luaCheck").put("ok", false).put("line", line).put("message", message).put("re", check.get("id")))
        answer(check.getString("code"), session.receive("editScriptItem"), error = message)
        waitFor(hasText(string(R.string.lua_go_to_line)))
        // Once the snackbar that says it is applied has gone from over the buttons
        waitFor(hasText(string(R.string.scripts_applied, "Wave back")))
        compose.waitUntil(SNACKBAR_MS) { compose.onAllNodes(hasText(string(R.string.scripts_applied, "Wave back"))).fetchSemanticsNodes().isEmpty() }
        save("script-item-problem-$mode")
    }

    private fun keyboardComes(): Boolean {
        val view = compose.activity.window.decorView
        return try {
            compose.waitUntil(WAIT_MS) { compose.runOnUiThread { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true } }
            true
        } catch (_: ComposeTimeoutException) {
            false
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** [block] on a screen the size of a 10-inch tablet's, on its side; the activity is recreated each way, as on a real resize. */
    private fun onATablet(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // The shell's output must be read for the command to finish
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            FileInputStream(pfd.fileDescriptor).readBytes()
        }
        try {
            shell("wm size 2560x1600")
            shell("wm density 320")
            compose.waitForIdle()
            block()
        } finally {
            shell("wm density reset")
            shell("wm size reset")
            compose.waitForIdle()
        }
    }

    /** The whole screen, system bars included, once the app has drawn what it last composed. */
    private fun save(name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("no screenshot of $name")
        val dir = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun appMap(size: JSONObject, roomName: String? = null, view: JSONObject? = null, viewOnly: Boolean = true): JSONObject {
        val w = size.getInt("w")
        val h = size.getInt("h")
        val scale = size.getDouble("scale")
        return JSONObject().put("t", "appMap").put("png", Base64.getEncoder().encodeToString(mapPng((w * scale).toInt(), (h * scale).toInt(), roomName, scale.toFloat())))
            .put("w", w).put("h", h).put("viewOnly", viewOnly).apply { if (view != null) put("view", view) }
    }

    // A grid of rooms joined by exits, in Mudlet's default room colours on its black map background, the player's in the
    // middle; [roomName] on a grey band along the top, at [scale] device pixels to the map's, as Mudlet's info text is
    private fun mapPng(w: Int, h: Int, roomName: String? = null, scale: Float = 1f): ByteArray {
        val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        val step = minOf(w, h) / 7f
        val room = step * 0.5f
        val exit = Paint().apply {
            color = android.graphics.Color.rgb(225, 225, 225)
            strokeWidth = step / 16
        }
        val fill = Paint()
        val colours = intArrayOf(0xFF4CAF50.toInt(), 0xFFB0BEC5.toInt(), 0xFF2196F3.toInt(), 0xFFFFC107.toInt(), 0xFF8D6E63.toInt())
        val columns = (w / step).toInt()
        val rows = (h / step).toInt()
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val x = (c + 0.5f) * step + (w - columns * step) / 2
                val y = (r + 0.5f) * step + (h - rows * step) / 2
                if (c + 1 < columns) canvas.drawLine(x, y, x + step, y, exit)
                if (r + 1 < rows && (c + r) % 2 == 0) canvas.drawLine(x, y, x, y + step, exit)
                fill.color = colours[(c * 7 + r * 3) % colours.size]
                canvas.drawRect(x - room / 2, y - room / 2, x + room / 2, y + room / 2, fill)
                if (r == rows / 2 && c == columns / 2) {
                    fill.color = android.graphics.Color.RED
                    canvas.drawCircle(x, y, room / 3, fill)
                }
            }
        }
        if (roomName != null) {
            val text = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 14 * scale
                typeface = android.graphics.Typeface.MONOSPACE
                isAntiAlias = true
            }
            canvas.drawRect(0f, 0f, w.toFloat(), text.textSize * 2, Paint().apply { color = android.graphics.Color.rgb(80, 80, 80) })
            canvas.drawText(roomName, text.textSize / 2, text.textSize * 1.4f, text)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    /**
     * The app's own night mode for a test whose name says dark, set before the activity starts: changing it later would
     * recreate the activity, which attaches to the engine anew. Each test ends following the system again.
     */
    private class NightMode : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                if (Build.VERSION.SDK_INT < 31) return base.evaluate()
                val modes = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(UiModeManager::class.java)
                val dark = description.methodName.contains("dark", ignoreCase = true)
                modes.setApplicationNightMode(if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
                try {
                    base.evaluate()
                } finally {
                    modes.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                }
            }
        }
    }

    private companion object {
        val PROFILES = listOf(
            Profile("Achaea", "achaea.com", 23, false, "", false),
            Profile("Aardwolf", "aardmud.org", 4000, false, "", false),
            Profile("StickMUD", "stickmud.com", 7680, true, "", false),
        )

        val GAME_LINES = listOf(
            coloured("The Crossroads", 11, bold = true),
            coloured("  Worn paths meet beneath an old oak. A signpost leans to the north.", -1),
            coloured("  Exits: north, east, south and west.", 6),
            coloured("A brass lantern lies here.", 3),
            coloured("A wandering minstrel is here, humming softly.", 13),
            coloured("The minstrel tells you, \"Lovely weather for an adventure.\"", 10),
            coloured("You feel a little hungry.", 1),
        )

        // A line in one colour of the ANSI palette, as a run (docs/protocol.md, "Runs"): start, length, fg, bg, attributes
        fun coloured(text: String, fg: Int, bold: Boolean = false): Pair<String, List<List<Int>>> = text to listOf(listOf(0, text.length, fg, -1, if (bold) RUN_BOLD else 0))

        val EXITS = listOf(
            EngineMessages.exit("n", 2, "Under the old oak", locked = true),
            EngineMessages.exit("e", 3, "Market square"),
            EngineMessages.exit("s", 4, "Mill road"),
            EngineMessages.exit("w", 5, "Village gate"),
        )

        const val ROOM_NAME = "A weapons vault / 56721 (Nu Second Circle of)"

        val ITEM_LUA = """
            -- Waves back at whoever waves, once
            local who = matches[2]
            if who ~= "you" and not waved[who] then
              send("wave " .. who)
              cecho("<green>Waved back at " .. who .. ", as anyone polite would on a road this empty\n")
              waved[who] = true
            end
        """.trimIndent()

        const val WAIT_MS = 10_000L

        // Longer than a snackbar with an action stays
        const val SNACKBAR_MS = 15_000L

        // For the window to show the last frame drawn, which compose's idle does not wait for
        const val SETTLE_MS = 750L
        const val TIMEOUT_MS = 240_000L
    }
}
