package com.dootah.ui

import dev.dootah.contract.Dimension
import dev.dootah.contract.LayoutArrangement
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

    /**
     * Alignment and arrangement arrive as names from a closed vocabulary, never
     * as objects: an `Alignment` computes a position rather than holding one, so
     * there is nothing in it to send. The renderer resolves a name against the
     * value this build already has, from an exhaustive `when`.
     *
     * Null is not "centre" or "start" -- it is "the bundle did not say", and the
     * renderer then leaves Compose's own default alone. A bundle asserting what
     * it believed the default to be would freeze that default across a version
     * boundary it does not control.
     */
    data class Column(
        override val modifiers: List<BundleUiModifier>,
        override val children: List<BundleUiNode>,
        val horizontalAlignment: String? = null,
        val verticalArrangement: LayoutArrangement? = null,
    ) : Container

    data class Row(
        override val modifiers: List<BundleUiModifier>,
        override val children: List<BundleUiNode>,
        val verticalAlignment: String? = null,
        val horizontalArrangement: LayoutArrangement? = null,
    ) : Container

    data class Box(
        override val modifiers: List<BundleUiModifier>,
        override val children: List<BundleUiNode>,
        val contentAlignment: String? = null,
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

        /**
         * Slots this component builds rather than draws.
         *
         * A `LazyColumn` is given a scope, not children, and the bundle has
         * never held one. What arrives is the list of entries; the app runs the
         * real container and declares each entry against the real scope.
         */
        val entries: Map<String, List<BundleUiEntry>> = emptyMap(),
    ) : BundleUiNode
}

/**
 * One declaration in a native container's builder.
 *
 * Closed, and matched arm for arm by the renderer. Nothing here describes how
 * many entries are composed, when, or in what order they are discarded -- that
 * is Compose's, and stays Compose's.
 */
sealed interface BundleUiEntry {

    /** `item { ... }`, holding UI the bundle describes. */
    data class Item(val children: List<BundleUiNode>) : BundleUiEntry

    /** Entries the app declares for itself, performed against the real scope. */
    data class Region(val adapterId: String) : BundleUiEntry
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

    /**
     * Lengths are a [Dimension] rather than a number, because a length may be
     * the app's own: `MaterialTheme.padding.small` arrives as a name this build
     * resolves against the value the screen itself reads. A fraction and a
     * weight stay numbers -- both are ratios the bundle decides.
     */
    data class Padding(
        val start: Dimension,
        val top: Dimension,
        val end: Dimension,
        val bottom: Dimension,
    ) : BundleUiModifier

    data class FillMaxWidth(val fraction: Float) : BundleUiModifier

    data class FillMaxHeight(val fraction: Float) : BundleUiModifier

    data class FillMaxSize(val fraction: Float) : BundleUiModifier

    data class Size(val width: Dimension, val height: Dimension) : BundleUiModifier

    data class Width(val value: Dimension) : BundleUiModifier

    data class Height(val value: Dimension) : BundleUiModifier

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

    // A container's own modifiers and arrangement are collected too. Before a
    // length could be anchored they held only numbers and there was nothing in
    // them to require; now one can name a value the APK has to have, and a name
    // that went uncollected here would reach a device unchecked.
    is BundleUiNode.Container -> layoutRequirements() + children.requirements()

    is BundleUiNode.Fragment -> children.requirements()

    is BundleUiNode.Component ->
        BundleRequirements(
            adapters = listOf(adapterId),
            arguments = props.keys.map { name -> AdapterArgument(adapterId, name) },
        ) +
            props.values.map { value -> value.requirements() }.merge() +
            children.values.flatten().requirements() +
            entries.values.flatten().map { entry -> entry.requirements() }.merge()

    is BundleUiNode.Text -> modifiers.requirements()

    is BundleUiNode.Button -> modifiers.requirements()
}

/** What a container needs beyond its children: its modifiers and arrangement. */
private fun BundleUiNode.Container.layoutRequirements(): BundleRequirements {

    val arrangement = when (this) {
        is BundleUiNode.Column -> verticalArrangement
        is BundleUiNode.Row -> horizontalArrangement
        is BundleUiNode.Box -> null
    }

    return modifiers.requirements() + (arrangement?.spacing?.requirements() ?: BundleRequirements())
}

@JvmName("modifierRequirements")
private fun List<BundleUiModifier>.requirements(): BundleRequirements =
    map { modifier -> modifier.requirements() }.merge()

/** Every length one modifier holds, and what naming it asks of the app. */
private fun BundleUiModifier.requirements(): BundleRequirements = when (this) {

    is BundleUiModifier.Padding ->
        start.requirements() + top.requirements() + end.requirements() + bottom.requirements()

    is BundleUiModifier.Size -> width.requirements() + height.requirements()
    is BundleUiModifier.Width -> value.requirements()
    is BundleUiModifier.Height -> value.requirements()

    is BundleUiModifier.Inherited,
    is BundleUiModifier.FillMaxWidth,
    is BundleUiModifier.FillMaxHeight,
    is BundleUiModifier.FillMaxSize,
    is BundleUiModifier.Weight,
    is BundleUiModifier.Background,
    -> BundleRequirements()
}

private fun Dimension.requirements(): BundleRequirements =
    anchor?.let { name -> BundleRequirements(anchors = listOf(name)) } ?: BundleRequirements()

/** What one prop needs to exist in the APK before it can be resolved. */
private fun PropValue.requirements(): BundleRequirements = when (this) {

    is PropValue.HandleValue -> BundleRequirements(handles = listOf(name))
    is PropValue.StateValue -> BundleRequirements(handles = listOf(name))
    is PropValue.CallbackValue -> BundleRequirements(capabilities = listOf(capability))

    is PropValue.AnchorValue -> BundleRequirements(anchors = listOf(anchor))

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
    val anchors: List<String> = emptyList(),
    val builders: List<String> = emptyList(),
    val arguments: List<AdapterArgument> = emptyList(),
) {
    operator fun plus(other: BundleRequirements): BundleRequirements = BundleRequirements(
        adapters = adapters + other.adapters,
        capabilities = capabilities + other.capabilities,
        handles = handles + other.handles,
        resources = resources + other.resources,
        anchors = anchors + other.anchors,
        builders = builders + other.builders,
        arguments = arguments + other.arguments,
    )
}

/** What one builder entry needs the installed app to have. */
private fun BundleUiEntry.requirements(): BundleRequirements = when (this) {
    is BundleUiEntry.Item -> children.requirements()
    is BundleUiEntry.Region -> BundleRequirements(builders = listOf(adapterId))
}
