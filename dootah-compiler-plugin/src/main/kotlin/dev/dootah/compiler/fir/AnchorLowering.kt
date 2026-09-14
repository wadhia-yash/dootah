package dev.dootah.compiler.fir

import dev.dootah.contract.AnchorId
import dev.dootah.contract.Dimension
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType

/**
 * Reads a length the app owns, as the name the app will answer to.
 *
 * `Arrangement.spacedBy(MaterialTheme.padding.small)` is the commonest thing in
 * a real app that a screen cannot bundle, and the reason is thin: the gap is the
 * app's spacing scale rather than a literal. Two ways out are worse than the
 * problem. Refusing the screen loses a whole layout over a number. Reading the
 * number at build time and shipping `8.dp` forks the app's spacing: the app
 * retunes its scale in a later release and every bundled screen keeps the old
 * value, visibly out of step with the native ones beside it.
 *
 * So nothing is read. The chain is written down as a name, the APK registers the
 * same name against its own expression, and the value never leaves the app.
 *
 * ## What counts
 *
 * A chain of plain property reads rooted at a named object or companion, whose
 * type is `Dp`. `MaterialTheme.padding.small` qualifies; a call, an argument, a
 * local, or arithmetic does not. The line is not about difficulty -- it is that
 * both compilations have to arrive at the same name from two versions of the
 * source, and a chain of resolved property symbols is something they can. A call
 * would have to agree about its arguments too, and a local does not survive
 * being lifted out of the body at all.
 */
internal object AnchorLowering {

    /** `androidx.compose.ui.unit.Dp`, the only type that may be anchored yet. */
    private const val DP_TYPE = "androidx/compose/ui/unit/Dp"

    /**
     * The anchor name for this expression, or null when it cannot be one.
     *
     * Walks receivers outward and reverses, the way a modifier chain is read:
     * the name is the chain as written, so it reads back in a build failure as
     * the thing the developer would search for.
     */
    fun read(expression: FirExpression): String? {

        if (!expression.isDp()) return null

        val path = mutableListOf<String>()
        var current: FirExpression? = expression

        while (true) {

            when (val node = current) {

                // The root: an object, a companion, or a class whose member this
                // is. Its fully qualified name is what makes the chain
                // unambiguous -- `Padding.small` alone stops meaning one thing
                // the moment a second class has a `small`.
                is FirResolvedQualifier -> {
                    val root = node.classId?.asSingleFqName()?.asString() ?: return null
                    if (path.isEmpty()) return null
                    return AnchorId.of(root, path.asReversed().toList())
                }

                is FirPropertyAccessExpression -> {
                    val name = node.calleeReference.toResolvedCallableSymbol()?.name?.asString()
                        ?: return null
                    path += name
                    current = node.explicitReceiver ?: return null
                }

                // A call, a literal, a local read: none of them is a name the
                // other compilation could arrive at independently.
                else -> return null
            }
        }
    }

    /** The dimension this expression is, when it is one the app owns. */
    fun dimension(expression: FirExpression): Dimension? =
        read(expression)?.let { anchor -> Dimension.anchored(anchor) }

    private fun FirExpression.isDp(): Boolean =
        runCatching { resolvedType.classId?.asString() }.getOrNull() == DP_TYPE
}
