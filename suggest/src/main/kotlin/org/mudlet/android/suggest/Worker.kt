package org.mudlet.android.suggest

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Runs tasks one at a time, in the order they fall due, on one thread of its own. */
interface Worker {
    /** Runs [task] [delayMillis] from now, after the tasks due before it; the result cancels it until it begins. */
    fun schedule(delayMillis: Long, task: () -> Unit): Cancellable
}

fun interface Cancellable {
    fun cancel()
}

/**
 * A [Worker] on a daemon thread named [name]. A task that throws is reported to [onFailure] and the next one runs.
 * Tasks that fall due together run in the order they were scheduled.
 */
class ExecutorWorker(name: String, private val onFailure: (Throwable) -> Unit) : Worker {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, name).apply { isDaemon = true } }.apply {
        removeOnCancelPolicy = true
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val future = executor.schedule({
            try {
                task()
            } catch (e: Exception) {
                onFailure(e)
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }
}
