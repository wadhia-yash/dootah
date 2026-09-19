package com.dootah

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A timeline of Dootah's startup, measured rather than guessed.
 *
 * Startup is the one part of Dootah a host app cannot instrument for itself:
 * everything between `initialize` and the first frame happens inside library
 * code, on dispatchers the app does not own. When that path is slow -- a cold
 * sandbox, a large bundle, a control plane that is not answering -- the app sees
 * only a delay, and the difference between "the network is hanging" and "the
 * isolate is still evaluating" is not something a stack trace after the fact can
 * recover.
 *
 * So each stage reports when it began and how long it took, relative to the
 * first mark of the process. Elapsed real time, which does not move when the
 * clock is set and does not stop when the device sleeps.
 *
 * Every mark is one line at `Log.i` under the ordinary Dootah tag. They are
 * bounded: the phases below happen once per process, and the per-screen marks
 * report only the first screen and then count.
 */
internal object DootahTrace {

    private val origin = AtomicLong(0)

    /** So "the first screen" is reported once rather than by whichever raced. */
    private val firstScreen = AtomicBoolean(false)
    private val firstFrame = AtomicBoolean(false)

    private fun now(): Long = SystemClock.elapsedRealtimeNanos()

    /** Starts the timeline, at the first thing Dootah does in this process. */
    fun begin(event: String) {
        origin.compareAndSet(0, now())
        mark(event)
    }

    fun mark(event: String) {
        val start = origin.get()
        if (start == 0L) return
        Log.i(DOOTAH_LOG_TAG, "trace ${millisSince(start)}ms  $event")
    }

    /** Marks [event] with how long it took as well as when it finished. */
    inline fun <T> timed(event: String, block: () -> T): T {
        val began = beganAt()
        return try {
            block()
        } finally {
            finished(event, began)
        }
    }

    /** Public for [timed]'s inline body; not part of the trace vocabulary. */
    fun beganAt(): Long = now()

    fun finished(event: String, began: Long) {
        val start = origin.get()
        if (start == 0L) return
        val took = (now() - began) / 1_000_000
        Log.i(DOOTAH_LOG_TAG, "trace ${millisSince(start)}ms  $event (took ${took}ms)")
    }

    /**
     * Times one repeated step, when someone has asked for that detail.
     *
     * A screen render happens once per intercepted composable, which is dozens of
     * times in an app's first composition. Worth measuring while diagnosing a
     * slow start, not worth a line each in an ordinary log, so it is written at
     * debug and costs nothing when debug is off.
     */
    inline fun <T> detail(event: String, block: () -> T): T {
        if (!Log.isLoggable(DOOTAH_LOG_TAG, Log.DEBUG)) return block()
        val began = beganAt()
        return try {
            block()
        } finally {
            detailed(event, began)
        }
    }

    /** Public for [detail]'s inline body; not part of the trace vocabulary. */
    fun detailed(event: String, began: Long) {
        val start = origin.get()
        if (start == 0L) return
        val took = (now() - began) / 1_000_000
        Log.d(DOOTAH_LOG_TAG, "trace ${millisSince(start)}ms  $event (took ${took}ms)")
    }

    /**
     * Marks the first intercepted composable of the process, and only that one.
     *
     * An app draws dozens of intercepted screens in its first composition, and a
     * line each would bury the phases they are meant to be read against.
     */
    fun firstInterceptedComposable(screenId: String) {
        if (firstScreen.compareAndSet(false, true)) mark("first intercepted composable: $screenId")
    }

    /** Marks the first frame Dootah put remote content into. */
    fun firstRemoteFrame(screenId: String) {
        if (firstFrame.compareAndSet(false, true)) mark("first remote frame: $screenId")
    }

    private fun millisSince(start: Long): Long = (now() - start) / 1_000_000
}
