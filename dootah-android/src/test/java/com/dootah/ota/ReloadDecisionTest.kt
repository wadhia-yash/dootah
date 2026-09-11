package com.dootah.ota

import com.dootah.FallbackReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two failures shaped this rule, and both are pinned here.
 *
 * The kill switch has to take effect on the screen the user is looking at, not
 * on the next launch -- it is the recovery path for a bad bundle. And a screen
 * has to pick up a bundle another screen's check downloaded, because with
 * several screens on display only one of their checks can be the one that finds
 * the update.
 */
class ReloadDecisionTest {

    @Test
    fun `reloads when the kill switch arrives while remote content is showing`() {

        assertTrue(
            shouldReloadAfterCheck(
                isShowingRemote = true,
                currentFallbackReason = null,
                isRemotelyDisabled = true,
                loadedBundleVersion = 5,
                installedBundleVersion = 5,
            )
        )
    }

    @Test
    fun `reloads when Dootah is switched back on`() {

        assertTrue(
            shouldReloadAfterCheck(
                isShowingRemote = false,
                currentFallbackReason = FallbackReason.DISABLED,
                isRemotelyDisabled = false,
                loadedBundleVersion = 5,
                installedBundleVersion = 5,
            )
        )
    }

    @Test
    fun `reloads when a newer bundle is installed`() {

        assertTrue(
            shouldReloadAfterCheck(
                isShowingRemote = true,
                currentFallbackReason = null,
                isRemotelyDisabled = false,
                loadedBundleVersion = 5,
                installedBundleVersion = 6,
            )
        )
    }

    @Test
    fun `reloads a screen whose own check found nothing because another screen downloaded it`() {

        // The case the first version of this rule got wrong. Only one screen's
        // check can be the one that downloads; every other screen is told there
        // is no update, and stayed native until the next launch.
        assertTrue(
            shouldReloadAfterCheck(
                isShowingRemote = false,
                currentFallbackReason = FallbackReason.NO_BUNDLE_AVAILABLE,
                isRemotelyDisabled = false,
                loadedBundleVersion = 1,
                installedBundleVersion = 2,
            )
        )
    }

    @Test
    fun `does not reload when nothing changed`() {

        assertFalse(
            shouldReloadAfterCheck(
                isShowingRemote = true,
                currentFallbackReason = null,
                isRemotelyDisabled = false,
                loadedBundleVersion = 5,
                installedBundleVersion = 5,
            )
        )
    }

    @Test
    fun `does not reload a screen that is native for a reason the check cannot fix`() {

        // Nothing has been downloaded, so reloading would restart the isolate on
        // every check for a screen that will still have no bundle afterwards.
        assertFalse(
            shouldReloadAfterCheck(
                isShowingRemote = false,
                currentFallbackReason = FallbackReason.NO_BUNDLE_AVAILABLE,
                isRemotelyDisabled = false,
                loadedBundleVersion = 1,
                installedBundleVersion = 1,
            )
        )
    }

    @Test
    fun `does not reload when the kill switch is on and native is already showing`() {

        assertFalse(
            shouldReloadAfterCheck(
                isShowingRemote = false,
                currentFallbackReason = FallbackReason.DISABLED,
                isRemotelyDisabled = true,
                loadedBundleVersion = 5,
                installedBundleVersion = 5,
            )
        )
    }
}
