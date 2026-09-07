package com.pravah.ota

/**
 * Failures raised by the Pravah update pipeline.
 *
 * These are deliberately distinct types rather than one generic error: the
 * caller's correct response differs sharply between them. A failed update check
 * must leave the installed patch untouched, whereas a payload that fails
 * verification must never be executed at all.
 */
sealed class PatchException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The manifest was unreachable, malformed, or failed schema validation. */
class PatchManifestException(
    message: String,
    cause: Throwable? = null,
) : PatchException(message, cause)

/** The patch payload could not be retrieved. */
class PatchDownloadException(
    message: String,
    cause: Throwable? = null,
) : PatchException(message, cause)

/** The payload did not match the digest the manifest promised. */
class PatchVerificationException(
    message: String,
    cause: Throwable? = null,
) : PatchException(message, cause)

/**
 * The patch targets a different Pravah runtime than this build implements.
 *
 * Thrown only when an incompatible patch is about to be acted upon; a routine
 * update check reports this as [UpdateDecision.Incompatible] instead, because
 * finding an incompatible patch on the server is an expected condition during a
 * staged runtime migration, not an error.
 */
class IncompatibleRuntimeException(
    val manifestRuntimeVersion: String,
    val supportedRuntimeVersion: String,
) : PatchException(
    "Patch targets Pravah runtime '$manifestRuntimeVersion' " +
        "but this build implements '$supportedRuntimeVersion'"
)
