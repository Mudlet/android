package org.mudlet.android.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.Executors

/** Where a composition's coroutine goes on after its work in the background. */
class InBackgroundTest {
    @Test
    fun aCoroutineThatGoesOnWhereverItIsResumedGoesOnInTheMainThread() {
        lateinit var mainThread: Thread
        val executor = Executors.newSingleThreadExecutor { Thread(it, "main").also { t -> mainThread = t } }
        val main = executor.asCoroutineDispatcher()
        try {
            // Threads, not their names: Gradle runs tests with assertions on, which puts kotlinx.coroutines in debug
            // mode, and that adds " @coroutine#n" to a thread's name while a coroutine runs in it
            val (worker, after) = runBlocking(main) {
                // As the Compose test rule's dispatcher, which dispatches nothing
                withContext(Dispatchers.Unconfined) {
                    val worker = inBackground(main) { Thread.currentThread() }
                    worker to Thread.currentThread()
                }
            }
            assertNotSame(mainThread, worker)
            assertSame(mainThread, after)
        } finally {
            executor.shutdown()
        }
    }
}
