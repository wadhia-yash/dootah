package com.dootah.ota

/**
 * Failures raised by the Dootah update pipeline.
 *
 * These are deliberately distinct types rather than one generic error: the
 * caller's correct response differs sharply between them. A failed update check
 * must leave the installed bundle untouched, whereas a payload that fails
 * verification must never be executed at all.
 */
sealed class BundleException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The manifest was unreachable, malformed, or failed schema validation. */
class BundleManifestException(
    message: String,
    cause: Throwable? = null,
) : BundleException(message, cause)

/** The bundle payload could not be retrieved. */
class BundleDownloadException(
    message: String,
    cause: Throwable? = null,
) : BundleException(message, cause)

/** The payload did not match the digest the manifest promised. */
class BundleVerificationException(
    message: String,
    cause: Throwable? = null,
) : BundleException(message, cause)

/**
 * The bundle targets a different Dootah runtime than this build implements.
 *
 * Thrown only when an incompatible bundle is about to be acted upon; a routine
 * update check reports this as [UpdateDecision.Incompatible] instead, because
 * finding an incompatible bundle on the server is an expected condition during a
 * staged runtime migration, not an error.
 */
class IncompatibleRuntimeException(
    val manifestRuntimeVersion: String,
    val supportedRuntimeVersion: String,
) : BundleException(
    "Bundle targets Dootah runtime '$manifestRuntimeVersion' " +
        "but this build implements '$supportedRuntimeVersion'"
)

/**
 * The bundle failed to evaluate, exceeded its time budget, or produced UI that
 * could not be parsed.
 */
class BundleExecutionException(
    message: String,
    cause: Throwable? = null,
) : BundleException(message, cause)
