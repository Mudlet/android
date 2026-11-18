package org.mudlet.android.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.Executors

/** Where a composition's coroutine goes on after its work in the background. */
class InBackgroundTest {
    @Test
    fun aCoroutineThatGoesOnWhereverItIsResumedGoesOnInTheMainThread() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, MAIN) }
        val main = executor.asCoroutineDispatcher()
        try {
            val (worker, after) = runBlocking(main) {
                // As the Compose test rule's dispatcher, which dispatches nothing
                withContext(Dispatchers.Unconfined) {
                    val worker = inBackground(main) { Thread.currentThread().name }
                    worker to Thread.currentThread().name
                }
            }
            assertNotEquals(MAIN, worker)
            assertEquals(MAIN, after)
        } finally {
            executor.shutdown()
        }
    }

    private companion object {
        const val MAIN = "main"
    }
}
