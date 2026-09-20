package dev.dootah.compiler.lowering

import dev.dootah.compiler.source.*

/**
 * What one ordinary Compose function's body is made of, as far as extraction needs
 * to know.
 *
 * Recorded in source order: the eventual lowering emits UI in the order the
 * developer wrote it, so the order is part of the observation rather than
 * incidental.
 */
data class ScreenBody(
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
fun SourceElement.inspectScreenBody(): ScreenBody {

    val resolvedCalls = mutableListOf<String>()
    val literals = mutableListOf<String>()
    var hasConditional = false
    var hasStringInterpolation = false

    val visitor = object : SourceVisitor() {

        override fun visitElement(element: SourceElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: SourceFunctionCall) {

            val resolvedName = functionCall.calleeReference
                .toResolvedCallableSymbol()
                ?.callableId
                ?.asSingleFqName()
                ?.asString()

            resolvedCalls += resolvedName ?: UNRESOLVED_CALL

            super.visitFunctionCall(functionCall)
        }

        override fun visitLiteralExpression(literalExpression: SourceLiteralExpression) {
            literals += literalExpression.value.toString()
            super.visitLiteralExpression(literalExpression)
        }

        override fun visitWhenExpression(whenExpression: SourceWhenExpression) {
            hasConditional = true
            super.visitWhenExpression(whenExpression)
        }

        override fun visitStringConcatenationCall(
            stringConcatenationCall: SourceStringConcatenationCall,
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
const val UNRESOLVED_CALL = "<unresolved>"
