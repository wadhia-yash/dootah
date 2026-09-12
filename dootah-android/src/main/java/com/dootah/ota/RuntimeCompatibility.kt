package com.dootah.ota

/**
 * The Dootah runtime contract this build of the app implements.
 *
 * A bundle is written against a specific runtime: the exported function
 * signatures, the set of UI node types and modifiers the renderer understands,
 * and the command vocabulary. Bump this whenever a change to any of those would
 * break bundles built before it, and treat it as an opaque token rather than a
 * number to be compared numerically.
 *
 * "3" adds the fragment node, so a screen can be several components with no
 * layout around them. A "2" renderer has no such node and would refuse the whole
 * response, which is exactly what the gate is for.
 *
 * "2" was the screen-addressed protocol: `screenIds()`, `renderScreen(id, args)`
 * and `handleAction(id, action, args)`, returning a UI-and-commands envelope.
 * "1" was single-screen and took no arguments.
 */
const val DOOTAH_RUNTIME_VERSION: String = "3"

/**
 * True when [manifest] targets exactly the runtime named by [supportedVersion].
 *
 * Deliberately an equality test and not a "greater or equal" range. A bundle
 * built for runtime "2" may call bridge commands or emit UI nodes that runtime
 * "1" has never heard of, and a bundle built for "1" is not guaranteed to survive
 * a breaking change in "2" either. Since the cost of guessing wrong is executing
 * remote code against a runtime that cannot honour it, this check fails closed
 * in both directions.
 *
 * The supported version is a parameter with a default so that compatibility can
 * be tested without reaching for a global.
 */
fun isRuntimeCompatible(
    manifest: BundleManifest,
    supportedVersion: String = DOOTAH_RUNTIME_VERSION,
): Boolean = manifest.runtimeVersion == supportedVersion
