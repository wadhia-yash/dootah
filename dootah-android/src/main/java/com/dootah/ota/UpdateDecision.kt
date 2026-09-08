package com.dootah.ota

/**
 * What the update pipeline should do about a manifest it has just parsed.
 *
 * Keeping this decision separate from the code that performs it means the rules
 * are a pure function of two values and can be tested without a device, a
 * network, or a filesystem.
 */
sealed interface UpdateDecision {

    /** The server offers nothing newer than what is already installed. */
    data object UpToDate : UpdateDecision

    /**
     * The publisher has switched Dootah off. No remote bundle may be downloaded
     * or executed until the manifest says otherwise.
     */
    data class Disabled(
        val manifest: BundleManifest,
    ) : UpdateDecision

    /**
     * A newer bundle exists but targets a different runtime, so it must not be
     * downloaded or executed. The installed bundle keeps running.
     */
    data class Incompatible(
        val manifest: BundleManifest,
        val supportedRuntimeVersion: String,
    ) : UpdateDecision

    /** A newer, runtime-compatible bundle is available. */
    data class Download(
        val manifest: BundleManifest,
    ) : UpdateDecision
}

/**
 * Decides what to do about [manifest].
 *
 * The kill switch is evaluated first and unconditionally: when the publisher has
 * switched Dootah off, no other property of the manifest can re-enable a
 * download.
 *
 * Version is then checked before compatibility. If the server is not offering
 * anything newer there is nothing to decide, and reporting
 * [UpdateDecision.Incompatible] for a bundle we would not have installed anyway
 * would log a migration warning on every single update check.
 *
 * Only a strictly greater bundle version is accepted, so a rolled-back or
 * replayed manifest cannot walk the installed bundle backwards.
 */
fun decideUpdate(
    manifest: BundleManifest,
    installedBundleVersion: Int,
    supportedRuntimeVersion: String = DOOTAH_RUNTIME_VERSION,
): UpdateDecision {

    if (!manifest.enabled) {
        return UpdateDecision.Disabled(manifest)
    }

    if (manifest.bundleVersion <= installedBundleVersion) {
        return UpdateDecision.UpToDate
    }

    if (!isRuntimeCompatible(manifest, supportedRuntimeVersion)) {
        return UpdateDecision.Incompatible(
            manifest = manifest,
            supportedRuntimeVersion = supportedRuntimeVersion,
        )
    }

    return UpdateDecision.Download(manifest)
}
