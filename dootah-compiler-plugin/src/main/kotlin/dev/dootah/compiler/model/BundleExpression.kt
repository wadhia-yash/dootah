package dev.dootah.compiler.model

/**
 * A value a bundle can compute.
 *
 * Closed on purpose. Lowering maps a resolved FIR expression onto one of these
 * or rejects it, so there is no path by which an expression Dootah does not
 * understand reaches generated code.
 */
internal sealed interface BundleExpression {

    data class IntConstant(val value: Int) : BundleExpression

    data class StringConstant(val value: String) : BundleExpression

    data class BooleanConstant(val value: Boolean) : BundleExpression

    /** A reference to a local declared earlier in the same screen. */
    data class LocalReference(val name: String) : BundleExpression

    data class Arithmetic(
        val operator: ArithmeticOperator,
        val left: BundleExpression,
        val right: BundleExpression,
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
