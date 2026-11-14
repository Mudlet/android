package org.mudlet.android.engine

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A game server in the test's process, for the engine in :engine to connect to on 127.0.0.1: it keeps the first bytes
 * each connection sends it, which for a client that wants TLS is its ClientHello, and then sends a banner.
 * native/tests/fake_telnet.py is the full one, on the host.
 */
class FakeGame : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName(HOST))
    private val connections: MutableList<Socket> = Collections.synchronizedList(ArrayList())

    /** The first bytes each connection sent, in the order they connected; empty for one that has sent nothing yet. */
    val firstBytes: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())

    val port: Int get() = server.localPort

    val accepted: Int get() = connections.size

    init {
        thread(name = "fake-game", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                connections.add(socket)
                // One thread accepts, so the list grows only here
                firstBytes.add(ByteArray(0))
                val index = firstBytes.size - 1
                thread(name = "fake-game-session", isDaemon = true) { serve(socket, index) }
            }
        }
    }

    private fun serve(socket: Socket, index: Int) {
        try {
            val input = socket.getInputStream()
            val buffer = ByteArray(512)
            // A client's first bytes before the banner: a TLS client that reads the banner before it has sent its
            // ClientHello fails its handshake and may never send it
            socket.soTimeout = FIRST_BYTES_MS
            val n = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                0
            }
            if (n < 0) return
            if (n > 0) firstBytes[index] = buffer.copyOf(n)
            socket.soTimeout = 0
            socket.getOutputStream().write(BANNER)
            // Then keep the connection open, as a game does, until the engine or the test closes it
            while (input.read(buffer) >= 0) Unit
        } catch (_: IOException) {
        } finally {
            socket.close()
        }
    }

    override fun close() {
        server.close()
        synchronized(connections) { connections.forEach { it.close() } }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val BANNER_TEXT = "Welcome to the fake game"

        // How long the game waits for a client's first bytes before it sends its banner
        private const val FIRST_BYTES_MS = 500

        // A coloured line, then a prompt with GA
        private val BANNER = "\u001b[1;31m$BANNER_TEXT\u001b[0m\r\nHP:100> ".toByteArray() + byteArrayOf(0xFF.toByte(), 0xF9.toByte())
    }
}
