package org.mudlet.android.protocol

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.util.Base64

/** The run encoding and message types of native/enginehost/SocketBridge.cpp, without an engine. */
class ProtocolTest {
    @Test
    fun runsOfEachLengthFlattenToTheSameStride() {
        val line = FrameReader.parse(
            JSONObject("""{"t":"line","seq":7,"text":"abcdef","prompt":false,"runs":[[0,1,9],[1,2,-1,4,5],[3,3,${RUN_RGB or 0x123456},-1,0,42]]}"""),
        ) as EngineMessage.Line
        assertArrayEquals(
            intArrayOf(0, 1, 9, -1, 0, 0, 1, 2, -1, 4, RUN_BOLD or RUN_UNDERLINE, 0, 3, 3, RUN_RGB or 0x123456, -1, 0, 42),
            line.runs,
        )
        assertEquals(3 * RUN_STRIDE, line.runs.size)
    }

    @Test
    fun theNewStylesHaveBitsOfTheirOwn() {
        val bits = listOf(RUN_BOLD, RUN_ITALIC, RUN_UNDERLINE, RUN_STRIKE, RUN_REVERSE, RUN_OVERLINE, RUN_BLINK, RUN_FAST_BLINK, RUN_CONCEALED)
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 64, 128, 256), bits)
    }

    @Test
    fun tailAndClearParse() {
        val tail = FrameReader.parse(JSONObject("""{"t":"tail","text":"HP> ","runs":[[0,2,1,-1,1]],"prompt":true}""")) as EngineMessage.Tail
        assertEquals("HP> ", tail.text)
        assertTrue(tail.prompt)
        assertArrayEquals(intArrayOf(0, 2, 1, -1, 1, 0), tail.runs)
        assertEquals(EngineMessage.Clear, FrameReader.parse(JSONObject("""{"t":"clear"}""")))
    }

    @Test
    fun profilesParseInTheEnginesOrder() {
        val message = FrameReader.parse(
            JSONObject(
                """{"t":"profiles","re":"r1","profiles":[""" +
                    """{"name":"Beta","host":"b.example","port":4000,"tls":true,"login":"hero","autoReconnect":true},""" +
                    """{"name":"Alpha","host":"a.example","port":23,"tls":false,"login":"","autoReconnect":false}]}""",
            ),
        )
        assertEquals(
            EngineMessage.Profiles(
                listOf(Profile("Beta", "b.example", 4000, true, "hero", true), Profile("Alpha", "a.example", 23, false, "", false)),
                "r1",
            ),
            message,
        )
        assertEquals(EngineMessage.Error("openProfile: no", 5), FrameReader.parse(JSONObject("""{"t":"error","message":"openProfile: no","re":5}""")))
    }

    @Test
    fun theProfileRequestsCarryWhatTheEngineReads() {
        val saved = Requests.saveProfile(Profile("Alpha", "a.example", 4000, true, "hero", false), 1)
        val fields = mapOf("t" to "saveProfile", "name" to "Alpha", "host" to "a.example", "port" to 4000, "tls" to true, "login" to "hero")
        assertEquals(fields + mapOf("autoReconnect" to false, "id" to 1L), saved.toMap())
        assertEquals(mapOf("t" to "openProfile", "name" to "Alpha", "connect" to false, "id" to 2L), Requests.openProfile("Alpha", null, false, 2).toMap())
        assertEquals("pw", Requests.openProfile("Alpha", "pw", true, 3).getString("password"))
        assertEquals(mapOf("t" to "deleteProfile", "name" to "Alpha", "id" to 4L), Requests.deleteProfile("Alpha", 4).toMap())
        assertEquals(mapOf("t" to "listProfiles", "id" to 5L), Requests.listProfiles(5).toMap())
    }

    @Test
    fun theInputLineMessagesParse() {
        assertEquals(EngineMessage.HiddenInput(true), FrameReader.parse(JSONObject("""{"t":"hiddenInput","wanted":true}""")))
        assertEquals(EngineMessage.HiddenInput(false), FrameReader.parse(JSONObject("""{"t":"hiddenInput","wanted":false}""")))
        assertEquals(
            EngineMessage.History(listOf("north", "say hi")),
            FrameReader.parse(JSONObject("""{"t":"history","lines":["north","say hi"]}""")),
        )
        assertEquals(EngineMessage.History(emptyList()), FrameReader.parse(JSONObject("""{"t":"history","lines":[]}""")))
        assertEquals(EngineMessage.CommandLine("x"), FrameReader.parse(JSONObject("""{"t":"commandLine","text":"x"}""")))
        assertEquals(
            EngineMessage.Status("completion", "say hello", 6),
            FrameReader.parse(JSONObject("""{"t":"status","key":"completion","value":"say hello","re":6}""")),
        )
    }

    @Test
    fun theInputLineRequestsCarryWhatTheEngineReads() {
        assertEquals(mapOf("t" to "sendHidden", "text" to "pw"), Requests.sendHidden("pw").toMap())
        assertEquals(mapOf("t" to "dismissHidden"), Requests.dismissHidden().toMap())
        assertEquals(mapOf("t" to "complete", "text" to "say he", "fromHistory" to false, "id" to 6L), Requests.complete("say he", false, 6).toMap())
        assertEquals(true, Requests.complete("say", true, 7).getBoolean("fromHistory"))
    }

    @Test
    fun announceParses() {
        assertEquals(EngineMessage.Announce("hi", true), FrameReader.parse(JSONObject("""{"t":"announce","text":"hi","assertive":true}""")))
        assertEquals(EngineMessage.Announce("hi", false), FrameReader.parse(JSONObject("""{"t":"announce","text":"hi","assertive":false}""")))
    }

    @Test
    fun aLinkParsesWithItsMenuAndOnlyTheLookItSets() {
        val link = FrameReader.parse(
            JSONObject(
                """{"t":"link","link":3,"tooltip":"the stew","menu":true,"menuTitle":"Stew","spoiler":false,"revealed":false,
                |"disabled":false,"concealed":false,"visited":true,"selected":false,"fg":${RUN_RGB or 0x00ffff},"styles":$RUN_BOLD,"re":7,
                |"items":[{"label":"Look","enabled":true,"separator":false},{"label":"","enabled":false,"separator":true}]}
                """.trimMargin(),
            ),
        )
        assertEquals(
            EngineMessage.Link(
                3, null, "the stew", true, "Stew", listOf(LinkItem("Look", true, false), LinkItem("", false, true)),
                spoiler = false, revealed = false, disabled = false, concealed = false, visited = true, selected = false,
                fg = RUN_RGB or 0x00ffff, bg = null, styles = RUN_BOLD, replyTo = 7,
            ),
            link,
        )
        assertEquals(EngineMessage.LinksChanged(listOf(3, 5)), FrameReader.parse(JSONObject("""{"t":"linksChanged","ids":[3,5]}""")))
    }

    @Test
    fun followLinkSendsAnItemOnlyWhenGiven() {
        assertEquals(mapOf("t" to "followLink", "link" to 3, "id" to 1L), Requests.followLink(3, null, null, 1).toMap())
        assertEquals(mapOf("t" to "followLink", "link" to 3, "item" to 0, "id" to 1L), Requests.followLink(3, 0, null, 1).toMap())
        assertEquals(mapOf("t" to "getLink", "link" to 3, "id" to 1L), Requests.getLink(3, null, 1).toMap())
    }

    @Test
    fun aScriptWindowsLinkIsAskedForAndFollowedByTheWindowsName() {
        assertEquals(mapOf("t" to "getLink", "link" to 2, "window" to "chat", "id" to 4L), Requests.getLink(2, "chat", 4).toMap())
        assertEquals(
            mapOf("t" to "followLink", "link" to 2, "item" to 1, "window" to "chat", "id" to 4L),
            Requests.followLink(2, 1, "chat", 4).toMap(),
        )
        val link = FrameReader.parse(
            JSONObject(
                """{"t":"link","link":2,"window":"chat","tooltip":"","menu":false,"menuTitle":"","spoiler":false,"revealed":false,
                |"disabled":false,"concealed":false,"visited":false,"selected":false,"items":[]}
                """.trimMargin(),
            ),
        ) as EngineMessage.Link
        assertEquals("chat", link.window)
    }

    @Test
    fun setScreenSizeCarriesColumnsAndRows() {
        assertEquals(mapOf("t" to "setScreenSize", "columns" to 100, "rows" to 30), Requests.setScreenSize(100, 30).toMap())
    }

    @Test
    fun setAppStateCarriesBothFlags() {
        assertEquals(mapOf("t" to "setAppState", "active" to false, "minimized" to true), Requests.setAppState(false, true).toMap())
    }

    @Test
    fun attentionAndMoveTypedLineParse() {
        assertEquals(EngineMessage.Attention(3000), FrameReader.parse(JSONObject("""{"t":"attention","ms":3000}""")))
        assertEquals(EngineMessage.Attention(0), FrameReader.parse(JSONObject("""{"t":"attention","ms":0}""")))
        assertEquals(EngineMessage.MoveTypedLine, FrameReader.parse(JSONObject("""{"t":"moveTypedLine"}""")))
    }

    @Test
    fun scriptWindowsParseWithTheirOptionalParts() {
        val message = FrameReader.parse(
            JSONObject(
                """{"t":"windows","full":true,"windows":[""" +
                    """{"name":"side","kind":"userWindow","parent":"","x":0,"y":0,"w":300,"h":200,"visible":true,"shown":true,"stack":0,""" +
                    """"bg":${RUN_RGB or 0x102030},"fontFamily":"Mono","fontSize":10.5,"inDock":true,"autoDock":false,"dockArea":"left",""" +
                    """"dockFloating":true,"dockTitle":"Side","scrolledBackTo":12,"columns":40,"rows":10,"scrollBar":true,"timeStamps":false},""" +
                    """{"name":"side","kind":"commandLine","parent":"side","parentKind":"userWindow","x":0,"y":0,"w":0,"h":0,""" +
                    """"visible":true,"shown":false,"stack":1,"inDock":false},""" +
                    """{"name":"hp_text","kind":"label","parent":"","x":10,"y":600,"w":200,"h":20,"visible":true,"shown":true,"stack":3,""" +
                    """"bg":$RUN_RGB,"bgAlpha":0,"inDock":false},""" +
                    """{"name":"later","kind":"hologram","parent":"","x":0,"y":0,"w":1,"h":1,"visible":true,"shown":true,"stack":2,"inDock":false}],""" +
                    """"removed":[{"name":"chat","kind":"miniConsole"},{"name":"x","kind":"hologram"}]}""",
            ),
        ) as EngineMessage.Windows
        assertTrue(message.full)
        val side = ScriptWindow(
            "side", WindowKind.USER_WINDOW, "", null, 0, 0, 300, 200, true, true, 0, RUN_RGB or 0x102030, 255, "Mono", 10.5, true,
            ScriptWindow.Dock(false, "left", true, "Side"), ScriptWindow.Console(12, 40, 10, true, false),
        )
        val line = ScriptWindow("side", WindowKind.COMMAND_LINE, "side", WindowKind.USER_WINDOW, 0, 0, 0, 0, true, false, 1, -1, 255, null, 0.0, false, null, null)
        // A transparent background, as a gauge's text label has
        val text = ScriptWindow("hp_text", WindowKind.LABEL, "", null, 10, 600, 200, 20, true, true, 3, RUN_RGB, 0, null, 0.0, false, null, null)
        // A kind the app does not know is left out
        assertEquals(listOf(side, line, text), message.windows)
        assertEquals(WindowKey("side", WindowKind.COMMAND_LINE), line.key)
        assertEquals(listOf(WindowKey("chat", WindowKind.MINI_CONSOLE)), message.removed)
        val update = FrameReader.parse(JSONObject("""{"t":"windows","windows":[]}""")) as EngineMessage.Windows
        assertEquals(EngineMessage.Windows(false, emptyList(), emptyList()), update)
    }

    @Test
    fun windowTextMainAreaAndCommandLinesParse() {
        val text = FrameReader.parse(
            JSONObject(
                """{"t":"windowText","window":"chat","lineCount":3,"first":1,"re":"w1","lines":[""" +
                    """{"text":"hello","runs":[[0,5,${RUN_RGB or 0xff0000}]],"prompt":false,"wrapped":false},""" +
                    """{"text":"HP>","runs":[],"prompt":true,"wrapped":true}]}""",
            ),
        ) as EngineMessage.WindowText
        assertEquals("chat", text.window)
        assertEquals(3, text.lineCount)
        assertEquals(1, text.first)
        assertEquals("w1", text.replyTo)
        assertEquals(listOf("hello", "HP>"), text.lines.map { it.text })
        assertArrayEquals(intArrayOf(0, 5, RUN_RGB or 0xff0000, -1, 0, 0), text.lines[0].runs)
        assertTrue(text.lines[1].prompt && text.lines[1].wrapped)
        assertEquals(
            EngineMessage.MainArea(800, 600, 90, 30, 10, 0, 780, 600),
            FrameReader.parse(JSONObject("""{"t":"mainArea","w":800,"h":600,"columns":90,"rows":30,"textX":10,"textY":0,"textW":780,"textH":600}""")),
        )
        assertEquals(EngineMessage.WindowCommandLine("chat", "say hi"), FrameReader.parse(JSONObject("""{"t":"windowCommandLine","name":"chat","text":"say hi"}""")))
    }

    @Test
    fun theScriptWindowRequestsCarryWhatTheEngineReads() {
        assertEquals(mapOf("t" to "setMainWindowSize", "w" to 800, "h" to 600), Requests.setMainWindowSize(800, 600).toMap())
        assertEquals(
            mapOf("t" to "getWindowText", "window" to "chat", "first" to -300, "count" to 300, "id" to 8L),
            Requests.getWindowText("chat", -300, 300, 8).toMap(),
        )
        assertEquals(
            mapOf("t" to "placeUserWindow", "name" to "side", "x" to 1, "y" to 2, "w" to 3, "h" to 4, "floating" to true),
            Requests.placeUserWindow("side", 1, 2, 3, 4, true).toMap(),
        )
        assertEquals(mapOf("t" to "closeUserWindow", "name" to "side"), Requests.closeUserWindow("side").toMap())
        assertEquals(mapOf("t" to "sendWindowCommandLine", "name" to "chat", "text" to "look"), Requests.sendWindowCommandLine("chat", "look").toMap())
    }

    @Test
    fun labelsParseWithTheirOptionalParts() {
        val full = FrameReader.parse(
            JSONObject(
                """{"t":"label","name":"box","text":"<b>hi</b>","image":"/data/a.svg","svg":true,"svgTint":${RUN_RGB or 0xff0000},""" +
                    """"svgTintAlpha":128,"svgRotation":90.5,"svgShearX":0.25,"svgShearY":-0.5,"movie":"/data/b.gif","moviePaused":true,""" +
                    """"movieFrame":3,"movieSpeed":50,"movieW":64,"movieH":48,"toolTip":"tip","clickThrough":true,"accessibleName":"Box",""" +
                    """"accessibleButton":true,"liveRegion":"assertive","styleSheet":"QLabel { color: red; }"}""",
            ),
        )
        assertEquals(
            EngineMessage.Label(
                LabelContent(
                    "box", "<b>hi</b>", "/data/a.svg", true, RUN_RGB or 0xff0000, 128, 90.5, 0.25, -0.5, "/data/b.gif", true, 3, 50, 64, 48,
                    "tip", true, "Box", true, LiveRegion.ASSERTIVE, "QLabel { color: red; }",
                ),
            ),
            full,
        )
        // The engine leaves out the parts at their defaults
        val plain = FrameReader.parse(
            JSONObject(
                """{"t":"label","name":"box","text":"","image":"","svg":false,"movie":"","moviePaused":false,"movieFrame":-1,""" +
                    """"movieSpeed":100,"toolTip":"","clickThrough":false,"accessibleName":"","accessibleButton":false,"styleSheet":""}""",
            ),
        ) as EngineMessage.Label
        assertEquals(
            LabelContent("box", "", "", false, -1, 255, 0.0, 0.0, 0.0, "", false, -1, 100, null, null, "", false, "", false, LiveRegion.OFF, ""),
            plain.label,
        )
    }

    @Test
    fun aLabelsLiveRegionParsesAndOneThisClientDoesNotKnowIsOff() {
        fun liveRegion(wire: String) = (FrameReader.parse(JSONObject().put("t", "label").put("name", "box").put("liveRegion", wire)) as EngineMessage.Label).label.liveRegion
        assertEquals(LiveRegion.POLITE, liveRegion("polite"))
        assertEquals(LiveRegion.ASSERTIVE, liveRegion("assertive"))
        assertEquals(LiveRegion.OFF, liveRegion("off"))
        assertEquals(LiveRegion.OFF, liveRegion("loud"))
        assertEquals(LiveRegion.OFF, liveRegion("Polite"))
    }

    @Test
    fun anImageDecodesItsPng() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, -1)
        val image = FrameReader.parse(
            JSONObject().put("t", "image").put("path", "/data/a.png").put("png", Base64.getEncoder().encodeToString(png)).put("w", 4).put("h", 3).put("re", 9),
        ) as EngineMessage.Image
        assertEquals("/data/a.png", image.path)
        assertArrayEquals(png, image.png)
        assertEquals(4 to 3, image.w to image.h)
        assertEquals(9, image.replyTo)
        // Not base64: empty, and the link goes on
        val broken = FrameReader.parse(JSONObject("""{"t":"image","path":"x","png":"not base64!","w":1,"h":1}""")) as EngineMessage.Image
        assertEquals(0, broken.png.size)
    }

    @Test
    fun thePointerAndImageRequestsCarryWhatTheEngineReads() {
        assertEquals(
            mapOf("t" to "pointer", "window" to "box", "type" to "press", "x" to 1.5, "y" to 2.0, "button" to POINTER_LEFT, "buttons" to POINTER_LEFT),
            Requests.pointer("box", PointerType.PRESS, 1.5, 2.0, POINTER_LEFT, POINTER_LEFT, 0, 0).toMap(),
        )
        assertEquals(
            mapOf("t" to "pointer", "window" to "main", "type" to "wheel", "x" to 0.0, "y" to 0.0, "modifiers" to (MODIFIER_SHIFT or MODIFIER_CONTROL), "wheelY" to -120),
            Requests.pointer("main", PointerType.WHEEL, 0.0, 0.0, 0, 0, MODIFIER_SHIFT or MODIFIER_CONTROL, -120).toMap(),
        )
        assertEquals("doubleClick", PointerType.DOUBLE_CLICK.wire)
        assertEquals(mapOf("t" to "getImage", "path" to "a.png", "id" to 3L), Requests.getImage("a.png", null, null, null, 3).toMap())
        assertEquals(
            mapOf("t" to "getImage", "path" to "a.gif", "w" to 16, "h" to 8, "frame" to 2, "id" to 4L),
            Requests.getImage("a.gif", 16, 8, 2, 4).toMap(),
        )
        assertThrows(IllegalArgumentException::class.java) { Requests.getImage("a.png", 16, null, null, 5) }
    }

    @Test
    fun buttonBarsParseWithTheirMenus() {
        val message = FrameReader.parse(
            JSONObject(
                """{"t":"buttonBars","bars":[""" +
                    """{"id":4,"name":"Tools","styleSheet":"QToolBar{}","place":"top","vertical":false,"columns":2,"fillerOffset":1,"buttons":[""" +
                    """{"id":5,"name":"push","icon":"/a.png","styleSheet":"b{}","pushDown":true,"checked":true,"menu":false,"children":[]},""" +
                    """{"id":6,"name":"more","icon":"","styleSheet":"","pushDown":false,"checked":false,"menu":true,"children":[""" +
                    """{"id":7,"name":"one","icon":"","styleSheet":"","pushDown":false,"checked":false,"menu":false,"children":[]}]}]},""" +
                    """{"id":8,"name":"Later","styleSheet":"","place":"bottom","vertical":false,"columns":1,"fillerOffset":0,"buttons":[]},""" +
                    """{"id":9,"name":"Side","styleSheet":"","place":"floating","vertical":true,"columns":1,"fillerOffset":0,"buttons":[]}]}""",
            ),
        ) as EngineMessage.ButtonBars
        val one = BarButton(7, "one", "", "", false, false, false, emptyList())
        assertEquals(
            listOf(
                ButtonBar(
                    4,
                    "Tools",
                    "QToolBar{}",
                    BarPlace.TOP,
                    false,
                    2,
                    1,
                    listOf(BarButton(5, "push", "/a.png", "b{}", true, true, false, emptyList()), BarButton(6, "more", "", "", false, false, true, listOf(one))),
                ),
                // The bar in a place this app does not know is left out
                ButtonBar(9, "Side", "", BarPlace.FLOATING, true, 1, 0, emptyList()),
            ),
            message.bars,
        )
        assertEquals(EngineMessage.ButtonBars(emptyList()), FrameReader.parse(JSONObject("""{"t":"buttonBars","bars":[]}""")))
    }

    @Test
    fun commandsParse() {
        val message = FrameReader.parse(
            JSONObject(
                """{"t":"commands","commands":[{"id":3,"package":"voices","name":"Speech","icon":"s.png","tooltip":"Read aloud",""" +
                    """"menuPath":"Tools/Voices","shortcut":"Ctrl+Shift+S","onMenu":true,"onToolbar":false,"enabled":false,""" +
                    """"checkable":true,"checked":true,"pinned":true,"pulse":true}]}""",
            ),
        ) as EngineMessage.Commands
        assertEquals(
            listOf(AddonCommand(3, "voices", "Speech", "s.png", "Read aloud", "Tools/Voices", "Ctrl+Shift+S", true, false, false, true, true, true, true)),
            message.commands,
        )
    }

    @Test
    fun theButtonAndCommandRequestsCarryWhatTheEngineReads() {
        assertEquals(mapOf("t" to "pressButton", "button" to 5), Requests.pressButton(5).toMap())
        assertEquals(mapOf("t" to "activateCommand", "command" to 3), Requests.activateCommand(3).toMap())
    }

    @Test
    fun aMapFrameDecodesItsPng() {
        val png = byteArrayOf(-119, 80, 78, 71)
        val map = FrameReader.parse(
            JSONObject().put("t", "map").put("png", Base64.getEncoder().encodeToString(png)).put("w", 400).put("h", 300).put("viewOnly", false),
        ) as EngineMessage.Map
        assertArrayEquals(png, map.png)
        assertEquals(400 to 300, map.w to map.h)
        assertEquals(false, map.viewOnly)
        val broken = FrameReader.parse(JSONObject("""{"t":"map","png":"not base64!","w":1,"h":1}""")) as EngineMessage.Map
        assertEquals(0, broken.png.size)
        assertTrue("viewing is the default", broken.viewOnly)
    }

    @Test
    fun theMapRequestsCarryWhatTheEngineReads() {
        assertEquals(
            mapOf("t" to "mapPointer", "type" to "press", "x" to 1.5, "y" to 2.0, "button" to POINTER_LEFT),
            Requests.mapPointer(MapPointerType.PRESS, 1.5, 2.0, POINTER_LEFT, 0, 0).toMap(),
        )
        assertEquals(
            mapOf("t" to "mapPointer", "type" to "wheel", "x" to 0.0, "y" to 0.0, "modifiers" to MODIFIER_CONTROL, "wheelY" to 120),
            Requests.mapPointer(MapPointerType.WHEEL, 0.0, 0.0, 0, MODIFIER_CONTROL, 120).toMap(),
        )
        assertEquals("doubleClick", MapPointerType.DOUBLE_CLICK.wire)
        assertEquals(mapOf("t" to "setMapViewOnly", "viewOnly" to false), Requests.setMapViewOnly(false).toMap())
    }

    @Test
    fun theAppsMapAndTheRoomsDecode() {
        val png = byteArrayOf(-119, 80, 78, 71)
        val map = FrameReader.parse(
            JSONObject().put("t", "appMap").put("png", Base64.getEncoder().encodeToString(png)).put("w", 360).put("h", 640).put("viewOnly", false),
        ) as EngineMessage.AppMap
        assertArrayEquals(png, map.png)
        assertEquals(360 to 640, map.w to map.h)
        assertEquals(false, map.viewOnly)
        val nothing = FrameReader.parse(JSONObject("""{"t":"appMap","png":"","w":0,"h":0,"viewOnly":true}""")) as EngineMessage.AppMap
        assertEquals(0 to 0, nothing.w to nothing.h)
        assertEquals(0, nothing.png.size)
        val rooms = FrameReader.parse(
            JSONObject(
                """{"t":"rooms","text":"hall","rooms":[{"id":12,"name":"Great Hall","area":"Castle"},"junk",{"id":3,"name":"hall","area":""}],"more":true,"re":4}""",
            ),
        ) as EngineMessage.Rooms
        assertEquals("hall", rooms.text)
        assertEquals(listOf(RoomMatch(12, "Great Hall", "Castle"), RoomMatch(3, "hall", "")), rooms.rooms)
        assertTrue(rooms.more)
        assertEquals(4, rooms.replyTo)
        val none = FrameReader.parse(JSONObject("""{"t":"rooms","text":"x"}""")) as EngineMessage.Rooms
        assertEquals(emptyList<RoomMatch>(), none.rooms)
        assertEquals(false, none.more)
    }

    @Test
    fun theAppMapRequestsCarryWhatTheEngineReads() {
        assertEquals(mapOf("t" to "setAppMapSize", "w" to 360, "h" to 640, "scale" to 2.75), Requests.setAppMapSize(360, 640, 2.75).toMap())
        assertEquals(mapOf("t" to "setAppMapSize", "w" to 0, "h" to 0, "scale" to 1.0), Requests.setAppMapSize(0, 0, 1.0).toMap())
        assertEquals(
            mapOf("t" to "appMapPointer", "type" to "doubleClick", "x" to 1.5, "y" to 2.0, "button" to POINTER_LEFT),
            Requests.appMapPointer(MapPointerType.DOUBLE_CLICK, 1.5, 2.0, POINTER_LEFT, 0, 0).toMap(),
        )
        assertEquals(
            mapOf("t" to "appMapPointer", "type" to "wheel", "x" to 0.0, "y" to 0.0, "wheelY" to -120),
            Requests.appMapPointer(MapPointerType.WHEEL, 0.0, 0.0, 0, 0, -120).toMap(),
        )
        assertEquals(mapOf("t" to "findRooms", "text" to "hall", "id" to 7L), Requests.findRooms("hall", 7).toMap())
        assertEquals(mapOf("t" to "showRoom", "room" to 12), Requests.showRoom(12).toMap())
    }

    @Test
    fun theMapDownloadDecodesAndAnUnknownStateIsNone() {
        val going = FrameReader.parse(
            JSONObject(
                """{"t":"mapDownload","offered":true,"host":"example.org","state":"downloading","label":"Downloading map file for use in game...","value":5,"maximum":10,"cancelable":true,"error":""}""",
            ),
        ) as EngineMessage.MapDownload
        assertEquals(
            EngineMessage.MapDownload(true, "example.org", MapDownloadState.DOWNLOADING, "Downloading map file for use in game...", 5, 10, true, ""),
            going,
        )
        val failed = FrameReader.parse(
            JSONObject("""{"t":"mapDownload","offered":true,"host":"example.org","state":"failed","error":"Host not found"}"""),
        ) as EngineMessage.MapDownload
        assertEquals(MapDownloadState.FAILED to "Host not found", failed.state to failed.error)
        assertEquals(0 to false, failed.maximum to failed.cancelable)
        val later = FrameReader.parse(JSONObject("""{"t":"mapDownload","offered":false,"state":"verifying"}""")) as EngineMessage.MapDownload
        assertEquals(MapDownloadState.NONE, later.state)
        assertEquals("" to false, later.host to later.offered)
        assertEquals(listOf("none", "downloading", "loaded", "failed", "cancelled"), MapDownloadState.entries.map { it.wire })
    }

    @Test
    fun theMapDownloadRequestsCarryNothingElse() {
        assertEquals(mapOf("t" to "downloadMap"), Requests.downloadMap().toMap())
        assertEquals(mapOf("t" to "cancelMapDownload"), Requests.cancelMapDownload().toMap())
    }

    @Test
    fun aFrameIsOneWriteAndNeverFlushed() {
        val writes = ArrayList<ByteArray>()
        val out = object : OutputStream() {
            override fun write(b: Int) = throw AssertionError("a frame written byte by byte")
            override fun write(b: ByteArray, off: Int, len: Int) {
                writes.add(b.copyOfRange(off, off + len))
            }

            override fun flush() = throw AssertionError("flushed: a LocalSocket's flush() waits for the engine to read")
        }
        val message = Requests.ping(7)
        FrameWriter(out).write(message)
        val payload = message.toString().toByteArray(Charsets.UTF_8)
        assertArrayEquals(byteArrayOf(0, 0, 0, payload.size.toByte()) + payload, writes.single())
    }
}
