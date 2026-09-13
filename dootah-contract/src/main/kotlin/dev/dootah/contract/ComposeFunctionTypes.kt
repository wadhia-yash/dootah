package dev.dootah.contract

/**
 * How a function-typed value is spelled after Compose has been through it.
 *
 * Compose rewrites a composable function type into its own class, so the same
 * parameter reads as `kotlin.Function0<Unit>` carrying a `@Composable`
 * annotation in source being analysed, and as
 * `androidx.compose.runtime.internal.ComposableFunction0<Unit>` once it has
 * been compiled into a library. A screen's own lambda is the first; the
 * declaration of `IconButton` it is passed to is the second.
 *
 * Both passes read this from here rather than each keeping its own list,
 * because they read the two spellings from two different places -- one from
 * source, one from a dependency -- and each has already been shipped knowing
 * only one of them. Getting it wrong costs a whole screen: one pass hands the
 * bundle children to draw while the other builds an adapter with nowhere to
 * draw them, or a component's content is copied into a handler and the build
 * fails inside the Compose compiler.
 */
public object ComposeFunctionTypes {

    /** A plain Kotlin function type, which may carry `@Composable` separately. */
    public fun isFunction(qualifiedName: String): Boolean =
        qualifiedName.startsWith(FUNCTION)

    /** A function type Compose has already marked as composable. */
    public fun isComposableFunction(qualifiedName: String): Boolean =
        qualifiedName.startsWith(COMPOSABLE_FUNCTION)

    /** How many arguments this function type takes, either spelling. */
    public fun arityOf(qualifiedName: String): Int? = when {
        isComposableFunction(qualifiedName) ->
            qualifiedName.removePrefix(COMPOSABLE_FUNCTION).toIntOrNull()

        isFunction(qualifiedName) ->
            qualifiedName.removePrefix(FUNCTION).toIntOrNull()

        else -> null
    }

    private const val FUNCTION = "kotlin.Function"

    private const val COMPOSABLE_FUNCTION =
        "androidx.compose.runtime.internal.ComposableFunction"
}
