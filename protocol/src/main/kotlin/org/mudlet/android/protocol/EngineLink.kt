package org.mudlet.android.protocol

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

interface Transport {
    val input: InputStream

    /** Unbuffered: [FrameWriter] does not flush it. */
    val output: OutputStream
    fun close()
}

/**
 * One reader thread that connects to the engine, says hello with what it already has,
 * dispatches frames, and reconnects with backoff when the engine goes away.
 * Callbacks run on the reader thread.
 */
class EngineLink(
    private val connector: () -> Transport,
    private val listener: Listener,
    // On the control socket: the UI attaches to each open profile's socket for its traffic (docs/protocol.md)
    private val profileSockets: Boolean = false,
) {
    interface Listener {
        fun onAttached(hello: EngineMessage.Hello, sameInstance: Boolean)
        fun onMessage(message: EngineMessage)
        fun onDetached(reason: String)
        fun onConnectFailed(attempt: Int, error: IOException) {}
    }

    @Volatile private var running = false

    // A stop() then start() (an Activity rotating) must not leave the old reader thread looping
    @Volatile private var generation = 0

    @Volatile private var transport: Transport? = null

    @Volatile private var writer: FrameWriter? = null

    /** The number of the connection [send] writes to, counted from 1 by each attach; set with the writer, under the lock. */
    @Volatile var connection = 0
        private set
    private var thread: Thread? = null
    private val pending = ConcurrentHashMap<Long, Pair<CountDownLatch, LongArray>>()
    private val nextId = AtomicLong(1)
    private val wakeSignal = Semaphore(0)

    // What this UI already has, so a reattach to the same engine only gets the rest
    @Volatile var instance: String? = null
        private set

    @Volatile var lastSeq: Long = 0
        private set

    val attached: Boolean get() = writer != null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val g = ++generation
        thread = Thread({ runLoop(g) }, "engine-link").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        transport?.close()
        thread?.interrupt()
        thread = null
    }

    fun send(message: JSONObject): Boolean = sendOn(message) != 0

    /** Sends [message] as [send] does, returning the number of the [connection] it went out on, or 0 when it did not. */
    fun sendOn(message: JSONObject): Int {
        val (w, n) = synchronized(this) { (writer ?: return 0) to connection }
        return try {
            w.write(message)
            n
        } catch (e: IOException) {
            transport?.close()
            0
        }
    }

    /**
     * The `id` for a request on this link, unique among every sender's: the engine copies it into its answers' `re` as it
     * came (docs/protocol.md), so ids from separate counters would let one sender take another's answer.
     */
    fun newRequestId(): Long = nextId.getAndIncrement()

    /** Round trip of one ping in nanoseconds, or null on timeout or when detached. */
    fun ping(timeoutMs: Long = 2000): Long? {
        val id = newRequestId()
        val latch = CountDownLatch(1)
        val slot = LongArray(1)
        pending[id] = latch to slot
        val t0 = System.nanoTime()
        if (!send(Requests.ping(id))) {
            pending.remove(id)
            return null
        }
        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pending.remove(id)
        return if (ok) slot[0] - t0 else null
    }

    /** Retries a failed connect now and restarts the fast retry phase, e.g. right after asking for the engine to start. */
    fun wake() {
        wakeSignal.release()
    }

    private fun live(g: Int) = running && generation == g

    private fun runLoop(g: Int) {
        var attempt = 0
        while (live(g)) {
            val t = try {
                connector()
            } catch (e: IOException) {
                if (!live(g)) break
                attempt++
                listener.onConnectFailed(attempt, e)
                if (waitForRetry(retryDelayMs(attempt))) attempt = 0
                continue
            }
            // Under the lock stop() holds: it closes the transport it finds, so one taken after it would stay open
            val taken = synchronized(this) { live(g).also { if (it) transport = t } }
            if (!taken) {
                t.close()
                break
            }
            attempt = 0
            try {
                val w = FrameWriter(t.output)
                val reader = FrameReader(t.input)
                w.write(Requests.hello(instance, lastSeq, profileSockets))
                val first = reader.read()
                if (first !is EngineMessage.Hello) {
                    throw IOException("engine answered hello with $first")
                }
                val same = first.instance == instance
                synchronized(this) {
                    // stop() may have come since the hello, and its close() need not fail this read: android.net.LocalSocket's wakes none
                    if (!live(g)) throw IOException("stopped")
                    if (!same) lastSeq = 0
                    instance = first.instance
                    connection++
                    writer = w
                }
                listener.onAttached(first, same)
                while (live(g)) {
                    val m = reader.read()
                    when (m) {
                        is EngineMessage.Line -> lastSeq = m.seq

                        is EngineMessage.Synced -> lastSeq = maxOf(lastSeq, m.seq)

                        is EngineMessage.Pong -> pending[m.id]?.let { (latch, slot) ->
                            slot[0] = System.nanoTime()
                            latch.countDown()
                        }

                        else -> {}
                    }
                    listener.onMessage(m)
                }
            } catch (e: Exception) {
                if (live(g)) listener.onDetached(e.message ?: e.javaClass.simpleName)
            } finally {
                synchronized(this) {
                    if (transport === t) {
                        writer = null
                        transport = null
                    }
                }
                t.close()
            }
        }
    }

    /** True when woken by [wake] rather than by the timeout or stop(). */
    private fun waitForRetry(ms: Long): Boolean = try {
        wakeSignal.tryAcquire(ms, TimeUnit.MILLISECONDS).also { if (it) wakeSignal.drainPermits() }
    } catch (_: InterruptedException) {
        false
    }

    companion object {
        // A killed engine is restarted by ActivityManager in ~300 ms and is ready ~100 ms later,
        // so poll briefly at a short interval before backing off to spare a long-gone engine's battery
        const val FAST_RETRY_MS = 50L
        const val FAST_RETRY_ATTEMPTS = 40
        const val MAX_RETRY_MS = 2000L

        fun retryDelayMs(attempt: Int): Long {
            if (attempt <= FAST_RETRY_ATTEMPTS) return FAST_RETRY_MS
            val doublings = (attempt - FAST_RETRY_ATTEMPTS).coerceAtMost(6)
            return (FAST_RETRY_MS shl doublings).coerceAtMost(MAX_RETRY_MS)
        }
    }
}
