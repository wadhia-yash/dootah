package ui

/**
 * A layout instruction a bundle can attach to a node.
 *
 * Closed, and deliberately far smaller than Compose's `Modifier` surface. Each
 * entry exists because a real screen used it; the renderer maps each one onto
 * the Compose modifier it came from, so a bundle cannot describe a layout the
 * installed app could not draw.
 *
 * Order is significant. Compose modifiers compose left to right, so a padding
 * before a background paints differently from a padding after it. The list is
 * kept in source order end to end -- lowering, JSON, renderer -- rather than
 * normalised into a set of properties, which would silently change layouts.
 *
 * Lengths are a [DimensionNode] rather than a number, because a length may be
 * the app's rather than this bundle's. A fraction and a weight stay numbers:
 * both are ratios the bundle decides, and neither has an app-owned form.
 */
sealed interface BundleModifier {

    /**
     * The `Modifier` the native caller passed into the screen.
     *
     * A remote bundle cannot represent an arbitrary native modifier, and it does
     * not need to: the actual instance stays on the Android side and this marker
     * says where in the chain to splice it in.
     */
    data object Inherited : BundleModifier

    data class Padding(
        val start: DimensionNode,
        val top: DimensionNode,
        val end: DimensionNode,
        val bottom: DimensionNode,
    ) : BundleModifier

    data class FillMaxWidth(val fraction: Double) : BundleModifier

    data class FillMaxHeight(val fraction: Double) : BundleModifier

    data class FillMaxSize(val fraction: Double) : BundleModifier

    data class Size(val width: DimensionNode, val height: DimensionNode) : BundleModifier

    data class Width(val value: DimensionNode) : BundleModifier

    data class Height(val value: DimensionNode) : BundleModifier

    /** Only meaningful inside a Column or Row, which is checked when lowering. */
    data class Weight(val value: Double) : BundleModifier

    /** A packed ARGB colour, as `Color(0xAARRGGBB)` produces. */
    data class Background(val color: Long) : BundleModifier
}
