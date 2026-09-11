package com.dootah.ota

import com.dootah.FallbackReason

/**
 * Whether a screen must reload after an update check.
 *
 * Decided by what is installed, not by what the check returned. Two things made
 * that necessary.
 *
 * Reloading only for a downloaded update made the kill switch a restart-only
 * control: a manifest that switched Dootah off left the remote implementation on
 * screen until the process died, which is the wrong property for the mechanism
 * that exists to stop a bad bundle.
 *
 * And with several screens on display, only one of their checks does the
 * downloading -- the rest are told there is nothing new, because by the time
 * they look there isn't. Keying on the result meant every other screen on the
 * page stayed native until the next launch. Comparing versions instead asks the
 * question that actually matters: is the bundle this screen drew from still the
 * one that is installed?
 *
 * A pure function of the four things that decide it, so the rule can be tested
 * without a device, a manifest server or a running isolate.
 */
internal fun shouldReloadAfterCheck(
    isShowingRemote: Boolean,
    currentFallbackReason: FallbackReason?,
    isRemotelyDisabled: Boolean,
    loadedBundleVersion: Int,
    installedBundleVersion: Int,
): Boolean = when {

    // The kill switch is on. Only a screen still showing remote content has
    // anything to do about it.
    isRemotelyDisabled -> isShowingRemote

    // Dootah was switched back on and the screen is native only because it had
    // been switched off. Without this the recovery would itself need a restart.
    currentFallbackReason == FallbackReason.DISABLED -> true

    // A different bundle is installed than the one this screen drew from --
    // whether this screen's own check downloaded it or another screen's did.
    else -> loadedBundleVersion != installedBundleVersion
}
