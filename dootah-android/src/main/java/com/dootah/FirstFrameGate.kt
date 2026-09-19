package com.dootah

import java.util.Collections

/**
 * Decides whether the first frame is still worth waiting for.
 *
 * Separated from the Android wiring in [DootahFirstFrame] because the rule is
 * where the mistakes are: a gate that opens too early draws the stale frame it
 * exists to prevent, and one that never closes is a blank screen. Neither
 * failure is visible in a view listener, and both are visible here.
 *
 * The gate holds while either the local bundle is still loading or a screen
 * that has been composed does not yet know what it is drawing. It opens once
 * and never closes again: the stale frame only exists at the start of a
 * process, and a gate that could re-arm would be able to blank a running app.
 */
internal class FirstFrameGate(private val isLoading: () -> Boolean) {

    private val pending = Collections.synchronizedSet(mutableSetOf<Any>())

    @Volatile
    var isHolding: Boolean = false
        private set

    /** True from the first call; later calls cannot re-arm an opened gate. */
    @Volatile
    private var opened = false

    fun beginHolding(): Boolean {
        if (opened) return false
        isHolding = true
        return true
    }

    /** Notes a screen that has been composed and has nothing to show yet. */
    fun awaiting(screen: Any) {
        if (isHolding) pending += screen
    }

    /** Notes a screen that now knows what it draws; true when that was the last. */
    fun settled(screen: Any): Boolean {
        if (!isHolding) return false
        pending -= screen
        return pending.isEmpty()
    }

    /**
     * Whether the frame can be let through, asked once per frame.
     *
     * Ready for two frames running, not one. Screens arrive in waves: a screen
     * whose remote content contains another intercepted screen only composes
     * that one after its own content is set, so there is a moment when
     * everything known has settled and the next wave has not registered yet.
     * Releasing in that moment drew a toolbar from the APK half a second before
     * the update replaced it, which is the exact frame this gate exists to
     * skip. A second frame costs nothing and lets the next wave sign up.
     */
    fun isReady(): Boolean {
        if (isLoading() || pending.isNotEmpty()) {
            settledFrames = 0
            return false
        }
        settledFrames++
        return settledFrames >= 2
    }

    private var settledFrames = 0

    /** Opens the gate. True the first time, so a caller can report why. */
    fun release(): Boolean {
        if (!isHolding) return false
        isHolding = false
        opened = true
        pending.clear()
        return true
    }
}
