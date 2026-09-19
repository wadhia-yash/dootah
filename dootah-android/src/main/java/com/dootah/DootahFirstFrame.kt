package com.dootah

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the first frame of the process from being the wrong one.
 *
 * An intercepted screen cannot decide synchronously what to draw. Reading the
 * active bundle and rendering a screen from it are suspending work, and Compose
 * cannot suspend a composition, so a screen's first composition necessarily
 * happens before Dootah has anything -- and what it draws then is the
 * implementation that shipped in the APK. When an active bundle is on the
 * device that implementation is out of date by definition, so the user watched
 * the old screen appear and then change: the two toolbar buttons an update had
 * removed were drawn, and then taken away again in front of them.
 *
 * The fix is not to draw that frame. Android already has the mechanism: a view
 * that declines to pre-draw holds the window on whatever preceded it -- the
 * launch theme, or the system splash -- which is the ordinary appearance of an
 * app that has not finished starting. Dootah installs the listener itself, on
 * the first activity of the process, so no host app has to call anything from
 * any activity of its own.
 *
 * Three rules keep this from becoming the blank screen it exists to prevent.
 *
 * It is installed only when a bundle is already on disk. An app with nothing to
 * load never waits, and neither does the launch straight after an install.
 *
 * It expires. [DootahConfig.firstFrameHoldMillis] is a budget, and when it runs
 * out the frame is drawn from whatever the app has -- the native
 * implementation, which is the right thing to show once Dootah has failed.
 *
 * It applies once, to the first activity of the process. That is where a stale
 * first frame is visible; every activity after it is drawn by a Dootah that is
 * already loaded.
 */
internal object DootahFirstFrame {

    private val installed = AtomicBoolean(false)

    private val gate = FirstFrameGate(Dootah::isLoadingLocalBundle)

    private var content: View? = null

    fun install(context: Context, holdMillis: Long) {

        if (holdMillis <= 0 || !installed.compareAndSet(false, true)) return

        val application = context.applicationContext as? Application ?: run {
            DootahTrace.mark("no Application to hold the first frame on")
            return
        }

        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit

            /**
             * Started, not created.
             *
             * `onActivityCreated` is dispatched from inside `Activity.onCreate`,
             * which is the *super* call at the top of the host app's own
             * `onCreate` -- before `setContent`, so there is no content view to
             * hold, and asking an AppCompat activity for one there builds its
             * decor against a theme that is not ready and throws. By
             * `onActivityStarted` the activity has its content and has still not
             * drawn a frame, which is exactly the window this needs.
             */
            override fun onActivityStarted(activity: Activity) {

                application.unregisterActivityLifecycleCallbacks(this)

                // Nothing about holding a frame is worth a crash. This runs
                // inside every host app's launch, before that app has drawn
                // anything, and it is an improvement on a path that works
                // without it.
                try {

                    // Asked of the bundle on disk rather than of the load in
                    // progress: a local load can finish before the first
                    // activity exists, and not holding then would draw the same
                    // stale frame a few milliseconds earlier.
                    if (!Dootah.expectsLocalBundle()) {
                        DootahTrace.mark("first frame not held: nothing local to draw")
                        return
                    }

                    hold(contentOf(activity), holdMillis)

                } catch (e: Exception) {
                    Log.w(DOOTAH_LOG_TAG, "first frame not held", e)
                }
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Notes that a screen has been composed and has nothing to show yet.
     *
     * Waiting only for the bundle to load is not enough: the load finishes
     * before the screens that need it have asked for anything, and releasing
     * then draws the same stale frame a moment earlier. So the frame also waits
     * for the screens of that first composition to settle -- each of them,
     * whether the bundle turns out to implement it or not.
     */
    fun awaiting(screen: Any) = gate.awaiting(screen)

    /**
     * Notes that a screen now knows what it is drawing.
     *
     * The nudge matters as much as the bookkeeping. A declined pre-draw leaves
     * nothing scheduled, so something has to ask for the frame again, and the
     * last screen to settle is exactly the right moment to ask.
     */
    fun settled(screen: Any) {
        // Also past the traversal barrier; see the frame callback in `hold`.
        if (gate.settled(screen)) content?.postOnAnimation { content?.invalidate() }
    }

    /** How many frames the hold declined, reported when it lets one through. */
    private val declined = AtomicInteger()

    /**
     * The activity's content view, asked for without disturbing the activity.
     *
     * Not `Activity.findViewById`. An `AppCompatActivity` routes that through
     * its delegate, which builds the AppCompat sub-decor on the way, and an
     * activity whose theme is not an AppCompat one -- a perfectly ordinary
     * Compose activity -- throws when it does. Dootah asking a question was
     * enough to stop IdeaMemo launching at all.
     *
     * The window's decor is the same view without the delegate in front of it,
     * and `peekDecorView` returns what is already there rather than creating
     * it, so an activity that has not set a content view yet is left alone.
     */
    private fun contentOf(activity: Activity): View? =
        activity.window?.peekDecorView()?.findViewById(android.R.id.content)

    private fun hold(view: View?, holdMillis: Long) {

        if (view == null) {
            DootahTrace.mark("first frame not held: no content view")
            return
        }

        if (!gate.beginHolding()) return

        content = view
        val deadline = SystemClock.elapsedRealtime() + holdMillis
        DootahTrace.mark("holding the first frame for the active bundle")

        // A frame callback, not a delayed message.
        //
        // Declining to pre-draw makes `ViewRootImpl` schedule another
        // traversal, and scheduling a traversal puts a synchronisation barrier
        // on the main looper that holds back every ordinary message behind it.
        // A release posted with `postDelayed` for two and a half seconds
        // therefore ran after twenty-one, and the app was frozen for all of
        // them -- the hold had become the blank screen it exists to prevent.
        // Choreographer callbacks are posted past that barrier, which is how
        // the traversal itself gets through.
        Choreographer.getInstance().postFrameCallbackDelayed(
            { release(view, "the hold expired") },
            holdMillis,
        )

        view.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {

                override fun onPreDraw(): Boolean {

                    val expired = SystemClock.elapsedRealtime() >= deadline
                    declined.incrementAndGet()

                    if (gate.isHolding && !expired && !gate.isReady()) return false

                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    release(view, if (expired) "the hold expired" else "the active bundle is ready")
                    return true
                }
            },
        )
    }

    private fun release(view: View, why: String) {

        if (!gate.release()) return

        content = null
        DootahTrace.mark("first frame released: $why, after ${declined.get()} frame(s)")
        view.postOnAnimation { view.invalidate() }
    }
}
