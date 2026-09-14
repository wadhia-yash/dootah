package dev.dootah.compiler.fir

import dev.dootah.contract.Alignments
import dev.dootah.contract.Arrangements
import dev.dootah.contract.LayoutArrangement
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.name.FqName

/**
 * Reads the alignment and arrangement a layout was written with.
 *
 * These are the second-largest measured blocker after third-party composables,
 * and they are blocked for a reason that does not apply to most of the others:
 * an `Alignment` is not data. It is an object with behaviour -- it computes a
 * position from a size and a direction -- so there is nothing to serialise. What
 * there is instead is a closed, published vocabulary of the values Compose
 * itself declares, which is what [Alignments] and [Arrangements] are: the bundle
 * names one, and the installed app resolves the name against the object it
 * already has.
 *
 * Which set a name is checked against comes from the parameter it is going into,
 * never from the name. Compose's type system already separates a
 * `Alignment.Horizontal` from a `Alignment.Vertical`, and a lowering that
 * accepted any alignment anywhere would let a bundle put `CenterVertically` on a
 * `Column`, where the app would have no value to resolve it to.
 *
 * Everything outside the vocabulary is refused, and a refusal here is not fatal:
 * it costs the layout its remote description and the enclosing screen walks the
 * degradation ladder, exactly as an unsupported modifier does.
 */
internal class LayoutLowering(private val rejector: Rejector) {

    /** `Column(horizontalAlignment = Alignment.CenterHorizontally)`. */
    fun horizontalAlignment(expression: FirExpression, layout: String): String? =
        alignment(expression, layout, "horizontalAlignment", Alignments.HORIZONTAL)

    /** `Row(verticalAlignment = Alignment.CenterVertically)`. */
    fun verticalAlignment(expression: FirExpression, layout: String): String? =
        alignment(expression, layout, "verticalAlignment", Alignments.VERTICAL)

    /** `Box(contentAlignment = Alignment.Center)`. */
    fun boxAlignment(expression: FirExpression, layout: String): String? =
        alignment(expression, layout, "contentAlignment", Alignments.BOX)

    /** `Column(verticalArrangement = Arrangement.spacedBy(8.dp))`. */
    fun verticalArrangement(expression: FirExpression, layout: String): LayoutArrangement? =
        arrangement(expression, layout, "verticalArrangement", Arrangements.VERTICAL)

    /** `Row(horizontalArrangement = Arrangement.SpaceBetween)`. */
    fun horizontalArrangement(expression: FirExpression, layout: String): LayoutArrangement? =
        arrangement(expression, layout, "horizontalArrangement", Arrangements.HORIZONTAL)

    private fun alignment(
        expression: FirExpression,
        layout: String,
        parameter: String,
        vocabulary: List<String>,
    ): String? {

        val token = memberName(expression, SupportedCatalog.ALIGNMENT_COMPANION)

        if (token == null || token !in vocabulary) {
            reject(
                expression,
                layout = layout,
                parameter = parameter,
                found = token?.let { "`Alignment.$it`" } ?: "an alignment Dootah could not read",
                vocabulary = vocabulary,
                prefix = "Alignment",
            )
            return null
        }

        return token
    }

    private fun arrangement(
        expression: FirExpression,
        layout: String,
        parameter: String,
        vocabulary: List<String>,
    ): LayoutArrangement? {

        // `Arrangement.spacedBy(8.dp)`: the one arrangement carrying a value,
        // and the only reason a dp literal is read on this path at all.
        if (expression is FirFunctionCall) {
            if (expression.resolvedCallableName() != SupportedCatalog.ARRANGEMENT_SPACED_BY) {
                reject(
                    expression, layout, parameter,
                    found = "an arrangement Dootah could not read",
                    vocabulary = vocabulary,
                    prefix = "Arrangement",
                )
                return null
            }
            return spacedBy(expression, layout, parameter, vocabulary)
        }

        val token = memberName(expression, SupportedCatalog.ARRANGEMENT)

        if (token == null || token !in vocabulary || token == Arrangements.SPACED_BY) {
            reject(
                expression, layout, parameter,
                found = token?.let { "`Arrangement.$it`" } ?: "an arrangement Dootah could not read",
                vocabulary = vocabulary,
                prefix = "Arrangement",
            )
            return null
        }

        return LayoutArrangement(token)
    }

    /**
     * Reads `Arrangement.spacedBy(8.dp)`.
     *
     * The gap must be a literal, for the same reason a `padding` must be: a
     * computed one is an expression the remote side would have to evaluate.
     *
     * The overload taking an alignment as well is refused rather than having its
     * second argument dropped. Dropping it would draw a layout nobody wrote, and
     * a silently different layout is the failure this whole design is built to
     * avoid.
     */
    private fun spacedBy(
        call: FirFunctionCall,
        layout: String,
        parameter: String,
        vocabulary: List<String>,
    ): LayoutArrangement? {

        val mapping = call.resolvedArgumentMapping

        if (mapping == null) {
            reject(
                call, layout, parameter,
                found = "a spacedBy Dootah could not read",
                vocabulary = vocabulary,
                prefix = "Arrangement",
            )
            return null
        }

        var space: Double? = null

        for ((argument, argumentParameter) in mapping) {
            when (argumentParameter.name.asString()) {

                "space" -> {
                    space = DpLiteral.read(argument)
                    if (space == null) {
                        reject(
                            argument, layout, parameter,
                            found = "a spacedBy gap that is not a dp literal",
                            detail = "$layout.$parameter.spacedBy",
                            remedy = "Write the gap as a literal, like " +
                                "Arrangement.spacedBy(8.dp). A computed gap is not bundled.",
                        )
                        return null
                    }
                }

                else -> {
                    reject(
                        argument, layout, parameter,
                        found = "the spacedBy argument " +
                            "`${argumentParameter.name.asString()}`",
                        detail = "$layout.$parameter.spacedBy." +
                            argumentParameter.name.asString(),
                        remedy = "Dootah bundles Arrangement.spacedBy(8.dp). " +
                            "The overload that also takes an alignment is not bundled.",
                    )
                    return null
                }
            }
        }

        if (space == null) {
            reject(
                call, layout, parameter,
                found = "a spacedBy with no gap",
                detail = "$layout.$parameter.spacedBy",
                remedy = "Write Arrangement.spacedBy(8.dp).",
            )
            return null
        }

        return LayoutArrangement(Arrangements.SPACED_BY, space)
    }

    /**
     * The member name this reads, when it is read off [owner].
     *
     * [owner] is matched against the callable's enclosing name, and against that
     * name's parent too, because `Alignment.Center` resolves through
     * `Alignment.Companion` and is written without it.
     */
    private fun memberName(expression: FirExpression, owner: FqName): String? {

        val access = expression as? FirPropertyAccessExpression ?: return null
        val callable = access.resolvedCallableName() ?: return null

        if (callable.isRoot) return null

        val enclosing = callable.parent()
        if (enclosing != owner && enclosing != owner.parent()) return null

        return callable.shortName().asString()
    }

    private fun reject(
        expression: FirExpression?,
        layout: String,
        parameter: String,
        found: String,
        vocabulary: List<String>,
        prefix: String,
    ) {
        reject(
            expression, layout, parameter, found,
            detail = "$layout.$parameter",
            remedy = "Dootah bundles these on $layout.$parameter: " +
                vocabulary.joinToString(", ") { token ->
                    if (token == Arrangements.SPACED_BY) "$prefix.$token(8.dp)" else "$prefix.$token"
                } + ".",
        )
    }

    private fun reject(
        expression: FirExpression?,
        layout: String,
        parameter: String,
        found: String,
        detail: String,
        remedy: String,
    ) {
        rejector.reject(
            expression?.source?.startOffset,
            "$found on $layout.$parameter",
            remedy,
            RejectionCode.UNSUPPORTED_LAYOUT_ARGUMENT,
            detail,
        )
    }
}
