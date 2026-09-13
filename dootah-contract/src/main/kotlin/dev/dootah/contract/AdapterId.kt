package dev.dootah.contract

/**
 * The identity of a native composable the installed app can instantiate.
 *
 * An adapter is a *reusable* entry point into one composable that shipped in the
 * APK. A bundle names an adapter and supplies its arguments as data, so it may
 * place that composable nowhere, once, or five times, in any order, without the
 * APK knowing anything about the arrangement.
 *
 * That is the property this identity exists to provide, and it rules out
 * everything positional. An identity derived from where the call was written, or
 * from its position among its siblings, means deleting one component silently
 * rebinds the rest -- the app still finds something under the name it is asked
 * for, so nothing is reported, and the screen draws the wrong thing.
 *
 * So an adapter is named by the *declaration* it calls: the fully-qualified name
 * of the composable, plus the names of the parameters that composable declares.
 *
 * The declaration, deliberately, and not the call. Which arguments a particular
 * call supplied is exactly what a bundle must be free to change, so it cannot be
 * part of the name. The declaration's own parameters are fixed for a given build
 * of the app, and they are what distinguishes two overloads of the same name --
 * `Icon(painter, ...)` from `Icon(imageVector, ...)` -- which would otherwise
 * collide.
 *
 * Parameter names are sorted so that reordering a declaration's parameters,
 * which is source-compatible for callers using named arguments, does not rename
 * the adapter.
 */
public object AdapterId {

    /**
     * Builds the id for a composable declaration.
     *
     * [parameterNames] must be the declaration's own value parameters, with any
     * the compiler synthesises already removed -- the Compose plugin adds its
     * own, and including them would make the name depend on a lowering that one
     * of the two passes reading this has not run yet.
     */
    public fun of(qualifiedName: String, parameterNames: List<String>): String =
        qualifiedName + "(" + parameterNames.sorted().joinToString(SEPARATOR) + ")"

    /** The composable's fully-qualified name, without the parameter list. */
    public fun qualifiedNameOf(adapterId: String): String = adapterId.substringBefore('(')

    /**
     * Separates parameter names inside an id.
     *
     * Not a comma, and not a character that occurs in a Kotlin identifier or a
     * qualified name, so that an id can be split back apart and can travel
     * through any format without needing to be escaped.
     */
    public const val SEPARATOR: String = "|"
}
