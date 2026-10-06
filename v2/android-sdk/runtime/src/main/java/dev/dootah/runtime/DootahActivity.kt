package dev.dootah.runtime

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import java.util.WeakHashMap

/** Optional compatibility base. Frame acknowledgement no longer depends on it or on AppCompat. */
open class DootahActivity : AppCompatActivity() {
    val dootah get() = application as DootahApplication
}

/**
 * Embedded/native frame acknowledgement for any Activity (ComponentActivity, AppCompatActivity
 * or a platform-themed Activity). Registered once by DootahApplication; main thread only.
 */
internal class NativeFrameObserver(private val dootah: DootahApplication) : Application.ActivityLifecycleCallbacks {
    private val tracked = WeakHashMap<View, Unit>()
    fun invalidate() = tracked.keys.toList().forEach { it.postInvalidateOnAnimation() }
    private fun track(activity: Activity) {
        // peekDecorView never installs a decor early, so window features stay under app control.
        val view = activity.window?.peekDecorView() ?: return
        if (tracked.put(view, Unit) != null) return
        val frame = ViewTreeObserver.OnDrawListener {
            val value = dootah.value.value
            if (value.embedded && value.updateId != null && view.isHardwareAccelerated) {
                view.viewTreeObserver.registerFrameCommitCallback {
                    view.post { dootah.onFrameCommitted(value) }
                }
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = v.viewTreeObserver.addOnDrawListener(frame)
            override fun onViewDetachedFromWindow(v: View) = v.viewTreeObserver.removeOnDrawListener(frame)
        })
        if (view.isAttachedToWindow) view.viewTreeObserver.addOnDrawListener(frame)
        view.postInvalidateOnAnimation()
    }
    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) = track(activity)
    override fun onActivityStarted(activity: Activity) = track(activity)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

/** Attach to the native content that actually renders this value. */
@Composable
fun Modifier.dootahFrame(value: RenderValue): Modifier {
    val view = LocalView.current
    val runtime = view.context.applicationContext as DootahApplication
    return drawWithContent {
        drawContent()
        if (view.isHardwareAccelerated && value.updateId != null) {
            view.viewTreeObserver.registerFrameCommitCallback {
                view.post { runtime.onFrameCommitted(value) }
            }
        }
    }
}
