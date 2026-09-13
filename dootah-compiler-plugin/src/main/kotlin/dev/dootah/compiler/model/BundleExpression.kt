package dev.dootah.compiler.model

/**
 * A value a bundle can compute.
 *
 * Closed on purpose. Lowering maps a resolved expression onto one of these or
 * rejects it, so there is no path by which an expression Dootah does not
 * understand reaches generated code.
 */
internal sealed interface BundleExpression {

    data class IntConstant(val value: Int) : BundleExpression

    data class LongConstant(val value: Long) : BundleExpression

    data class FloatConstant(val value: Float) : BundleExpression

    data class DoubleConstant(val value: Double) : BundleExpression

    data class StringConstant(val value: String) : BundleExpression

    data class BooleanConstant(val value: Boolean) : BundleExpression

    /** A `val` declared earlier in the screen, or one of its parameters. */
    data class LocalReference(val name: String) : BundleExpression

    /** A `var` declared in the screen, which lives in the screen's remote state. */
    data class StateReference(val name: String, val type: BundleType) : BundleExpression

    data class Arithmetic(
        val operator: ArithmeticOperator,
        val left: BundleExpression,
        val right: BundleExpression,
    ) : BundleExpression

    data class Comparison(
        val operator: ComparisonOperator,
        val left: BundleExpression,
        val right: BundleExpression,
    ) : BundleExpression

    data class Logical(
        val operator: LogicalOperator,
        val left: BundleExpression,
        val right: BundleExpression,
    ) : BundleExpression

    data class Not(val value: BundleExpression) : BundleExpression

    data class Negate(val value: BundleExpression) : BundleExpression

    /** An `if` used as a value, and what a `when` expression lowers to. */
    data class Conditional(
        val condition: BundleExpression,
        val ifTrue: BundleExpression,
        val ifFalse: BundleExpression,
    ) : BundleExpression

    /** A call to a function the same screen bundles alongside it. */
    data class Invoke(
        val functionName: String,
        val arguments: List<BundleExpression>,
    ) : BundleExpression

    /**
     * A string template, in source order.
     *
     * Kept as parts rather than folded into one constant: the parts are what
     * make the value depend on the screen's logic, and folding would turn
     * computed text back into a fixed string.
     */
    data class Interpolation(val parts: List<BundleExpression>) : BundleExpression
}

internal enum class ArithmeticOperator(val symbol: String) {
    PLUS("+"),
    MINUS("-"),
    TIMES("*"),
    DIV("/"),
    REM("%"),
}

internal enum class ComparisonOperator(val symbol: String) {
    LESS("<"),
    LESS_OR_EQUAL("<="),
    GREATER(">"),
    GREATER_OR_EQUAL(">="),
    EQUAL("=="),
    NOT_EQUAL("!="),
}

internal enum class LogicalOperator(val symbol: String) {
    AND("&&"),
    OR("||"),
}
