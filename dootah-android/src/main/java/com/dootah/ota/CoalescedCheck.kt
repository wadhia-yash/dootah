package com.dootah.ota

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One answer, however many callers ask for it.
 *
 * Every intercepted screen asks whether there is an update when it appears,
 * because any of them could be the one a kill switch has to reach and no screen
 * can know what its neighbours already did. An app drawing forty screens does
 * not need forty round trips, and a list whose rows enter composition as they
 * scroll does not need one per row.
 *
 * Serialising them was worse than wasteful: each waited for the one before, so
 * the burst from a first composition became a queue of hundreds of requests
 * that went on being answered long after the screens had drawn. One launch of a
 * real app made three hundred requests in thirty seconds, of which one was
 * useful.
 *
 * So callers that overlap share the run in flight, and a caller arriving within
 * [quietPeriodMillis] of the last answer is given that answer. Both are
 * truthful: the shared run is the same check the caller would have made, and
 * the reused answer is one from within a window the caller could not have
 * distinguished anyway.
 *
 * [now] is a monotonic clock -- elapsed real time, not wall clock, so setting
 * the device's clock cannot make an answer look fresh forever.
 */
internal class CoalescedCheck<T>(
    private val scope: CoroutineScope,
    private val quietPeriodMillis: Long,
    private val now: () -> Long,
    private val check: suspend () -> T,
) {

    private val lock = Mutex()

    private var inFlight: Deferred<T>? = null

    // Written by the run that produced them, read under the lock by the next
    // caller. Volatile rather than locked, because the run holds no lock and
    // taking one from inside it would depend on how its scope dispatches.
    @Volatile private var answer: T? = null
    @Volatile private var answeredAt: Long? = null

    suspend fun run(): T {

        val shared = lock.withLock {

            val running = inFlight?.takeIf { it.isActive }
            val age = answeredAt?.let { now() - it }

            when {
                running != null -> running

                age != null && age < quietPeriodMillis ->
                    @Suppress("UNCHECKED_CAST") return answer as T

                else -> scope.async { record(check()) }.also { inFlight = it }
            }
        }

        return shared.await()
    }

    private fun record(result: T): T {
        answer = result
        answeredAt = now()
        return result
    }
}
