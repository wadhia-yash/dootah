package dev.dootah.compiler.model

/**
 * A step a bundle can perform.
 *
 * Used for two things that turn out to be the same shape: the run of
 * declarations at the top of a screen, and the body of a button's click
 * handler. Keeping one statement model means `if` works identically in both
 * without a second lowering path.
 */
internal sealed interface BundleStatement {

    /** An immutable local, recomputed on every render. */
    data class DeclareValue(
        val name: String,
        val type: BundleType,
        val value: BundleExpression,
    ) : BundleStatement

    /**
     * A `var`, which becomes an entry in the screen's remote state.
     *
     * The initial value is seeded once. A re-render after a button changed it
     * must not reset it, which is what separates this from [DeclareValue].
     */
    data class DeclareState(
        val name: String,
        val type: BundleType,
        val initial: BundleExpression,
    ) : BundleStatement

    data class Assign(
        val name: String,
        val type: BundleType,
        val value: BundleExpression,
    ) : BundleStatement

    /** Asks the app to do something from the closed capability set. */
    data class Perform(val command: BundleCommandModel) : BundleStatement

    data class Conditional(
        val condition: BundleExpression,
        val ifTrue: List<BundleStatement>,
        val ifFalse: List<BundleStatement>,
    ) : BundleStatement
}

/** A capability a bundle may ask the app to exercise. */
internal sealed interface BundleCommandModel {

    /** Calls one of the screen's own callback parameters. */
    data class InvokeCallback(val name: String) : BundleCommandModel

    data class Log(val message: BundleExpression) : BundleCommandModel

    data class Toast(val message: BundleExpression) : BundleCommandModel
}
