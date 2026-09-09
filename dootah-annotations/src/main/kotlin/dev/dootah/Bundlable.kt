package dev.dootah

/**
 * Marks a Composable function whose implementation Dootah may replace over the air.
 *
 * The annotated function's original body stays in the APK and remains the
 * fallback: Dootah renders a remote implementation only when it has one that is
 * compatible, and otherwise the native body runs exactly as written.
 *
 * @param id Stable identity shared by the installed app and the published bundle.
 *
 * Left empty, the identity is derived from the fully qualified function name,
 * which means renaming or moving the function changes it and any already
 * published bundle stops matching (the app then renders the native body). Set an
 * explicit id on any function expected to be renamed or moved.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Bundlable(val id: String = "")
