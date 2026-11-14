package org.mudlet.android.ui

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import org.mudlet.android.protocol.FrameWriter
import org.mudlet.android.protocol.Transport
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An engine for the UI to attach to, in the test's own process: it answers EngineClient's hello as the engine would
 * (docs/protocol.md, "Attaching") and then sends what a test tells it to. EngineClient reaches it through
 * [EngineClient.connector], over a local socket as it reaches the engine, but in the abstract namespace.
 */
class FakeEngine : AutoCloseable {
    private val name = "mudlet-fake-engine-${UUID.randomUUID()}"
    private val server = LocalServerSocket(name)
    private val sessions = LinkedBlockingQueue<Session>()

    @Volatile private var closed = false

    /** The instance the next session says hello with: a new one is a new engine, as after the old one was killed. */
    @Volatile var instance = FIRST_INSTANCE

    init {
        thread(name = "fake-engine", isDaemon = true) {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                if (closed) {
                    socket.close()
                    break
                }
                try {
                    sessions.add(Session(socket, instance))
                } catch (_: IOException) {
                    // The UI gave up on this connection before its hello
                    socket.close()
                }
            }
        }
    }

    /** For [EngineClient.connector]. */
    fun connect(): Transport {
        val socket = localSocket()
        return object : Transport {
            override val input: InputStream = socket.inputStream
            override val output: OutputStream = socket.outputStream
            override fun close() = socket.close()
        }
    }

    /** The next UI that attached, once it has said hello and been answered. */
    fun nextSession(timeoutMs: Long = 10_000): Session = sessions.poll(timeoutMs, TimeUnit.MILLISECONDS)
        ?: throw AssertionError("the UI did not attach to the fake engine within $timeoutMs ms")

    override fun close() {
        closed = true
        // Closing a LocalServerSocket does not wake its accept(): a last connection does
        try {
            localSocket().close()
        } catch (_: IOException) {
        }
        server.close()
        while (true) sessions.poll()?.close() ?: break
    }

    private fun localSocket(): LocalSocket {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    class Session(private val socket: LocalSocket, instance: String) : AutoCloseable {
        private val writer = FrameWriter(socket.outputStream)
        private val input = DataInputStream(socket.inputStream)

        /** Every request [receive] has read, the skipped ones included. */
        val received = mutableListOf<JSONObject>()

        /** The UI's hello: "instance" and "lastSeq" when it was attached to an engine before. */
        val hello: JSONObject

        init {
            socket.soTimeout = 10_000
            hello = readFrame(input)
            if (hello.optString("t") != "hello") throw IOException("the UI began with $hello")
            send(JSONObject().put("t", "hello").put("v", 1).put("pid", FAKE_PID).put("instance", instance))
            // As the engine follows its hello, so state an earlier test left in EngineClient goes
            hiddenInput(false)
            history(emptyList())
        }

        fun send(message: JSONObject) = writer.write(message)

        /** The next request of type [type] the UI sent, skipping the others; fails after [timeoutMs]. */
        fun receive(type: String, timeoutMs: Long = 10_000): JSONObject {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                // A read times out after soTimeout, or fails when the UI has closed the connection
                val frame = try {
                    readFrame(input)
                } catch (e: IOException) {
                    throw AssertionError("the UI sent no '$type': $e", e)
                }
                received.add(frame)
                if (frame.optString("t") == type) return frame
            }
            throw AssertionError("the UI sent no '$type' within $timeoutMs ms")
        }

        fun clear() = send(JSONObject().put("t", "clear"))

        /**
         * [runs]: each [start, length, fg, bg, attributes], as docs/protocol.md's "Runs" says. [replay]: one of the
         * lines an engine sends again to a UI that attached.
         */
        fun line(seq: Long, text: String, runs: List<List<Int>> = emptyList(), prompt: Boolean = false, replay: Boolean = false) = send(
            JSONObject().put("t", "line").put("seq", seq).put("text", text).put("prompt", prompt).put("replay", replay)
                .put("runs", JSONArray(runs.map { JSONArray(it) })),
        )

        fun tail(text: String, prompt: Boolean = true) = send(JSONObject().put("t", "tail").put("text", text).put("prompt", prompt))

        fun status(key: String, value: String) = send(JSONObject().put("t", "status").put("key", key).put("value", value))

        /** A reply to the request whose "id" was [re]. */
        fun reply(key: String, value: String, re: Any) = send(JSONObject().put("t", "status").put("key", key).put("value", value).put("re", re))

        fun hiddenInput(wanted: Boolean) = send(JSONObject().put("t", "hiddenInput").put("wanted", wanted))

        /** [lines]: newest first. */
        fun history(lines: List<String>) = send(JSONObject().put("t", "history").put("lines", JSONArray(lines)))

        fun commandLine(text: String) = send(JSONObject().put("t", "commandLine").put("text", text))

        fun announce(text: String, assertive: Boolean) = send(JSONObject().put("t", "announce").put("text", text).put("assertive", assertive))

        fun synced(seq: Long) = send(JSONObject().put("t", "synced").put("seq", seq))

        /** As the engine process dying: the UI sees the connection end. */
        override fun close() {
            // shutdownInput wakes the UI's reader at once, where close alone may leave it blocked
            try {
                socket.shutdownInput()
                socket.shutdownOutput()
            } catch (_: IOException) {
            }
            socket.close()
        }
    }

    companion object {
        const val FAKE_PID = 4242
        const val FIRST_INSTANCE = "fake-1"

        private fun readFrame(input: DataInputStream): JSONObject {
            val n = input.readInt()
            val payload = ByteArray(n)
            input.readFully(payload)
            return JSONObject(String(payload, Charsets.UTF_8))
        }
    }
}
