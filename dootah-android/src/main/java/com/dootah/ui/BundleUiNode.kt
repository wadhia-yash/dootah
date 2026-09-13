package com.dootah.ui

import dev.dootah.contract.PropValue

/**
 * The UI a bundle may describe, as the app understands it.
 *
 * The Android mirror of the bundle-side node model. Two declarations rather than
 * a shared one because the two sides are versioned independently and travel over
 * a network: the app must be able to read a bundle it did not build, and the
 * only contract between them is the JSON, checked by the parser.
 */
sealed interface BundleUiNode {

    /** Nodes that hold other nodes; all three differ only in how they lay out. */
    sealed interface Container : BundleUiNode {
        val modifiers: List<BundleUiModifier>
        val children: List<BundleUiNode>
    }

    data class Column(
        override val modifiers: List<BundleUiModifier>,
        override val children: List<BundleUiNode>,
    ) : Container

    data class Row(
        override val modifiers: List<BundleUiModifier>,
        override val children: List<BundleUiNode>,
    ) : Container

    data class Box(
        override val modifiers: List<BundleUiModifier>,
        override val children: List<BundleUiNode>,
    ) : Container

    data class Text(
        val text: String,
        val modifiers: List<BundleUiModifier>,
    ) : BundleUiNode

    data class Button(
        val text: String,
        val action: String,
        val modifiers: List<BundleUiModifier>,
    ) : BundleUiNode

    /**
     * Several nodes drawn in place, with no layout around them.
     *
     * A screen is not required to be one layout. Where it is several components
     * in a row, whatever the caller wrapped the screen in is what lays them out,
     * so this adds nothing of its own.
     */
    data class Fragment(
        val children: List<BundleUiNode>,
    ) : BundleUiNode

    /**
     * An instance of a native composable that shipped in the APK.
     *
     * The bundle chooses which adapter to place, how many of them, where, in
     * what order, and what to give each one. The app decides what an adapter
     * *is*, and supplies everything a bundle is not allowed to name for itself:
     * the objects behind the handles, the code behind the capabilities, the
     * numbers behind the resource keys.
     *
     * Children are keyed by the adapter parameter that takes them, because a
     * component may have more than one content slot and the bundle has to say
     * which is which.
     */
    data class Component(
        val adapterId: String,
        val props: Map<String, PropValue> = emptyMap(),
        val children: Map<String, List<BundleUiNode>> = emptyMap(),
    ) : BundleUiNode
}

/**
 * A layout instruction from a bundle.
 *
 * Applied in list order, because Compose modifiers are order-sensitive and a
 * bundle that says "pad, then colour" must not be drawn as "colour, then pad".
 */
sealed interface BundleUiModifier {

    /** Splices in the `Modifier` the native caller passed to the screen. */
    data object Inherited : BundleUiModifier

    data class Padding(
        val start: Float,
        val top: Float,
        val end: Float,
        val bottom: Float,
    ) : BundleUiModifier

    data class FillMaxWidth(val fraction: Float) : BundleUiModifier

    data class FillMaxHeight(val fraction: Float) : BundleUiModifier

    data class FillMaxSize(val fraction: Float) : BundleUiModifier

    data class Size(val width: Float, val height: Float) : BundleUiModifier

    data class Width(val value: Float) : BundleUiModifier

    data class Height(val value: Float) : BundleUiModifier

    /** Ignored outside a Column or Row, where Compose has no weight to give. */
    data class Weight(val value: Float) : BundleUiModifier

    data class Background(val color: Long) : BundleUiModifier
}

/**
 * Everything this tree needs the installed app to already have.
 *
 * Collected before anything is drawn, and compared against what this build
 * registered. A bundle is refused for exactly one reason: it needs native code,
 * an action, an object or a resource that this APK genuinely does not contain.
 * Rearranging components, dropping one, repeating one or changing what one is
 * given are not reasons -- they are the point.
 *
 * Exhaustive on purpose, with no `else`. A node type that held children and was
 * not listed here would report nothing beneath it, and the check between a
 * bundle built against a different APK and a hole in a shipped screen would pass
 * without looking. Adding a node type has to stop compiling here.
 */
fun BundleUiNode.requirements(): BundleRequirements = when (this) {

    is BundleUiNode.Container -> children.requirements()

    is BundleUiNode.Fragment -> children.requirements()

    is BundleUiNode.Component ->
        BundleRequirements(
            adapters = listOf(adapterId),
            arguments = props.keys.map { name -> AdapterArgument(adapterId, name) },
        ) +
            props.values.map { value -> value.requirements() }.merge() +
            children.values.flatten().requirements()

    is BundleUiNode.Text -> BundleRequirements()

    is BundleUiNode.Button -> BundleRequirements()
}

/** What one prop needs to exist in the APK before it can be resolved. */
private fun PropValue.requirements(): BundleRequirements = when (this) {

    is PropValue.HandleValue -> BundleRequirements(handles = listOf(name))
    is PropValue.StateValue -> BundleRequirements(handles = listOf(name))
    is PropValue.CallbackValue -> BundleRequirements(capabilities = listOf(capability))

    is PropValue.PainterResourceValue -> BundleRequirements(resources = listOf(key))
    is PropValue.StringResourceValue -> BundleRequirements(resources = listOf(key))

    is PropValue.ModifierValue ->
        operations.flatMap { operation -> operation.arguments.values }
            .map { argument -> argument.requirements() }
            .merge()

    is PropValue.ListValue -> elements.map { element -> element.requirements() }.merge()

    is PropValue.NullValue,
    is PropValue.BoolValue,
    is PropValue.IntValue,
    is PropValue.LongValue,
    is PropValue.FloatValue,
    is PropValue.DoubleValue,
    is PropValue.StringValue,
    is PropValue.DpValue,
    is PropValue.ColorValue,
    is PropValue.ThemeColorValue,
    is PropValue.ShapeValue,
    -> BundleRequirements()
}

private fun List<BundleUiNode>.requirements(): BundleRequirements =
    map { node -> node.requirements() }.merge()

private fun List<BundleRequirements>.merge(): BundleRequirements =
    fold(BundleRequirements()) { total, next -> total + next }

/** One argument a bundle gave to one native component. */
data class AdapterArgument(val adapter: String, val name: String)

/** The names a bundle used, deduplicated when they are read back. */
data class BundleRequirements(
    val adapters: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val handles: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
    val arguments: List<AdapterArgument> = emptyList(),
) {
    operator fun plus(other: BundleRequirements): BundleRequirements = BundleRequirements(
        adapters = adapters + other.adapters,
        capabilities = capabilities + other.capabilities,
        handles = handles + other.handles,
        resources = resources + other.resources,
        arguments = arguments + other.arguments,
    )
}
