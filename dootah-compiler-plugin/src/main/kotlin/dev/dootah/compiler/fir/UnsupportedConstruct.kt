package dev.dootah.compiler.fir

/**
 * Something in a `@Bundlable` function that Dootah cannot send over the air.
 *
 * Carries where it is and what to do about it. A diagnostic that only says
 * "unsupported" leaves a developer guessing between rewriting the screen and
 * exposing a capability through the native bridge, which are very different
 * pieces of work.
 */
internal data class UnsupportedConstruct(
    val functionName: String,
    val filePath: String,
    val sourceOffset: Int?,

    /** What was found, in the developer's terms. */
    val found: String,

    /** What to do instead. */
    val remedy: String,
)
