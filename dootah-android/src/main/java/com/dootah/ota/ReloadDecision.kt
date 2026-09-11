package com.dootah.ota

import com.dootah.FallbackReason
import com.dootah.UpdateResult

/**
 * Whether a screen must reload after an update check.
 *
 * Reloading only for a downloaded update, which is what Dootah used to do, made
 * the kill switch a restart-only feature: a manifest that switched Dootah off
 * left the remote implementation on screen until the process died. The kill
 * switch is the recovery path for a bad bundle, so it has to take effect on the
 * screen the user is looking at.
 *
 * A pure function of the three things that decide it, so the rule can be tested
 * without a device, a manifest server or a running isolate.
 */
internal fun shouldReloadAfterCheck(
    result: UpdateResult,
    isShowingRemote: Boolean,
    currentFallbackReason: FallbackReason?,
    isRemotelyDisabled: Boolean,
): Boolean = when {

    // Something newer was installed, whatever is on screen now.
    result is UpdateResult.Updated -> true

    // The kill switch arrived while remote content is showing. This is the case
    // the old rule missed.
    isRemotelyDisabled && isShowingRemote -> true

    // Dootah was switched back on and the screen is native only because it had
    // been switched off. Without this the recovery would itself need a restart.
    !isRemotelyDisabled && currentFallbackReason == FallbackReason.DISABLED -> true

    // A check that changes nothing must not restart a working isolate.
    else -> false
}
