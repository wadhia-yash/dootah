package com.dootah.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * A context whose service connections are delivered off the main thread.
 *
 * `Context.bindService(intent, connection, flags)` delivers `onServiceConnected`
 * on the application's main thread, and that is the overload the JavaScript
 * sandbox binds with. So connecting to the sandbox is not merely asynchronous:
 * it cannot finish until the host app's main thread is free to run one more
 * message.
 *
 * Which is precisely when it is least likely to be. Dootah starts the sandbox
 * during application startup, so that an already-active bundle is ready before
 * the first screen composes -- and application startup is exactly the window in
 * which a real app saturates its main thread. Measured on a mid-range device,
 * IdeaMemo's own cold start held the main thread at 98% for seventeen seconds,
 * and the sandbox connection, requested nine milliseconds in, completed at
 * nineteen. The load itself took forty milliseconds once it could begin.
 *
 * Android has an overload that takes an executor. The sandbox does not use it,
 * but the context it binds through is Dootah's to choose, so this one answers
 * `bindService` with that overload and hands the callbacks to a thread of its
 * own. Nothing else changes: the same connection object is registered with the
 * same underlying context, so unbinding, and every other context operation,
 * goes through untouched.
 *
 * Before API 29 there is no such overload and the wrapper is transparent: the
 * connection is delivered on the main thread as before, which is slower on a
 * busy start but no worse than not wrapping it.
 */
internal class OffMainThreadBinding(base: Context) : ContextWrapper(base) {

    override fun bindService(
        service: Intent,
        connection: ServiceConnection,
        flags: Int,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            baseContext.bindService(service, flags, callbacks, connection)
        } else {
            super.bindService(service, connection, flags)
        }

    private companion object {

        /**
         * One thread, shared by every binding, named so it is recognisable in a
         * trace. Service callbacks are short and must not run concurrently with
         * each other, which is what the main thread guaranteed and a single
         * thread restores.
         */
        val callbacks: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dootah-sandbox-binding").apply { isDaemon = true }
        }
    }
}
