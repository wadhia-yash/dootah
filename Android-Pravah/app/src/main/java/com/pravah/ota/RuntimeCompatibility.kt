package com.pravah.ota

/**
 * The Pravah runtime contract this build of the app implements.
 *
 * A patch is written against a specific runtime: the JavaScript bootstrap, the
 * set of UI node types the renderer understands, and the native bridge command
 * vocabulary. Bump this whenever a change to any of those would break patches
 * built before it, and treat it as an opaque token rather than a number to be
 * compared numerically.
 */
const val PRAVAH_RUNTIME_VERSION: String = "1"

/**
 * True when [manifest] targets exactly the runtime named by [supportedVersion].
 *
 * Deliberately an equality test and not a "greater or equal" range. A patch
 * built for runtime "2" may call bridge commands or emit UI nodes that runtime
 * "1" has never heard of, and a patch built for "1" is not guaranteed to survive
 * a breaking change in "2" either. Since the cost of guessing wrong is executing
 * remote code against a runtime that cannot honour it, this check fails closed
 * in both directions.
 *
 * The supported version is a parameter with a default so that compatibility can
 * be tested without reaching for a global.
 */
fun isRuntimeCompatible(
    manifest: PatchManifest,
    supportedVersion: String = PRAVAH_RUNTIME_VERSION,
): Boolean = manifest.runtimeVersion == supportedVersion
