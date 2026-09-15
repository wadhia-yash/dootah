package dev.dootah.contract

/**
 * The identity of a screen callback a bundle may ask the installed app to run.
 *
 * A callback is a function-typed parameter of a `@Bundlable` screen -- an
 * `onSave`, a `navigateToPost` -- that the app's own caller supplied. The bundle
 * refers to one by this id and hands it values it computed itself; it cannot
 * construct one, cannot reach a callback of another screen, and cannot name a
 * method.
 *
 * The parameter types are part of the identity because they are what the values
 * crossing the wire are coerced to. A build that changed `(String) -> Unit` into
 * `(Int) -> Unit` still declares a parameter of that name, and a bundle written
 * against the old shape would otherwise go on sending text to it -- refused at
 * publish time here rather than dropped on a device.
 *
 * Deliberately *not* [CapabilityId]. A capability is a handler lifted out of the
 * app's own source, which the bundle can only place; a callback is the app's own
 * parameter, which the bundle can call. The two are checked separately because
 * having one is no evidence of having the other.
 */
public object CallbackId {

    /** [parameterTypes] are simple Kotlin names -- `String`, `Int` -- in order. */
    public fun of(name: String, parameterTypes: List<String>): String =
        name + "(" + parameterTypes.joinToString(SEPARATOR) + ")"

    /** The parameter's own name, without its signature. */
    public fun nameOf(id: String): String = id.substringBefore('(')

    /**
     * Separates parameter types inside an id.
     *
     * The same separator [AdapterId] uses, and for the same reason: not a
     * character that occurs in a Kotlin identifier, so an id splits back apart
     * and travels through any format unescaped.
     */
    public const val SEPARATOR: String = "|"
}
