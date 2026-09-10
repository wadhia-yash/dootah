package dev.dootah.compiler.fir

import dev.dootah.compiler.model.ArithmeticOperator
import org.jetbrains.kotlin.name.FqName

/**
 * Exactly what Milestone 1 can lower, listed by the fully qualified name a call
 * resolves to.
 *
 * Matching on resolved names rather than on the text at the call site is what
 * makes this trustworthy: a locally declared `Text` is a different function from
 * Compose's, and only the resolved symbol distinguishes them.
 *
 * The list is deliberately short. Widening it is a decision to support, test and
 * document another construct, never a side effect of making one screen compile.
 */
internal object SupportedCatalog {

    val COLUMN: FqName = FqName("androidx.compose.foundation.layout.Column")
    val TEXT: FqName = FqName("androidx.compose.material3.Text")
    val BUTTON: FqName = FqName("androidx.compose.material3.Button")

    private val ARITHMETIC: Map<FqName, ArithmeticOperator> = mapOf(
        FqName("kotlin.Int.plus") to ArithmeticOperator.PLUS,
        FqName("kotlin.Int.minus") to ArithmeticOperator.MINUS,
        FqName("kotlin.Int.times") to ArithmeticOperator.TIMES,
        FqName("kotlin.Int.div") to ArithmeticOperator.DIV,
        FqName("kotlin.Int.rem") to ArithmeticOperator.REM,
    )

    fun arithmeticFor(callable: FqName): ArithmeticOperator? = ARITHMETIC[callable]

    /** The composables a screen body may call, for use in diagnostics. */
    val SUPPORTED_COMPOSABLES: List<FqName> = listOf(COLUMN, TEXT, BUTTON)
}
