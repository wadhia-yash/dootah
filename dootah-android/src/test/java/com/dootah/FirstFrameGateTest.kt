package com.dootah

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule deciding whether the first frame of a process is worth waiting for.
 *
 * Both ways of getting it wrong are user-visible and neither is visible in the
 * view listener that applies it: opening early draws the stale screen the gate
 * exists to prevent, and never opening is a blank app.
 */
class FirstFrameGateTest {

    private object ScreenA
    private object ScreenB

    /** The gate asks once per frame; readiness needs two frames running. */
    private fun FirstFrameGate.readyAfterTwoFrames(): Boolean = isReady() || isReady()

    @Test
    fun `holds while the local bundle is still loading`() {

        val gate = FirstFrameGate(isLoading = { true })
        assertTrue(gate.beginHolding())

        assertFalse(gate.readyAfterTwoFrames())
    }

    @Test
    fun `holds while a composed screen has nothing to draw`() {

        val gate = FirstFrameGate(isLoading = { false })
        gate.beginHolding()
        gate.awaiting(ScreenA)

        assertFalse(gate.readyAfterTwoFrames(), "a loaded bundle is not yet a drawn screen")
    }

    @Test
    fun `is ready once the bundle is loaded and every screen has settled`() {

        val gate = FirstFrameGate(isLoading = { false })
        gate.beginHolding()
        gate.awaiting(ScreenA)
        gate.awaiting(ScreenB)

        assertFalse(gate.settled(ScreenA), "one of two screens is not the last")
        assertTrue(gate.settled(ScreenB))
        assertTrue(gate.readyAfterTwoFrames())
    }

    /**
     * A screen whose remote content holds another intercepted screen registers
     * that one only after its own content is set. The frame in between looks
     * ready and is not.
     */
    @Test
    fun `a screen that registers in the next wave is waited for`() {

        val gate = FirstFrameGate(isLoading = { false })
        gate.beginHolding()
        gate.awaiting(ScreenA)
        gate.settled(ScreenA)

        assertFalse(gate.isReady(), "one settled frame is not enough")
        gate.awaiting(ScreenB)

        assertFalse(gate.readyAfterTwoFrames(), "the second wave must be waited for")
        assertTrue(gate.settled(ScreenB))
        assertTrue(gate.readyAfterTwoFrames())
    }

    @Test
    fun `a screen composed after release is not waited for`() {

        val gate = FirstFrameGate(isLoading = { false })
        gate.beginHolding()
        gate.release()

        gate.awaiting(ScreenA)

        assertTrue(gate.readyAfterTwoFrames(), "navigating to a new screen must not blank the app")
    }

    @Test
    fun `the gate opens once and cannot be re-armed`() {

        val gate = FirstFrameGate(isLoading = { true })
        gate.beginHolding()
        assertTrue(gate.release())

        assertFalse(gate.beginHolding())
        assertFalse(gate.isHolding)
        assertFalse(gate.release(), "a second release is not a second hold")
    }

    @Test
    fun `settling a screen nobody waited for is harmless`() {

        val gate = FirstFrameGate(isLoading = { false })
        gate.beginHolding()
        gate.awaiting(ScreenA)

        assertFalse(gate.settled(ScreenB), "an unknown screen does not open the gate")
        assertTrue(gate.settled(ScreenA))
    }

    /** An expired hold releases even though nothing became ready. */
    @Test
    fun `releasing while still loading stops the hold`() {

        val gate = FirstFrameGate(isLoading = { true })
        gate.beginHolding()
        gate.awaiting(ScreenA)

        assertTrue(gate.release())
        assertFalse(gate.isHolding)
    }
}
