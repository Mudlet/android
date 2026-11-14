package org.mudlet.android.protocol

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream

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
                3, "the stew", true, "Stew", listOf(LinkItem("Look", true, false), LinkItem("", false, true)),
                spoiler = false, revealed = false, disabled = false, concealed = false, visited = true, selected = false,
                fg = RUN_RGB or 0x00ffff, bg = null, styles = RUN_BOLD, replyTo = 7,
            ),
            link,
        )
        assertEquals(EngineMessage.LinksChanged(listOf(3, 5)), FrameReader.parse(JSONObject("""{"t":"linksChanged","ids":[3,5]}""")))
    }

    @Test
    fun followLinkSendsAnItemOnlyWhenGiven() {
        assertEquals(mapOf("t" to "followLink", "link" to 3, "id" to 1L), Requests.followLink(3, null, 1).toMap())
        assertEquals(mapOf("t" to "followLink", "link" to 3, "item" to 0, "id" to 1L), Requests.followLink(3, 0, 1).toMap())
        assertEquals(mapOf("t" to "getLink", "link" to 3, "id" to 1L), Requests.getLink(3, 1).toMap())
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
                    """{"name":"later","kind":"hologram","parent":"","x":0,"y":0,"w":1,"h":1,"visible":true,"shown":true,"stack":2,"inDock":false}],""" +
                    """"removed":[{"name":"chat","kind":"miniConsole"},{"name":"x","kind":"hologram"}]}""",
            ),
        ) as EngineMessage.Windows
        assertTrue(message.full)
        val side = ScriptWindow(
            "side", WindowKind.USER_WINDOW, "", null, 0, 0, 300, 200, true, true, 0, RUN_RGB or 0x102030, "Mono", 10.5, true,
            ScriptWindow.Dock(false, "left", true, "Side"), ScriptWindow.Console(12, 40, 10, true, false),
        )
        val line = ScriptWindow("side", WindowKind.COMMAND_LINE, "side", WindowKind.USER_WINDOW, 0, 0, 0, 0, true, false, 1, -1, null, 0.0, false, null, null)
        // A kind the app does not know is left out
        assertEquals(listOf(side, line), message.windows)
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
