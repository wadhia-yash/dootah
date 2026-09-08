package com.pravah

/**
 * The outcome of a manual update check.
 *
 * A closed set of outcomes rather than exceptions, so an integrating app can
 * branch on what happened without parsing message strings.
 */
sealed interface UpdateResult {

    /** A new patch was downloaded, verified and stored. */
    data class Updated(
        val patchVersion: Int,
        val previousPatchVersion: Int,
    ) : UpdateResult

    /** The server offers nothing newer than what is installed. */
    data class NoUpdate(
        val patchVersion: Int,
    ) : UpdateResult

    /**
     * The publisher has switched Pravah off. The installed patch is left on disk
     * untouched, but it will not be executed until a manifest re-enables it.
     */
    data object Disabled : UpdateResult

    /**
     * A newer patch exists but targets a different Pravah runtime, so it was not
     * downloaded. The installed patch keeps running.
     */
    data class IncompatibleRuntime(
        val patchRuntimeVersion: String,
        val supportedRuntimeVersion: String,
    ) : UpdateResult

    /**
     * The check failed. The installed patch is untouched: a failed update is
     * never a reason to discard working code.
     */
    data class Failed(
        val reason: UpdateFailure,
        val message: String,
    ) : UpdateResult
}

/** Why an update check failed, at a granularity an app can act on. */
enum class UpdateFailure {

    /** The manifest or the patch could not be fetched. */
    NETWORK,

    /** The manifest was malformed, or failed schema validation. */
    INVALID_MANIFEST,

    /** The payload did not match the digest the manifest promised. */
    FAILED_VERIFICATION,

    /** The verified patch could not be written to storage. */
    STORAGE,

    UNKNOWN,
}
