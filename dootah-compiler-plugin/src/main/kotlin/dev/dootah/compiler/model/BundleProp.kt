package dev.dootah.compiler.model

import dev.dootah.contract.PropValue

/**
 * An argument the bundle supplies to a native component.
 *
 * Two shapes, and the difference is when the value is known. Most arguments are
 * fixed when the screen is lowered -- a resource name, a size, a capability --
 * and travel as a [Constant]. The interesting ones are not: a colour chosen by
 * `if (isEraserMode)` depends on a value the bundle computes at render time, and
 * lowering it to a constant would throw away the thing an update is for.
 *
 * So a prop is a small expression tree over the contract's value vocabulary,
 * evaluated in the bundle and arriving at the app already decided.
 */
internal sealed interface BundleProp {

    /** A value already known when the screen was lowered. */
    data class Constant(val value: PropValue) : BundleProp

    /**
     * A scalar the bundle works out for itself.
     *
     * [kind] is the contract kind the result is sent as, so the app knows what
     * it is receiving without having to infer it from the JSON.
     */
    data class Computed(val kind: String, val expression: BundleExpression) : BundleProp

    /** `if`/`else` over arguments, which is how a conditional colour lowers. */
    data class Conditional(
        val condition: BundleExpression,
        val ifTrue: BundleProp,
        val ifFalse: BundleProp,
    ) : BundleProp

    /** A `Modifier` chain whose steps may themselves carry computed arguments. */
    data class Modifier(val operations: List<BundleModifierOp>) : BundleProp

    data class ListOf(val elements: List<BundleProp>) : BundleProp
}

internal data class BundleModifierOp(
    val name: String,
    val arguments: Map<String, BundleProp>,
)

/**
 * A native action the app generates from a lambda in the screen's own source.
 *
 * Recorded separately from the props that refer to it because the app has to
 * build the thing, not just be told its name -- and because two components given
 * the same handler refer to one capability rather than two.
 */
internal data class BundleCapability(
    val id: String,
    val arity: Int,
)
