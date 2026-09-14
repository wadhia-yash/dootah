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

    is ColumnNode -> container(
        "column", modifiers, children,
        alignmentField("horizontalAlignment", horizontalAlignment) +
            arrangementField("verticalArrangement", verticalArrangement),
    )

    is RowNode -> container(
        "row", modifiers, children,
        alignmentField("verticalAlignment", verticalAlignment) +
            arrangementField("horizontalArrangement", horizontalArrangement),
    )

    is BoxNode -> container(
        "box", modifiers, children,
        alignmentField("contentAlignment", contentAlignment),
    )

    is TextNode ->
        "{\"type\":\"text\",\"text\":\"${text.escapeJson()}\"" +
            modifiersField(modifiers) + "}"

    is ButtonNode ->
        "{\"type\":\"button\",\"text\":\"${text.escapeJson()}\"," +
            "\"action\":\"${action.escapeJson()}\"" +
            modifiersField(modifiers) + "}"

    is ComponentNode ->
        "{\"type\":\"component\",\"adapter\":\"${adapter.escapeJson()}\"" +
            propsField(props) + slotsField(children) + "}"

    is FragmentNode ->
        "{\"type\":\"fragment\",\"children\":[" +
            children.joinToString(",") { it.toJson() } + "]}"
}

private fun propsField(props: Map<String, PropNode>): String =
    if (props.isEmpty()) ""
    else ",\"props\":{" + props.entries.joinToString(",") { (name, value) ->
        "\"${name.escapeJson()}\":" + value.toJson()
    } + "}"

private fun slotsField(children: Map<String, List<BundleNode>>): String =
    if (children.isEmpty()) ""
    else ",\"slots\":{" + children.entries.joinToString(",") { (name, nodes) ->
        "\"${name.escapeJson()}\":[" + nodes.joinToString(",") { it.toJson() } + "]"
    } + "}"

/**
 * Renders one argument to a native component.
 *
 * Exhaustive with no `else`, on both sides of the wire: a kind added here and
 * not in the app's parser would be refused there, and a kind the app knows and
 * this does not could never be sent.
 */
private fun PropNode.toJson(): String = when (this) {

    is NullProp -> "{\"k\":\"null\"}"
    is BoolProp -> "{\"k\":\"bool\",\"v\":$value}"
    is IntProp -> "{\"k\":\"int\",\"v\":$value}"
    is LongProp -> "{\"k\":\"long\",\"v\":\"${value.escapeJson()}\"}"
    is FloatProp -> "{\"k\":\"float\",\"v\":$value}"
    is DoubleProp -> "{\"k\":\"double\",\"v\":$value}"
    is StringProp -> "{\"k\":\"string\",\"v\":\"${value.escapeJson()}\"}"

    is DpProp -> "{\"k\":\"dp\",\"v\":$value}"
    is ColorProp -> "{\"k\":\"color\",\"v\":$argb}"
    is ThemeColorProp -> "{\"k\":\"themeColor\",\"token\":\"${token.escapeJson()}\"}"
    is ShapeProp -> "{\"k\":\"shape\",\"token\":\"${token.escapeJson()}\"}"

    is PainterResourceProp -> "{\"k\":\"painterRes\",\"key\":\"${key.escapeJson()}\"}"
    is StringResourceProp -> "{\"k\":\"stringRes\",\"key\":\"${key.escapeJson()}\"}"

    is ModifierProp ->
        "{\"k\":\"modifier\",\"ops\":[" +
            operations.joinToString(",") { it.toJson() } + "]}"

    is ListProp ->
        "{\"k\":\"list\",\"items\":[" +
            elements.joinToString(",") { it.toJson() } + "]}"

    is HandleProp -> "{\"k\":\"handle\",\"name\":\"${name.escapeJson()}\"}"
    is StateProp -> "{\"k\":\"state\",\"name\":\"${name.escapeJson()}\"}"

    is CallbackProp ->
        "{\"k\":\"callback\",\"id\":\"${id.escapeJson()}\",\"arity\":$arity}"
}

private fun ModifierOpNode.toJson(): String =
    "{\"op\":\"${op.escapeJson()}\"" +
        (if (arguments.isEmpty()) "" else ",\"args\":{" +
            arguments.entries.joinToString(",") { (name, value) ->
                "\"${name.escapeJson()}\":" + value.toJson()
            } + "}") + "}"

private fun container(
    type: String,
    modifiers: List<BundleModifier>,
    children: List<BundleNode>,
    layout: String = "",
): String =
    "{\"type\":\"$type\"" +
        modifiersField(modifiers) +
        layout +
        ",\"children\":[" + children.joinToString(",") { it.toJson() } + "]}"

/**
 * Written only when the layout had one.
 *
 * An absent field and a field naming Compose's default are different things: the
 * first leaves the app's own default in place, and the second asserts what the
 * default is from the other side of a version boundary.
 */
/**
 * A length, as a number or as the name of one the app owns.
 *
 * Two shapes on the wire rather than one object with an empty half, because the
 * number case is every length written as a literal and it is worth keeping it
 * as small as it has always been. The parser tells them apart by their JSON
 * type, which cannot be ambiguous.
 */
private fun DimensionNode.toJson(): String =
    if (anchor != null) "{\"anchor\":\"${anchor.escapeJson()}\"}" else "$value"

private fun alignmentField(name: String, token: String?): String =
    if (token == null) "" else ",\"$name\":\"${token.escapeJson()}\""

private fun arrangementField(name: String, arrangement: ArrangementNode?): String {

    if (arrangement == null) return ""

    val spacing = arrangement.spacing?.let { ",\"space\":${it.toJson()}" } ?: ""

    return ",\"$name\":{\"token\":\"${arrangement.token.escapeJson()}\"$spacing}"
}

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
        "{\"type\":\"padding\",\"start\":${start.toJson()},\"top\":${top.toJson()}," +
            "\"end\":${end.toJson()},\"bottom\":${bottom.toJson()}}"

    is BundleModifier.FillMaxWidth -> "{\"type\":\"fillMaxWidth\",\"fraction\":$fraction}"
    is BundleModifier.FillMaxHeight -> "{\"type\":\"fillMaxHeight\",\"fraction\":$fraction}"
    is BundleModifier.FillMaxSize -> "{\"type\":\"fillMaxSize\",\"fraction\":$fraction}"

    is BundleModifier.Size ->
        "{\"type\":\"size\",\"width\":${width.toJson()},\"height\":${height.toJson()}}"
    is BundleModifier.Width -> "{\"type\":\"width\",\"value\":${value.toJson()}}"
    is BundleModifier.Height -> "{\"type\":\"height\",\"value\":${value.toJson()}}"
    is BundleModifier.Weight -> "{\"type\":\"weight\",\"value\":$value}"

    is BundleModifier.Background -> "{\"type\":\"background\",\"color\":$color}"
}
