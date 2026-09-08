package com.dootah

/**
 * The outcome of a manual update check.
 *
 * A closed set of outcomes rather than exceptions, so an integrating app can
 * branch on what happened without parsing message strings.
 */
sealed interface UpdateResult {

    /** A new bundle was downloaded, verified and stored. */
    data class Updated(
        val bundleVersion: Int,
        val previousBundleVersion: Int,
    ) : UpdateResult

    /** The server offers nothing newer than what is installed. */
    data class NoUpdate(
        val bundleVersion: Int,
    ) : UpdateResult

    /**
     * The publisher has switched Dootah off. The installed bundle is left on disk
     * untouched, but it will not be executed until a manifest re-enables it.
     */
    data object Disabled : UpdateResult

    /**
     * A newer bundle exists but targets a different Dootah runtime, so it was not
     * downloaded. The installed bundle keeps running.
     */
    data class IncompatibleRuntime(
        val bundleRuntimeVersion: String,
        val supportedRuntimeVersion: String,
    ) : UpdateResult

    /**
     * The check failed. The installed bundle is untouched: a failed update is
     * never a reason to discard working code.
     */
    data class Failed(
        val reason: UpdateFailure,
        val message: String,
    ) : UpdateResult
}

/** Why an update check failed, at a granularity an app can act on. */
enum class UpdateFailure {

    /** The manifest or the bundle could not be fetched. */
    NETWORK,

    /** The manifest was malformed, or failed schema validation. */
    INVALID_MANIFEST,

    /** The payload did not match the digest the manifest promised. */
    FAILED_VERIFICATION,

    /** The verified bundle could not be written to storage. */
    STORAGE,

    UNKNOWN,
}
