package ui

import json.escapeJson

/**
 * Renders a node tree as the JSON the installed app parses.
 *
 * Hand-built rather than reflective: the bundle is compiled with dead code
 * elimination, and a serialization library would either be excluded or would
 * dominate the download. The shape is small enough that writing it out is
 * cheaper than the dependency.
 */
fun BundleNode.toJson(): String = when (this) {

    is ColumnNode -> container("column", modifiers, children)
    is RowNode -> container("row", modifiers, children)
    is BoxNode -> container("box", modifiers, children)

    is TextNode ->
        "{\"type\":\"text\",\"text\":\"${text.escapeJson()}\"" +
            modifiersField(modifiers) + "}"

    is ButtonNode ->
        "{\"type\":\"button\",\"text\":\"${text.escapeJson()}\"," +
            "\"action\":\"${action.escapeJson()}\"" +
            modifiersField(modifiers) + "}"

    is NativeSlotNode ->
        "{\"type\":\"native\",\"slot\":\"${slot.escapeJson()}\"}"

    is FragmentNode ->
        "{\"type\":\"fragment\",\"children\":[" +
            children.joinToString(",") { it.toJson() } + "]}"
}

private fun container(
    type: String,
    modifiers: List<BundleModifier>,
    children: List<BundleNode>,
): String =
    "{\"type\":\"$type\"" +
        modifiersField(modifiers) +
        ",\"children\":[" + children.joinToString(",") { it.toJson() } + "]}"

/**
 * Emitted only when there is something to emit, so an unmodified node produces
 * the same JSON it did before modifiers existed.
 */
private fun modifiersField(modifiers: List<BundleModifier>): String =
    if (modifiers.isEmpty()) ""
    else ",\"modifiers\":[" + modifiers.joinToString(",") { it.toJson() } + "]"

private fun BundleModifier.toJson(): String = when (this) {

    is BundleModifier.Inherited -> "{\"type\":\"inherited\"}"

    is BundleModifier.Padding ->
        "{\"type\":\"padding\",\"start\":$start,\"top\":$top," +
            "\"end\":$end,\"bottom\":$bottom}"

    is BundleModifier.FillMaxWidth -> "{\"type\":\"fillMaxWidth\",\"fraction\":$fraction}"
    is BundleModifier.FillMaxHeight -> "{\"type\":\"fillMaxHeight\",\"fraction\":$fraction}"
    is BundleModifier.FillMaxSize -> "{\"type\":\"fillMaxSize\",\"fraction\":$fraction}"

    is BundleModifier.Size -> "{\"type\":\"size\",\"width\":$width,\"height\":$height}"
    is BundleModifier.Width -> "{\"type\":\"width\",\"value\":$value}"
    is BundleModifier.Height -> "{\"type\":\"height\",\"value\":$value}"
    is BundleModifier.Weight -> "{\"type\":\"weight\",\"value\":$value}"

    is BundleModifier.Background -> "{\"type\":\"background\",\"color\":$color}"
}
