package org.mudlet.android.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The app's own Kotlin protocol code (EngineLink, FrameReader/Writer) against the
 * desktop build of the engine, over a Unix socket as on Android. Run by
 * native/tests/run_jvm_client_test.sh, which passes the engine binary and a fake game port.
 */
class EngineLinkJvmTest {
    private val engineBin = System.getProperty("engineBin").orEmpty()
    private val fakePort = System.getProperty("fakePort").orEmpty().toIntOrNull() ?: 0
    private lateinit var socketPath: Path
    private var engine: Process? = null
    private val events = LinkedBlockingQueue<Any>()

    // JDK's close() waits for a blocked reader to leave read(); LocalSocket does not, so a
    // reader can wake after start() has already run. This makes that happen every time.
    @Volatile private var lateReaderWake = false

    @Before
    fun setUp() {
        assumeTrue("engineBin not set; run native/tests/run_jvm_client_test.sh", engineBin.isNotEmpty() && fakePort > 0)
        socketPath = Files.createTempDirectory("engine-jvm").resolve("engine.sock")
        startEngine()
    }

    @After
    fun tearDown() {
        engine?.destroyForcibly()?.waitFor()
    }

    private fun startEngine() {
        engine = ProcessBuilder(engineBin, "--socket", socketPath.toString(), "--heartbeat-ms", "1000")
            .apply { environment()["QT_QPA_PLATFORM"] = "offscreen" }
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    private fun connectUnix(path: Path = socketPath): Transport {
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            ch.connect(UnixDomainSocketAddress.of(path))
        } catch (e: IOException) {
            ch.close()
            throw e
        }
        // Own streams: Channels.newInputStream would serialise reads and writes on one lock
        return object : Transport {
            override val input = object : InputStream() {
                override fun read(): Int {
                    val b = ByteArray(1)
                    return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                    ch.read(ByteBuffer.wrap(b, off, len))
                } catch (e: IOException) {
                    if (lateReaderWake) Thread.sleep(50)
                    throw e
                }
            }
            override val output = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    val buf = ByteBuffer.wrap(b, off, len)
                    while (buf.hasRemaining()) ch.write(buf)
                }
            }
            override fun close() = ch.close()
        }
    }

    private fun newLink(
        path: Path = socketPath,
        profileSockets: Boolean = false,
        queue: LinkedBlockingQueue<Any> = events,
    ): EngineLink = EngineLink(
        { connectUnix(path) },
        object : EngineLink.Listener {
            override fun onAttached(hello: EngineMessage.Hello, sameInstance: Boolean) {
                queue.put(Attached(hello, sameInstance))
            }
            override fun onMessage(message: EngineMessage) {
                queue.put(message)
            }
            override fun onDetached(reason: String) {
                queue.put(Detached(reason))
            }
        },
        profileSockets = profileSockets,
    )

    private data class Attached(val hello: EngineMessage.Hello, val same: Boolean)
    private data class Detached(val reason: String)

    private inline fun <reified T> next(
        timeoutMs: Long = 5000,
        queue: LinkedBlockingQueue<Any> = events,
        pred: (T) -> Boolean = { true },
    ): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val left = deadline - System.nanoTime()
            check(left > 0) { "timed out waiting for ${T::class.simpleName}" }
            val e = queue.poll(left, TimeUnit.NANOSECONDS) ?: continue
            if (e is T && pred(e)) return e
        }
    }

    private fun percentile(sorted: List<Long>, p: Int) = sorted[(sorted.size * p / 100).coerceAtMost(sorted.size - 1)]

    @Test
    fun theStandInHasNoScriptWindows() {
        val link = newLink()
        link.start()
        next<Attached>()
        assertEquals(EngineMessage.Windows(true, emptyList(), emptyList()), next<EngineMessage.Windows>())
        next<EngineMessage.Synced>()
        link.send(Requests.getWindowText("chat", -300, 300, 5))
        val refused = next<EngineMessage.Error>()
        assertEquals("getWindowText: there is no console named \"chat\"", refused.message)
        assertEquals(5, (refused.replyTo as Number).toInt())
        link.send(Requests.placeUserWindow("side", 0, 0, 300, 200, false))
        assertEquals("placeUserWindow: the stand-in engine has no script windows", next<EngineMessage.Error>().message)
        link.send(Requests.pointer("box", PointerType.PRESS, 1.5, 2.0, POINTER_LEFT, POINTER_LEFT, 0, 0))
        assertEquals("pointer: the stand-in engine has no script windows", next<EngineMessage.Error>().message)
        link.send(Requests.getImage("/no/such.png", 16, 16, null, 6))
        val noImage = next<EngineMessage.Error>()
        assertEquals("getImage: the stand-in engine has no script windows", noImage.message)
        assertEquals(6, (noImage.replyTo as Number).toInt())
        link.stop()
    }

    @Test
    fun theStandInHasNoButtonsOrCommands() {
        val link = newLink()
        link.start()
        next<Attached>()
        assertEquals(EngineMessage.ButtonBars(emptyList()), next<EngineMessage.ButtonBars>())
        assertEquals(EngineMessage.Commands(emptyList()), next<EngineMessage.Commands>())
        next<EngineMessage.Synced>()
        link.send(Requests.pressButton(1))
        assertEquals("pressButton: the stand-in engine has no buttons or commands", next<EngineMessage.Error>().message)
        link.send(Requests.activateCommand(1))
        assertEquals("activateCommand: the stand-in engine has no buttons or commands", next<EngineMessage.Error>().message)
        link.stop()
    }

    @Test
    fun theStandInHasNoMap() {
        val link = newLink()
        link.start()
        next<Attached>()
        next<EngineMessage.Synced>()
        link.send(Requests.mapPointer(MapPointerType.DOUBLE_CLICK, 1.5, 2.0, POINTER_LEFT, 0, 0))
        assertEquals("mapPointer: the stand-in engine has no map", next<EngineMessage.Error>().message)
        link.send(Requests.setMapViewOnly(false))
        assertEquals("setMapViewOnly: the stand-in engine has no map", next<EngineMessage.Error>().message)
        link.stop()
    }

    @Test
    fun severalProfilesEachOnTheirOwnSocket() {
        val control = newLink(profileSockets = true)
        control.start()
        assertEquals(null, next<Attached>().hello.profile)
        for (name in listOf("North", "South")) {
            control.send(Requests.saveProfile(Profile(name, "127.0.0.1", fakePort, false, "", false), 1))
            next<EngineMessage.Profiles>()
        }
        control.send(Requests.openProfile("North", null, connect = true, alongside = true, id = 2))
        next<EngineMessage.OpenProfiles> { (it.replyTo as? Number)?.toInt() == 2 }
        control.send(Requests.openProfile("South", null, connect = true, alongside = true, id = 3))
        val open = next<EngineMessage.OpenProfiles> { (it.replyTo as? Number)?.toInt() == 3 }.profiles
        assertEquals(listOf("North" to false, "South" to true), open.map { it.name to it.front })
        assertEquals(listOf(1, 2), open.map { it.slot })

        val queues = open.associate { it.name to LinkedBlockingQueue<Any>() }
        val links = open.associate { it.name to newLink(Path.of(it.socket), queue = queues.getValue(it.name)) }
        links.values.forEach(EngineLink::start)
        for ((name, queue) in queues) {
            assertEquals(name, next<Attached>(queue = queue).hello.profile)
            next<EngineMessage.Status>(queue = queue) { it.key == "connection" && it.value == "connected" }
        }
        links.getValue("North").send(Requests.send("echo north only"))
        val said = next<EngineMessage.Line>(queue = queues.getValue("North")) { it.text.contains("north only") }
        next<EngineMessage.OpenProfiles> { list -> list.profiles.any { it.name == "North" && it.lastSeq >= said.seq } }
        val south = queues.getValue("South")
        while (true) {
            val e = south.poll(300, TimeUnit.MILLISECONDS) ?: break
            assertTrue("North's line reached South's socket: $e", e !is EngineMessage.Line || !e.text.contains("north only"))
        }

        control.send(Requests.closeProfile("South", 4))
        val left = next<EngineMessage.OpenProfiles> { (it.replyTo as? Number)?.toInt() == 4 }.profiles
        assertEquals(listOf("North" to true), left.map { it.name to it.front })
        next<Detached>(queue = south)
        links.values.forEach(EngineLink::stop)
        control.stop()
    }

    @Test
    fun protocolLatencyThroughputAndEngineDeath() {
        val link = newLink()
        link.start()
        val first = next<Attached>().hello
        assertEquals(PROTOCOL_VERSION, first.version)
        next<EngineMessage.Synced>()

        // What is sent says which connection it went out on, as a text box's typing is counted by it
        assertEquals("the first connection", 1, link.sendOn(Requests.setScreenSize(100, 30)))

        link.send(Requests.connect("127.0.0.1", fakePort, false))
        // The tell line comes before the banner's prompt, and next() discards what it skips
        assertEquals("Lua notify() reached the JVM client", "Tell", next<EngineMessage.Notify>().title)
        val banner = next<EngineMessage.Line> { it.prompt }
        assertEquals("HP:100> ", banner.text)

        link.send(Requests.send("naws"))
        next<EngineMessage.Line> { it.text == "NAWS asked" }
        link.send(Requests.send("size"))
        assertEquals("the game was told the size setScreenSize gave", "SIZE: 100x30", next<EngineMessage.Line> { it.text.startsWith("SIZE:") }.text)

        link.send(Requests.runLua("return 6 * 7", 99))
        val lua = next<EngineMessage.Status> { it.key == "luaResult" }
        assertEquals("42", lua.value)
        assertEquals(99, (lua.replyTo as Number).toInt())

        // Ping round trips through the app's EngineLink
        val rtts = (0 until 3000).map { link.ping()!! / 1000 }.drop(300).sorted()
        println("JVM: ping n=${rtts.size} median=${rtts[rtts.size / 2]}us p99=${percentile(rtts, 99)}us max=${rtts.last()}us")

        // Paced floods, decoded by FrameReader the way the UI does
        for (rate in listOf(25_000, 50_000, 100_000, 200_000)) {
            events.clear()
            link.send(Requests.send("pace $rate $rate"))
            var lines = 0
            var skipped = 0L
            while (true) {
                when (val e = events.poll(30, TimeUnit.SECONDS) ?: error("flood timed out")) {
                    is EngineMessage.Line -> {
                        lines++
                        if (e.text == "FLOOD DONE") break
                    }

                    is EngineMessage.Gap -> skipped += e.skipped

                    else -> {}
                }
            }
            println("JVM: paced $rate lines/s for 1 s: delivered $lines, skipped $skipped")
            assertEquals(rate + 1L, lines + skipped)
        }

        // Detach and reattach to the same engine: only the lines that arrived meanwhile come back
        link.stop()
        val seqBefore = link.lastSeq
        Thread.sleep(200)
        events.clear()
        val link2 = EngineLink(
            ::connectUnix,
            object : EngineLink.Listener {
                override fun onAttached(hello: EngineMessage.Hello, sameInstance: Boolean) = events.put(Attached(hello, sameInstance))
                override fun onMessage(message: EngineMessage) = events.put(message)
                override fun onDetached(reason: String) = events.put(Detached(reason))
            },
        )
        link2.start()
        next<Attached>()
        next<EngineMessage.Synced>()
        link2.stop()
        println("JVM: a new link (no instance) re-synced; seq before detach was $seqBefore")

        // Engine death and restart, with the original link reconnecting on its own
        events.clear()
        link.start()
        assertTrue(next<Attached>().same)
        assertEquals("each attach is a new connection", 2, link.connection)
        val synced = next<EngineMessage.Synced>()
        assertTrue("reattach to the same engine replays nothing", synced.seq == seqBefore)

        // Rotation: stop() and start() back to back must leave exactly one reader attached
        events.clear()
        lateReaderWake = true
        link.stop()
        link.start()
        assertTrue(next<Attached>().same)
        next<EngineMessage.Synced>()
        Thread.sleep(1000)
        lateReaderWake = false
        assertTrue("no second attach or detach after a quick restart", events.none { it is Attached || it is Detached })
        println("JVM: stop()+start() back to back reattached once, with no reconnect fight")
        val killedAt = System.nanoTime()
        ProcessHandle.of(first.pid.toLong()).ifPresent { it.destroyForcibly() }
        next<Detached>()
        val detectUs = (System.nanoTime() - killedAt) / 1000
        startEngine()
        val restarted = next<Attached>(10_000)
        val reconnectMs = (System.nanoTime() - killedAt) / 1_000_000
        assertTrue("new engine instance", !restarted.same && restarted.hello.instance != first.instance)
        println("JVM: engine killed -> EOF seen in ${detectUs}us; link reattached to the restarted engine ${reconnectMs}ms after the kill")
        link.stop()
    }
}
