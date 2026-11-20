package org.mudlet.android.ui

import android.os.Looper
import android.util.StringBuilderPrinter
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.runners.model.TestTimedOutException
import java.util.concurrent.TimeUnit

/**
 * Fails a test that runs past [ms], the rules inside this one included, with where the main thread is: launching an
 * Activity waits for the main thread to go idle, so a launch that never returns is stuck there, not in the test.
 * AndroidJUnitRunner's own timeout covers the test method alone and gives the test thread's stack.
 */
class MainThreadTimeout(ms: Long) : TestRule {
    private val timeout = Timeout.builder().withTimeout(ms, TimeUnit.MILLISECONDS).build()

    override fun apply(base: Statement, description: Description): Statement {
        val timed = timeout.apply(base, description)
        return object : Statement() {
            override fun evaluate() {
                try {
                    timed.evaluate()
                } catch (e: TestTimedOutException) {
                    val main = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "\tat $it" }
                    val queue = StringBuilder().also { Looper.getMainLooper().dump(StringBuilderPrinter(it), "\t") }
                    val others = Thread.getAllStackTraces().filterKeys { it != Looper.getMainLooper().thread }
                        .filterValues { stack -> stack.any { it.className.startsWith("org.mudlet") } }
                        .entries.joinToString("\n") { (thread, stack) -> "\"${thread.name}\":\n" + stack.joinToString("\n") { "\tat $it" } }
                    throw AssertionError("${e.message}; the main thread is at:\n$main\nits queue:\n$queue\nthe test's threads:\n$others", e)
                }
            }
        }
    }
}
