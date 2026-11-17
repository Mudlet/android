package org.mudlet.android.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What a thread that stop() retired may still do to the link, with no engine: the test is each connection's other end. */
class EngineLinkStopTest {
    // As android.net.LocalSocket: neither close() nor Thread.interrupt() wakes a read() that waits
    private class Wire : Transport {
        private val incoming = LinkedBlockingQueue<ByteArray>()
        private var unread = ByteArray(0)
        val helloSent = CountDownLatch(1)
        val closes = AtomicInteger()
        val sent = LinkedBlockingQueue<JSONObject>()

        override val input = object : InputStream() {
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                while (unread.isEmpty()) {
                    unread = try {
                        incoming.take()
                    } catch (_: InterruptedException) {
                        continue
                    }
                    if (unread.isEmpty()) return -1
                }
                val n = minOf(len, unread.size)
                unread.copyInto(b, off, 0, n)
                unread = unread.copyOfRange(n, unread.size)
                return n
            }
        }

        override val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            // FrameWriter writes a frame in one call
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closes.get() > 0) throw IOException("socket closed")
                sent.add(JSONObject(String(b, off + 4, len - 4, Charsets.UTF_8)))
                helloSent.countDown()
            }
        }

        override fun close() {
            closes.incrementAndGet()
        }

        fun answer(message: JSONObject) {
            val frame = ByteArrayOutputStream()
            FrameWriter(frame).write(message)
            incoming.add(frame.toByteArray())
        }

        fun end() = incoming.add(ByteArray(0))
    }

    @Test
    fun aThreadStoppedBeforeTheEngineAnsweredDoesNotAttach() {
        val wires = LinkedBlockingQueue<Wire>()
        val attaches = AtomicInteger()
        val link = EngineLink(
            { Wire().also(wires::add) },
            object : EngineLink.Listener {
                override fun onAttached(hello: EngineMessage.Hello, sameInstance: Boolean) {
                    attaches.incrementAndGet()
                }
                override fun onMessage(message: EngineMessage) {}
                override fun onDetached(reason: String) {}
            },
        )
        val hello = JSONObject().put("t", "hello").put("v", 1).put("pid", 1).put("instance", "one")
        link.start()
        val stopped = next(wires)
        assertTrue("the first thread said hello", stopped.helloSent.await(WAIT_S, TimeUnit.SECONDS))
        link.stop()
        link.start()
        val running = next(wires)
        assertTrue("the second thread said hello", running.helloSent.await(WAIT_S, TimeUnit.SECONDS))

        stopped.answer(hello)
        // stop() closed it once; its thread closes it again as it leaves
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (stopped.closes.get() < 2) {
            assertTrue("the stopped thread did not leave its connection", System.nanoTime() < deadline)
            Thread.sleep(1)
        }
        assertEquals("the stopped thread attached", 0, attaches.get())
        assertEquals(0, link.connection)

        running.answer(hello)
        val waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (attaches.get() < 1) {
            assertTrue("the running thread did not attach", System.nanoTime() < waitUntil)
            Thread.sleep(1)
        }
        running.sent.clear()
        assertEquals("a request goes out on the running thread's connection", 1, link.sendOn(Requests.ping(7)))
        assertNotNull(running.sent.poll(WAIT_S, TimeUnit.SECONDS))
        link.stop()
        running.end()
    }

    private fun next(wires: LinkedBlockingQueue<Wire>): Wire = wires.poll(WAIT_S, TimeUnit.SECONDS) ?: throw AssertionError("the link did not connect")

    private companion object {
        const val WAIT_S = 5L
    }
}
