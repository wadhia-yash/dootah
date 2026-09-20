package dev.dootah.contract

/**
 * The identity of a native action the installed app can run on a bundle's behalf.
 *
 * A capability is a lambda written in ordinary Compose source -- an `onClick`, an
 * `onDismissRequest`, an `onBrushChange` -- that the compiler lifts into the
 * APK, closing over the screen's own parameters. The bundle refers to one by
 * name and can attach it to any component it likes; it cannot construct one, and
 * it cannot reach anything the lambda did not already touch.
 *
 * The name is a normalised rendering of what the lambda *does*. That is what
 * makes it independent of where it was written: moving the call, deleting its
 * neighbour, or placing the same handler on three components does not change it,
 * and two lambdas with identical bodies share one capability rather than
 * becoming two indistinguishable entries.
 *
 * Both compiler passes build the same [Statement] list from their own very
 * different trees and render it here, so the rendering itself is never written
 * twice and cannot drift.
 *
 * Only the forms below can be named. A lambda doing anything else -- a
 * condition, a loop, a local variable -- has no stable rendering, and the screen
 * keeps its native implementation rather than being given a capability whose
 * meaning the two passes might not agree on.
 */
public object CapabilityId {

    /**
     * A constant as both passes render it.
     *
     * Here rather than in either compiler pass because the two meet constants in
     * different shapes: the app's own compilation is handed the folded value,
     * and the extraction pass is handed the name the value was written under.
     * Rendering is the one thing they must do identically.
     */
    public fun literal(value: Any?): String = when (value) {
        is String -> "\"" + value + "\""
        else -> value.toString()
    }

    public fun of(statements: List<Statement>): String =
        statements.joinToString(STATEMENT_SEPARATOR) { statement -> statement.render() }

    public sealed interface Statement {

        public fun render(): String
    }

    /** `receiver.member(a, b)`, or `member(a, b)` for a screen callback. */
    public data class Invoke(
        val receiver: String?,
        val member: String,
        val arguments: List<Argument> = emptyList(),
    ) : Statement {

        override fun render(): String {
            val target = if (receiver == null) member else "$receiver.$member"
            return target + "(" + arguments.joinToString(ARGUMENT_SEPARATOR) { it.render() } + ")"
        }
    }

    /** `receiver.property = value`, which is how a `MutableState` is written. */
    public data class Assign(
        val receiver: String,
        val property: String,
        val value: Argument,
    ) : Statement {

        override fun render(): String = "$receiver.$property=" + value.render()
    }

    public sealed interface Argument {

        public fun render(): String
    }

    /**
     * One of the lambda's own parameters, by position.
     *
     * Rendered positionally because a Kotlin function type has no parameter
     * names to use instead: `onBrushChange: (CustomBrush) -> Unit` says nothing
     * about what the lambda called its argument, and two sources that named it
     * differently describe the same capability.
     */
    public data class Parameter(val index: Int) : Argument {
        override fun render(): String = "$$index"
    }

    /** A constant, already rendered in the canonical form for its type. */
    public data class Literal(val text: String) : Argument {
        override fun render(): String = text
    }

    /** A screen parameter, read straight through. */
    public data class Read(val name: String) : Argument {
        override fun render(): String = name
    }

    private const val STATEMENT_SEPARATOR = ";"

    private const val ARGUMENT_SEPARATOR = "|"
}
