package com.pravah.ota

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
     * The publisher has switched Pravah off. No remote patch may be downloaded
     * or executed until the manifest says otherwise.
     */
    data class Disabled(
        val manifest: PatchManifest,
    ) : UpdateDecision

    /**
     * A newer patch exists but targets a different runtime, so it must not be
     * downloaded or executed. The installed patch keeps running.
     */
    data class Incompatible(
        val manifest: PatchManifest,
        val supportedRuntimeVersion: String,
    ) : UpdateDecision

    /** A newer, runtime-compatible patch is available. */
    data class Download(
        val manifest: PatchManifest,
    ) : UpdateDecision
}

/**
 * Decides what to do about [manifest].
 *
 * The kill switch is evaluated first and unconditionally: when the publisher has
 * switched Pravah off, no other property of the manifest can re-enable a
 * download.
 *
 * Version is then checked before compatibility. If the server is not offering
 * anything newer there is nothing to decide, and reporting
 * [UpdateDecision.Incompatible] for a patch we would not have installed anyway
 * would log a migration warning on every single update check.
 *
 * Only a strictly greater patch version is accepted, so a rolled-back or
 * replayed manifest cannot walk the installed patch backwards.
 */
fun decideUpdate(
    manifest: PatchManifest,
    installedPatchVersion: Int,
    supportedRuntimeVersion: String = PRAVAH_RUNTIME_VERSION,
): UpdateDecision {

    if (!manifest.enabled) {
        return UpdateDecision.Disabled(manifest)
    }

    if (manifest.patchVersion <= installedPatchVersion) {
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
