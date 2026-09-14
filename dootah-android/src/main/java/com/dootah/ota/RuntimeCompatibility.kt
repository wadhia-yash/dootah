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
 * "6" lets a native container's content be a list of entries rather than
 * children. A "5" renderer knows no `builders` field and, because unknown keys
 * are ignored rather than refused, would draw the container with nothing in it
 * -- an empty list where an article was, on a screen whose whole content is
 * that list.
 *
 * "5" lets a length be a name instead of a number, so a bundle can lay a screen
 * out with the app's own spacing scale rather than a copy of it. A "4" renderer
 * reads every length as a number; handed a name it would lay out at zero, and a
 * collapsed screen reads as a design decision rather than as a fault.
 *
 * "4" lets a layout carry an alignment and an arrangement. This is the case the
 * gate matters most for: the parser ignores keys it does not know, so a "3"
 * renderer handed a "4" bundle would not fail -- it would quietly draw every
 * layout with Compose's default alignment. A screen that is subtly not the one
 * described is worse than one that does not render.
 *
 * "3" adds the fragment node, so a screen can be several components with no
 * layout around them. A "2" renderer has no such node and would refuse the whole
 * response, which is exactly what the gate is for.
 *
 * "2" was the screen-addressed protocol: `screenIds()`, `renderScreen(id, args)`
 * and `handleAction(id, action, args)`, returning a UI-and-commands envelope.
 * "1" was single-screen and took no arguments.
 */
const val DOOTAH_RUNTIME_VERSION: String = "6"

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
