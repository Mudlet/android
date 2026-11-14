package org.mudlet.android.engine

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import org.mudlet.android.protocol.EngineLink
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.Transport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A UI for the tests: an [EngineLink] to the engine's local socket, as the app's EngineClient has, that keeps what the
 * engine sends for the tests to wait on.
 */
class EngineProbe(context: Context) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var engineHello: EngineMessage.Hello? = null
    private var synced = false
    private val status = HashMap<String, String>()

    // Every value of status "restore", in order: the engine resends only the last to a UI that attaches late
    private val restores = ArrayList<String>()
    private val texts = ArrayList<String>()

    private val link = EngineLink(
        { connect(EngineSocket.path(context)) },
        object : EngineLink.Listener {
            override fun onAttached(hello: EngineMessage.Hello, sameInstance: Boolean) = update {
                engineHello = hello
                synced = false
                status.clear()
            }

            override fun onMessage(message: EngineMessage) = update {
                when (message) {
                    is EngineMessage.Status -> {
                        status[message.key] = message.value
                        if (message.key == "restore") restores.add(message.value)
                    }

                    is EngineMessage.Line -> texts.add(message.text)

                    is EngineMessage.Tail -> texts.add(message.text)

                    is EngineMessage.Synced -> synced = true

                    else -> {}
                }
            }

            override fun onDetached(reason: String) = update { synced = false }
        },
    )

    init {
        link.start()
    }

    fun send(message: JSONObject) = check(link.send(message)) { "not attached to the engine: $message not sent" }

    /** The engine's hello, once attached and caught up (the engine has sent its status and its lines). */
    fun attached(timeoutMs: Long = ENGINE_START_MS): EngineMessage.Hello = await("the engine to answer hello and catch the UI up", timeoutMs) {
        engineHello.takeIf { synced }
    }

    fun status(key: String, timeoutMs: Long = TIMEOUT_MS, pred: (String) -> Boolean): String = await("status $key to match", timeoutMs) {
        status[key]?.takeIf(pred)
    }

    fun restores(): List<String> = lock.withLock { restores.toList() }

    fun text(timeoutMs: Long = TIMEOUT_MS, pred: (String) -> Boolean): String = await("a line or tail to match", timeoutMs) {
        texts.firstOrNull(pred)
    }

    private fun <T : Any> await(what: String, timeoutMs: Long, get: () -> T?): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock {
            while (true) {
                get()?.let { return it }
                val left = deadline - System.nanoTime()
                if (left <= 0) {
                    throw AssertionError("timed out after $timeoutMs ms waiting for $what; status $status, last lines ${texts.takeLast(10)}")
                }
                changed.awaitNanos(left)
            }
        }
    }

    private fun update(block: () -> Unit) = lock.withLock {
        block()
        changed.signalAll()
    }

    override fun close() = link.stop()

    companion object {
        // Qt, then libmudlet's profile and its Lua, on a slow emulator
        const val ENGINE_START_MS = 120_000L
        const val TIMEOUT_MS = 60_000L

        private fun connect(path: String): Transport {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            } catch (e: IOException) {
                socket.close()
                throw e
            }
            return object : Transport {
                override val input: InputStream = socket.inputStream
                override val output: OutputStream = socket.outputStream
                override fun close() = socket.close()
            }
        }
    }
}
