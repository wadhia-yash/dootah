package expo.modules.updates.errorrecovery

import com.facebook.react.devsupport.interfaces.DevSupportManager
import expo.modules.updates.logging.UpdatesLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NativeHealthTest {
    @Test fun nativeOnlyMonitoringDoesNotTrustReactMarkers() {
        val recovery = ErrorRecovery(mockk<UpdatesLogger>(relaxed = true))
        val delegate = mockk<ErrorRecoveryDelegate>(relaxed = true)
        recovery.initialize(delegate)
        recovery.startNativeMonitoring()
        try {
            com.facebook.react.bridge.ReactMarker.logMarker(com.facebook.react.bridge.ReactMarkerConstants.CONTENT_APPEARED)
            shadowOf(recovery.handlerThread.looper).idle()
            verify(exactly = 0) { delegate.markSuccessfulLaunchForLaunchedUpdate() }
            assertTrue(recovery.handleContentAppeared())
            shadowOf(recovery.handlerThread.looper).idle()
            verify(exactly = 1) { delegate.markSuccessfulLaunchForLaunchedUpdate() }
        } finally {
            shadowOf(recovery.handlerThread.looper).idleFor(java.time.Duration.ofSeconds(10))
            recovery.handlerThread.quitSafely()
        }
    }

    @Test fun nativeFrameRequiresMonitoringAndAcknowledgesOnlyOnce() {
        val recovery = ErrorRecovery(mockk<UpdatesLogger>(relaxed = true))
        val delegate = mockk<ErrorRecoveryDelegate>(relaxed = true)
        assertFalse(recovery.handleContentAppeared())
        recovery.initialize(delegate)
        try {
            assertFalse(recovery.handleContentAppeared())
            recovery.startMonitoring(mockk<DevSupportManager>(relaxed = true))
            assertTrue(recovery.handleContentAppeared())
            assertTrue(recovery.handleContentAppeared())
            shadowOf(recovery.handlerThread.looper).idle()
            verify(exactly = 1) { delegate.markSuccessfulLaunchForLaunchedUpdate() }
            verify(exactly = 0) { delegate.markFailedLaunchForLaunchedUpdate() }
        } finally {
            shadowOf(recovery.handlerThread.looper).idleFor(java.time.Duration.ofSeconds(10))
            recovery.handlerThread.quitSafely()
        }
    }
    @Test fun nativeFrameRejectsMissingStaleAndEmergencyLaunches() {
        val procedure = expo.modules.updates.procedures.StartupProcedure(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            java.io.File("unused"), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true)
        )
        val id = java.util.UUID.randomUUID()
        assertFalse(procedure.onNativeContentRendered(id))
        val update = mockk<expo.modules.updates.db.entity.UpdateEntity>()
        every { update.id } returns id
        val launcher = mockk<expo.modules.updates.launcher.Launcher>()
        every { launcher.launchedUpdate } returns update
        procedure.setLauncher(launcher)
        assertFalse(procedure.onNativeContentRendered(java.util.UUID.randomUUID()))
        val recoveryField = procedure.javaClass.getDeclaredField("errorRecovery").apply { isAccessible = true }
        val recovery = recoveryField.get(procedure) as ErrorRecovery
        val delegate = mockk<ErrorRecoveryDelegate>(relaxed = true)
        recovery.initialize(delegate)
        recovery.startMonitoring(mockk<DevSupportManager>(relaxed = true))
        try {
            val emergency = procedure.javaClass.getDeclaredField("emergencyLaunchException").apply { isAccessible = true }
            emergency.set(procedure, RuntimeException("emergency"))
            assertFalse(procedure.onNativeContentRendered(id))
            emergency.set(procedure, null)
            assertTrue(procedure.onNativeContentRendered(id))
            shadowOf(recovery.handlerThread.looper).idle()
            verify(exactly = 1) { delegate.markSuccessfulLaunchForLaunchedUpdate() }
        } finally {
            shadowOf(recovery.handlerThread.looper).idleFor(java.time.Duration.ofSeconds(10))
            recovery.handlerThread.quitSafely()
        }
    }

}
