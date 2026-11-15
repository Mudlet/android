package org.mudlet.android.suggest

import java.io.IOException
import java.io.Reader
import java.io.StringWriter
import java.io.Writer

/** A [Worker] on a clock the test moves: [advance] runs what falls due, in order. */
internal class FakeWorker : Worker {
    var now = 0L
    private var scheduled = 0L

    private class Task(val due: Long, val order: Long, val run: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val t = Task(now + delayMillis, scheduled++, task)
        tasks += t
        return Cancellable { t.cancelled = true }
    }

    fun advance(millis: Long = 0) {
        val end = now + millis
        while (true) {
            val next = tasks.filter { !it.cancelled && it.due <= end }.minWithOrNull(compareBy({ it.due }, { it.order })) ?: break
            tasks.remove(next)
            now = next.due
            next.run()
        }
        tasks.removeAll { it.cancelled }
        now = end
    }

    val pending get() = tasks.count { !it.cancelled }
}

/** A [SuggestStore] in memory, which fails a load or a save when told to. */
internal class MemoryStore(var text: String? = null) : SuggestStore {
    var failLoad = false
    var failSave = false
    var saves = 0

    override fun <T> load(read: (Reader) -> T): T? {
        if (failLoad) throw IOException("no access")
        return text?.let { read(it.reader()) }
    }

    override fun save(write: (Writer) -> Unit) {
        if (failSave) throw IOException("disk full")
        val out = StringWriter()
        write(out)
        text = out.toString()
        saves++
    }
}
