package com.dootah.ota

import com.dootah.FallbackReason
import com.dootah.UpdateResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kill switch has to take effect on the screen the user is looking at.
 *
 * Dootah used to reload only when an update had been downloaded, which made
 * `enabled: false` a restart-only control -- the worst possible property for the
 * mechanism that exists to stop a bad bundle. These pin the rule that fixed it.
 */
class ReloadDecisionTest {

    @Test
    fun `reloads when the kill switch arrives while remote content is showing`() {

        assertTrue(
            shouldReloadAfterCheck(
                result = UpdateResult.Disabled,
                isShowingRemote = true,
                currentFallbackReason = null,
                isRemotelyDisabled = true,
            )
        )
    }

    @Test
    fun `reloads when a check reports no update but the app has been switched off`() {

        // The kill switch is persisted, so a later check can report NoUpdate
        // while the app is disabled. The reason to reload is the disabled flag,
        // not the shape of the check's result.
        assertTrue(
            shouldReloadAfterCheck(
                result = UpdateResult.NoUpdate(bundleVersion = 5),
                isShowingRemote = true,
                currentFallbackReason = null,
                isRemotelyDisabled = true,
            )
        )
    }

    @Test
    fun `reloads when Dootah is switched back on`() {

        assertTrue(
            shouldReloadAfterCheck(
                result = UpdateResult.NoUpdate(bundleVersion = 5),
                isShowingRemote = false,
                currentFallbackReason = FallbackReason.DISABLED,
                isRemotelyDisabled = false,
            )
        )
    }

    @Test
    fun `reloads when an update was installed`() {

        assertTrue(
            shouldReloadAfterCheck(
                result = UpdateResult.Updated(bundleVersion = 6, previousBundleVersion = 5),
                isShowingRemote = true,
                currentFallbackReason = null,
                isRemotelyDisabled = false,
            )
        )
    }

    @Test
    fun `does not reload when nothing changed`() {

        assertFalse(
            shouldReloadAfterCheck(
                result = UpdateResult.NoUpdate(bundleVersion = 5),
                isShowingRemote = true,
                currentFallbackReason = null,
                isRemotelyDisabled = false,
            )
        )
    }

    @Test
    fun `does not reload a screen that is native for a reason the check cannot fix`() {

        // Nothing has been downloaded, so reloading would restart the isolate on
        // every check for a screen that will still have no bundle afterwards.
        assertFalse(
            shouldReloadAfterCheck(
                result = UpdateResult.NoUpdate(bundleVersion = 1),
                isShowingRemote = false,
                currentFallbackReason = FallbackReason.NO_BUNDLE_AVAILABLE,
                isRemotelyDisabled = false,
            )
        )
    }

    @Test
    fun `does not reload when the kill switch is on and native is already showing`() {

        assertFalse(
            shouldReloadAfterCheck(
                result = UpdateResult.Disabled,
                isShowingRemote = false,
                currentFallbackReason = FallbackReason.DISABLED,
                isRemotelyDisabled = true,
            )
        )
    }
}
