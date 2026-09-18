package dev.dootah.compiler.fir

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * What one ordinary Compose function's body is made of, as far as extraction needs
 * to know.
 *
 * Recorded in source order: the eventual lowering emits UI in the order the
 * developer wrote it, so the order is part of the observation rather than
 * incidental.
 */
internal data class ScreenBody(
    val resolvedCalls: List<String>,
    val literals: List<String>,
    val hasConditional: Boolean,
    val hasStringInterpolation: Boolean,
)

/**
 * Reads a resolved FIR body.
 *
 * Every call is recorded by the fully qualified name its callee actually
 * resolved to, not by the name written at the call site. That distinction is the
 * whole reason extraction works on FIR: `Text` in one file and `Text` in another
 * may be different functions, and only the resolved symbol says which is which.
 */
internal fun FirElement.inspectScreenBody(): ScreenBody {

    val resolvedCalls = mutableListOf<String>()
    val literals = mutableListOf<String>()
    var hasConditional = false
    var hasStringInterpolation = false

    val visitor = object : FirVisitorVoid() {

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {

            val resolvedName = functionCall.calleeReference
                .toResolvedCallableSymbol()
                ?.callableId
                ?.asSingleFqName()
                ?.asString()

            resolvedCalls += resolvedName ?: UNRESOLVED_CALL

            super.visitFunctionCall(functionCall)
        }

        override fun visitLiteralExpression(literalExpression: FirLiteralExpression) {
            literals += literalExpression.value.toString()
            super.visitLiteralExpression(literalExpression)
        }

        override fun visitWhenExpression(whenExpression: FirWhenExpression) {
            hasConditional = true
            super.visitWhenExpression(whenExpression)
        }

        override fun visitStringConcatenationCall(
            stringConcatenationCall: FirStringConcatenationCall,
        ) {
            hasStringInterpolation = true
            super.visitStringConcatenationCall(stringConcatenationCall)
        }
    }

    accept(visitor)

    return ScreenBody(
        resolvedCalls = resolvedCalls.toList(),
        literals = literals.toList(),
        hasConditional = hasConditional,
        hasStringInterpolation = hasStringInterpolation,
    )
}

/**
 * Recorded rather than skipped. A call extraction could not resolve is exactly
 * the kind of thing that must be reported, never quietly omitted from a bundle.
 */
internal const val UNRESOLVED_CALL = "<unresolved>"
