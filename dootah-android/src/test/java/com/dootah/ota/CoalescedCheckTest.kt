package com.dootah.ota

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * The rule that turned a first composition's burst of update checks back into
 * one request.
 *
 * Stated over a counter and a clock rather than over the network, because what
 * matters is how many times the work runs and when, not what it does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalescedCheckTest {

    @Test
    fun `callers that overlap share one run`() = runTest {

        val runs = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val check = CoalescedCheck(TestScope(testScheduler), 1_000, { 0L }) {
            runs.incrementAndGet(); gate.await(); "answer"
        }

        val callers = (1..40).map { async { check.run() } }
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals(List(40) { "answer" }, callers.awaitAll())
        assertEquals(1, runs.get())
    }

    @Test
    fun `a caller inside the quiet period is given the last answer`() = runTest {

        val runs = AtomicInteger()
        var clock = 0L
        val check = CoalescedCheck(TestScope(testScheduler), 1_000, { clock }) {
            "answer ${runs.incrementAndGet()}"
        }

        assertEquals("answer 1", check.run())
        clock = 999
        assertEquals("answer 1", check.run())
        assertEquals(1, runs.get())
    }

    @Test
    fun `a caller after the quiet period runs the check again`() = runTest {

        val runs = AtomicInteger()
        var clock = 0L
        val check = CoalescedCheck(TestScope(testScheduler), 1_000, { clock }) {
            "answer ${runs.incrementAndGet()}"
        }

        assertEquals("answer 1", check.run())
        clock = 1_000
        assertEquals("answer 2", check.run())
        assertEquals(2, runs.get())
    }

    /**
     * A kill switch is published while the app is running. The quiet period may
     * delay the answer; it must not be able to withhold it.
     */
    @Test
    fun `an answer that changes is not hidden by an earlier one`() = runTest {

        var current = "enabled"
        var clock = 0L
        val check = CoalescedCheck(TestScope(testScheduler), 1_000, { clock }) { current }

        assertEquals("enabled", check.run())
        current = "disabled"
        clock = 1_500
        assertEquals("disabled", check.run())
    }
}
