package org.mudlet.android.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * Keeps docs/protocol.md, the engine (native/enginehost/SocketBridge.*, ControlBridge.* and LineJson.*), this client and the Python test client in
 * step: the same messages in each direction, the same fields, the same protocol version, and the same phases of
 * libmudlet's restore status (native/enginehost/MudletEngine.cpp and MudletProfile.cpp).
 */
class ProtocolSpecTest {
    private val root = File(System.getProperty("repoRoot") ?: error("repoRoot not set; run through Gradle"))
    private fun read(path: String) = root.resolve(path).readText()

    private val spec = read("docs/protocol.md")
    private val bridge = read("native/enginehost/SocketBridge.cpp") + read("native/enginehost/ControlBridge.cpp")

    // The line frames, which LineJson.cpp writes as text: "{\"prompt\":", ",\"t\":\"line\""
    private val lineJson = read("native/enginehost/LineJson.cpp")

    private val backquoted = Regex("`(\\w+)`")

    /** Message name to its fields (without the "?" of an optional one), from one table of the spec. */
    private fun specTable(heading: String): Map<String, Set<String>> {
        val section = spec.substringAfter("\n### $heading\n", "").substringBefore("\n#")
        val rows = section.lines().filter { it.startsWith("| `") }
        check(rows.isNotEmpty()) { "no message table under \"### $heading\" in docs/protocol.md" }
        return rows.associate { row ->
            val cells = row.split('|').map { it.trim() }
            val name = backquoted.find(cells[1])!!.groupValues[1]
            name to backquoted.findAll(cells[2]).map { it.groupValues[1] }.toSet()
        }
    }

    private val toEngine = specTable("UI to engine")
    private val toUi = specTable("Engine to UI")

    @Test
    fun theVersionIsTheSameEverywhere() {
        val specVersion = Regex("The current version is \\*\\*(\\d+)\\*\\*").find(spec)!!.groupValues[1].toInt()
        val cpp = Regex("protocolVersion = (\\d+);").find(read("native/enginehost/SocketBridge.h"))!!.groupValues[1].toInt()
        val python = Regex("^PROTOCOL_VERSION = (\\d+)$", RegexOption.MULTILINE)
            .find(read("native/tests/protocol_client.py"))!!.groupValues[1].toInt()
        assertEquals("docs/protocol.md against Protocol.kt", specVersion, PROTOCOL_VERSION)
        assertEquals("SocketBridge.h against Protocol.kt", PROTOCOL_VERSION, cpp)
        assertEquals("protocol_client.py against Protocol.kt", PROTOCOL_VERSION, python)
    }

    @Test
    fun theEngineHandlesTheRequestsTheSpecLists() {
        // The hello check, then the dispatch on the message type
        val handled = Regex("type [!=]= QLatin1String\\(\"(\\w+)\"\\)").findAll(bridge).map { it.groupValues[1] }.toSet()
        assertEquals("requests SocketBridge.cpp and ControlBridge.cpp handle", toEngine.keys, handled)
        val read = Regex("(?:\\.|->)value\\(QLatin1String\\(\"(\\w+)\"\\)\\)").findAll(bridge).map { it.groupValues[1] }.toSet()
        // client is the one field the engine ignores
        assertEquals("request fields SocketBridge.cpp and ControlBridge.cpp read", toEngine.values.flatten().toSet() - "client" + "t", read)
    }

    @Test
    fun theEngineSendsTheMessagesTheSpecLists() {
        val sent = Regex("QStringLiteral\\(\"t\"\\), QStringLiteral\\(\"(\\w+)\"\\)").findAll(bridge).map { it.groupValues[1] }.toSet() +
            Regex("\\\\\"t\\\\\":\\\\\"(\\w+)\\\\\"").findAll(lineJson).map { it.groupValues[1] }
        assertEquals("messages SocketBridge.cpp, ControlBridge.cpp and LineJson.cpp send", toUi.keys, sent)
        // Keys of the JSON objects the engine writes: {QStringLiteral("key"), value} and insert(QStringLiteral("key"), ...)
        val keys = Regex("(?:\\{|insert\\()QStringLiteral\\(\"(\\w+)\"\\),").findAll(bridge).map { it.groupValues[1] }.toSet() +
            Regex("\\\\\"(\\w+)\\\\\":").findAll(lineJson).map { it.groupValues[1] }
        assertEquals("fields SocketBridge.cpp, ControlBridge.cpp and LineJson.cpp write", toUi.values.flatten().toSet() + "t", keys)
    }

    @Test
    fun theClientBuildsTheRequestsTheSpecLists() {
        // Every builder in Requests, called with placeholder arguments; hello with an instance, so it has every field
        val built = Requests::class.java.declaredMethods
            .filter { it.returnType == JSONObject::class.java }
            .map { method ->
                val args: List<Any> = method.parameterTypes.zip(method.genericParameterTypes).map { (type, generic) ->
                    when (type) {
                        String::class.java -> "x"
                        Int::class.javaPrimitiveType, Int::class.javaObjectType -> 1
                        Double::class.javaPrimitiveType -> 1.0
                        Long::class.javaPrimitiveType -> 1L
                        Boolean::class.javaPrimitiveType -> true
                        Profile::class.java -> Profile("x", "x", 1, true, "x", true)
                        ProxySettings::class.java -> ProxySettings(true, "x", 1, "x")
                        PointerType::class.java -> PointerType.PRESS
                        MapPointerType::class.java -> MapPointerType.PRESS
                        MapTarget::class.java -> MapTarget.APP
                        List::class.java -> if (VariableKey::class.java.name in generic.typeName) listOf(VariableKey("x", VariableType.STRING)) else listOf("x")
                        Map::class.java -> mapOf("x" to true)
                        RoomEdit::class.java -> RoomEdit("x", "x", 1, 1, true, Triple(1, 1, 1))
                        ScriptItemKind::class.java -> ScriptItemKind.TRIGGER
                        ScriptItem::class.java -> ScriptItem(ScriptItemNode(1, 0, "x", false, true, true, "", "", ""), "x", ScriptItemDetails.Alias("x", "x"))
                        else -> error("Requests.${method.name} takes a ${type.name}: teach ProtocolSpecTest about it")
                    }
                }
                method.invoke(Requests, *args.toTypedArray()) as JSONObject
            }
            .associate { it.getString("t") to (it.keySet() - "t") }
        assertEquals("requests and their fields that Requests builds", toEngine, built)
    }

    @Test
    fun theRestorePhasesAreTheSameEverywhere() {
        val section = spec.substringAfter("\n### The restore status\n", "").substringBefore("\n#")
        val documented = Regex("^\\| `(\\w+)", RegexOption.MULTILINE).findAll(section).map { it.groupValues[1] }.toList()
        check(documented.isNotEmpty()) { "no table under \"### The restore status\" in docs/protocol.md" }
        val sources = read("native/enginehost/MudletEngine.cpp") + read("native/enginehost/MudletProfile.cpp")
        val sent = Regex("restoreStatus\\(QStringLiteral\\(\"(\\w+)\"\\)").findAll(sources).map { it.groupValues[1] }.toSet()
        assertEquals("restore phases EngineRestore knows, against docs/protocol.md", documented, EngineRestore.PHASES)
        assertEquals("restore phases MudletEngine.cpp and MudletProfile.cpp send", documented.toSet(), sent)
    }

    @Test
    fun theClientParsesEveryMessageTheEngineSends() {
        val parsed = toUi.map { (type, fields) ->
            val message = JSONObject().put("t", type)
            for (field in fields) {
                message.put(field, if (field in setOf("runs", "profiles", "lines", "items", "ids", "rooms")) JSONArray() else "1")
            }
            FrameReader.parse(message)
        }
        for (message in parsed) {
            assertNotEquals("docs/protocol.md lists a message FrameReader does not know", EngineMessage.Unknown::class, message::class)
        }
        val kinds = EngineMessage::class.sealedSubclasses.toSet() - EngineMessage.Unknown::class
        assertEquals("one EngineMessage class per message the engine sends", kinds, parsed.map { it::class }.toSet())
    }
}
