package com.dootah.ui

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
     * A composable that shipped in the APK, named by slot.
     *
     * The bundle decides where it goes; the app decides what it is. Nothing
     * about the component -- not its arguments, not its identity beyond this
     * slot name -- comes from the bundle.
     */
    data class NativeSlot(
        val slot: String,
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
 * Every native component this tree asks the app to draw.
 *
 * Exhaustive on purpose, with no `else`. This is what the app checks a bundle
 * against before it draws anything, so a node type that held children and was
 * not listed here would report no components beneath it -- and the one guard
 * between a bundle built against a different APK and a hole in a shipped screen
 * would pass without looking. Adding a node type has to stop compiling here.
 */
fun BundleUiNode.nativeSlots(): List<String> = when (this) {
    is BundleUiNode.Container -> children.flatMap { child -> child.nativeSlots() }
    is BundleUiNode.NativeSlot -> listOf(slot)
    is BundleUiNode.Fragment -> children.flatMap { child -> child.nativeSlots() }
    is BundleUiNode.Text -> emptyList()
    is BundleUiNode.Button -> emptyList()
}
