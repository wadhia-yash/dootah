package dev.dootah.compiler.fir

import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression

/**
 * Reads `16.dp`, and nothing else.
 *
 * One definition shared by everything that takes a dimension -- modifiers and
 * layout arrangements -- because the two grew separate copies of "what a dp
 * literal is" and a difference between them would be invisible until a screen
 * lowered one way in one position and refused in the other.
 *
 * Literal only. A computed size is a value the remote side would have to
 * evaluate, and there is no reason to grow the expression surface into layout
 * until a real screen asks for it. The caller reports the refusal, because the
 * same unreadable dp means different things in a padding and in a `spacedBy`.
 */
internal object DpLiteral {

    /** The dp amount, or null when this is not a literal `N.dp`. */
    fun read(expression: FirExpression): Double? {

        val access = expression as? FirPropertyAccessExpression ?: return null
        if (access.resolvedCallableName() != SupportedCatalog.DP_PROPERTY) return null

        // Any number, because the frontend chooses the box: an integer literal
        // arrives as a `Long` whatever it was written as, so listing the types a
        // developer can type silently refused every `16.dp` ever written.
        return number(access.explicitReceiver ?: return null)
    }

    /** The value of a numeric literal, whichever numeric type it was written as. */
    fun number(expression: FirExpression): Double? =
        when (val value = (expression as? FirLiteralExpression)?.value) {
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Float -> value.toDouble()
            is Double -> value
            else -> null
        }
}
