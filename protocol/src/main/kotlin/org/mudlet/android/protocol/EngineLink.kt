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
    val output: OutputStream
    fun close()
}

/**
 * One reader thread that connects to the engine, says hello with what it already has,
 * dispatches frames, and reconnects with backoff when the engine goes away.
 * Callbacks run on the reader thread.
 */
class EngineLink(private val connector: () -> Transport, private val listener: Listener) {
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

    fun send(message: JSONObject): Boolean {
        val w = writer ?: return false
        return try {
            w.write(message)
            true
        } catch (e: IOException) {
            transport?.close()
            false
        }
    }

    /** Round trip of one ping in nanoseconds, or null on timeout or when detached. */
    fun ping(timeoutMs: Long = 2000): Long? {
        val id = nextId.getAndIncrement()
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
            if (!live(g)) {
                t.close()
                break
            }
            transport = t
            attempt = 0
            try {
                val w = FrameWriter(t.output)
                val reader = FrameReader(t.input)
                w.write(Requests.hello(instance, lastSeq))
                val first = reader.read()
                if (first !is EngineMessage.Hello) {
                    throw IOException("engine answered hello with $first")
                }
                val same = first.instance == instance
                if (!same) lastSeq = 0
                instance = first.instance
                writer = w
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
