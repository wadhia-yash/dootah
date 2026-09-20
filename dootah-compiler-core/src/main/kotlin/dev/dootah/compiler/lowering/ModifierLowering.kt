package dev.dootah.compiler.lowering

import dev.dootah.compiler.source.*
import dev.dootah.compiler.model.BundleModifier
import dev.dootah.contract.Dimension

/** Reports something Dootah cannot bundle, at a source position. */
fun interface Rejector {
    fun reject(
        offset: Int?,
        found: String,
        remedy: String,
        code: RejectionCode,
        detail: String?,
    )
}

/**
 * Reads a `Modifier` chain into the ordered list a bundle carries.
 *
 * A chain is a left-leaning spine of extension calls ending at `Modifier` or at
 * the screen's own `modifier` parameter, so it is read by walking receivers and
 * then reversing -- source order is what the renderer replays, and reversing it
 * would quietly change layouts.
 *
 * Sizes must be literal: `16.dp` is read, `spacing` is not. A computed size is a
 * value the remote side would have to evaluate, and there is no reason to grow
 * the expression surface into layout until a real screen asks for it.
 */
class ModifierLowering(
    private val rejector: Rejector,
    private val modifierParameterName: String?,
    private val onAnchor: (String) -> Unit = {},
) {

    /** Null when the chain contains something Dootah cannot carry. */
    fun lower(expression: SourceExpression): List<BundleModifier>? {

        val reversed = mutableListOf<BundleModifier>()
        var current: SourceExpression? = expression

        while (true) {

            when (val node = current) {

                // `Modifier`, where a chain written from scratch bottoms out.
                is SourceResolvedQualifier -> {
                    if (node.classId?.asSingleFqName() != SupportedCatalog.MODIFIER_TYPE) {
                        reject(
                            node,
                            "a modifier chain starting at ${node.classId}",
                            code = RejectionCode.UNREADABLE_MODIFIER,
                            detail = node.classId?.asSingleFqName()?.asString(),
                        )
                        return null
                    }
                    return reversed.asReversed().toList()
                }

                is SourcePropertyAccessExpression -> {
                    val name = node.resolvedName()

                    // The screen's own `modifier` parameter: the native value
                    // stays native and the bundle records where it belongs.
                    if (name != null && name == modifierParameterName) {
                        reversed += BundleModifier.Inherited
                        return reversed.asReversed().toList()
                    }

                    if (node.resolvedCallableName() == SupportedCatalog.MODIFIER_COMPANION) {
                        return reversed.asReversed().toList()
                    }

                    reject(
                        node,
                        "`${name ?: "?"}` in a modifier chain",
                        code = RejectionCode.UNSUPPORTED_MODIFIER,
                        detail = name,
                    )
                    return null
                }

                is SourceFunctionCall -> {
                    val entry = lowerEntry(node) ?: return null
                    reversed += entry
                    current = node.explicitReceiver
                }

                else -> {
                    reject(
                        node,
                        "a modifier Dootah could not read",
                        code = RejectionCode.UNREADABLE_MODIFIER,
                    )
                    return null
                }
            }

            if (current == null) {
                reject(
                    expression,
                    "a modifier chain with no Modifier at its root",
                    code = RejectionCode.UNREADABLE_MODIFIER,
                )
                return null
            }
        }
    }

    private fun lowerEntry(call: SourceFunctionCall): BundleModifier? {

        val callable = call.resolvedCallableName()

        return when (callable) {

            SupportedCatalog.PADDING -> lowerPadding(call)

            SupportedCatalog.FILL_MAX_WIDTH ->
                BundleModifier.FillMaxWidth(call.fraction() ?: return null)

            SupportedCatalog.FILL_MAX_HEIGHT ->
                BundleModifier.FillMaxHeight(call.fraction() ?: return null)

            SupportedCatalog.FILL_MAX_SIZE ->
                BundleModifier.FillMaxSize(call.fraction() ?: return null)

            SupportedCatalog.SIZE -> lowerSize(call)

            SupportedCatalog.WIDTH ->
                BundleModifier.Width(call.singleDimension("width") ?: return null)

            SupportedCatalog.HEIGHT ->
                BundleModifier.Height(call.singleDimension("height") ?: return null)

            SupportedCatalog.COLUMN_WEIGHT, SupportedCatalog.ROW_WEIGHT ->
                BundleModifier.Weight(call.weight() ?: return null)

            SupportedCatalog.BACKGROUND -> lowerBackground(call)

            else -> {
                reject(
                    call,
                    "the modifier `${callable?.shortName()?.asString() ?: "unknown"}`",
                    code = RejectionCode.UNSUPPORTED_MODIFIER,
                    detail = callable?.asString(),
                )
                null
            }
        }
    }

    private fun lowerPadding(call: SourceFunctionCall): BundleModifier? {

        val mapping = call.resolvedArgumentMapping ?: run {
            reject(call, "a padding Dootah could not read", code = RejectionCode.UNREADABLE_MODIFIER, detail = "padding")
            return null
        }

        var start = Dimension.ZERO
        var top = Dimension.ZERO
        var end = Dimension.ZERO
        var bottom = Dimension.ZERO

        for ((expression, parameter) in mapping) {

            val value = dimensionOf(expression) ?: return null

            when (parameter.name.asString()) {
                "all" -> { start = value; top = value; end = value; bottom = value }
                "horizontal" -> { start = value; end = value }
                "vertical" -> { top = value; bottom = value }
                "start" -> start = value
                "top" -> top = value
                "end" -> end = value
                "bottom" -> bottom = value
                else -> {
                    reject(
                        expression,
                        "the padding argument `${parameter.name.asString()}`",
                        code = RejectionCode.UNSUPPORTED_MODIFIER_ARGUMENT,
                        detail = "padding.${parameter.name.asString()}",
                        remedy = "Dootah reads padding written as all, " +
                            "horizontal/vertical, or start/top/end/bottom.",
                    )
                    return null
                }
            }
        }

        return BundleModifier.Padding(start = start, top = top, end = end, bottom = bottom)
    }

    private fun lowerSize(call: SourceFunctionCall): BundleModifier? {

        val mapping = call.resolvedArgumentMapping ?: run {
            reject(call, "a size Dootah could not read", code = RejectionCode.UNREADABLE_MODIFIER, detail = "size")
            return null
        }

        val values = mapping.entries.associate { (expression, parameter) ->
            parameter.name.asString() to expression
        }

        // size(20.dp) sets both sides; size(width, height) sets each.
        values["size"]?.let { square ->
            val value = dimensionOf(square) ?: return null
            return BundleModifier.Size(width = value, height = value)
        }

        val width = values["width"]?.let { dimensionOf(it) } ?: return null
        val height = values["height"]?.let { dimensionOf(it) } ?: return null

        return BundleModifier.Size(width = width, height = height)
    }

    private fun lowerBackground(call: SourceFunctionCall): BundleModifier? {

        val mapping = call.resolvedArgumentMapping ?: run {
            reject(call, "a background Dootah could not read", code = RejectionCode.UNREADABLE_MODIFIER, detail = "background")
            return null
        }

        val unsupported = mapping.entries.firstOrNull { (_, parameter) ->
            parameter.name.asString() != "color"
        }

        if (unsupported != null) {
            reject(
                call,
                "the background argument `${unsupported.value.name.asString()}`",
                code = RejectionCode.UNSUPPORTED_MODIFIER_ARGUMENT,
                detail = "background.${unsupported.value.name.asString()}",
                remedy = "Dootah reads background(Color(0xAARRGGBB)) only -- " +
                    "no shape, no brush.",
            )
            return null
        }

        val color = colorOf(mapping.keys.first()) ?: return null

        return BundleModifier.Background(color)
    }

    /**
     * Reads `Color(0xAARRGGBB)`.
     *
     * One form rather than a table of named colours. A named colour would have
     * to be resolved to the value the installed renderer would produce, and a
     * table that drifts from Compose's is worse than not accepting one.
     */
    private fun colorOf(expression: SourceExpression): Long? {

        val call = expression as? SourceFunctionCall
        val packed = call
            ?.takeIf { it.resolvedCallableName() == SupportedCatalog.COLOR_FUNCTION }
            ?.arguments
            ?.singleOrNull()
            ?.let { (it as? SourceLiteralExpression)?.value }

        val value = when (packed) {
            is Long -> packed
            is Int -> packed.toLong()
            else -> null
        }

        if (value == null) {
            reject(
                expression,
                "a colour Dootah could not read",
                code = RejectionCode.UNSUPPORTED_COLOR,
                remedy = "Write background(Color(0xFF2196F3)). Named colours and " +
                    "brushes are not bundled.",
            )
            return null
        }

        return value
    }

    private fun SourceFunctionCall.fraction(): Double? {

        val argument = arguments.singleOrNull() ?: return 1.0

        return numberOf(argument)
    }

    private fun SourceFunctionCall.weight(): Double? {

        val mapping = resolvedArgumentMapping ?: return null

        val weight = mapping.entries
            .firstOrNull { (_, parameter) -> parameter.name.asString() == "weight" }
            ?.key

        if (weight == null) {
            reject(this, "a weight Dootah could not read", code = RejectionCode.UNREADABLE_MODIFIER, detail = "weight")
            return null
        }

        return numberOf(weight)
    }

    private fun SourceFunctionCall.singleDimension(parameterName: String): Dimension? {

        val mapping = resolvedArgumentMapping ?: return null

        val argument = mapping.entries
            .firstOrNull { (_, parameter) -> parameter.name.asString() == parameterName }
            ?.key
            ?: arguments.singleOrNull()

        if (argument == null) {
            reject(
                this,
                "a $parameterName Dootah could not read",
                code = RejectionCode.UNREADABLE_MODIFIER,
                detail = parameterName,
            )
            return null
        }

        return dimensionOf(argument)
    }

    /**
     * Reads `16.dp`, or a length the app owns and the bundle names.
     *
     * The second is the commonest real shape -- `MaterialTheme.padding.small` --
     * and naming it rather than reading its value is what keeps a bundled screen
     * following the app's spacing when a later release retunes it.
     */
    private fun dimensionOf(expression: SourceExpression): Dimension? {

        DpLiteral.read(expression)?.let { return Dimension.of(it) }

        AnchorLowering.dimension(expression)?.let { anchored ->
            onAnchor(anchored.anchor!!)
            return anchored
        }

        reject(
            expression,
            "a size Dootah could not read",
            code = RejectionCode.UNSUPPORTED_MODIFIER_ARGUMENT,
            detail = "dp",
            remedy = "Write a size as a literal like 16.dp, or as a property the app " +
                "owns like MaterialTheme.padding.small. A computed size is not bundled.",
        )

        return null
    }

    private fun numberOf(expression: SourceExpression): Double? {

        val number = DpLiteral.number(expression)

        if (number == null) {
            reject(
                expression,
                "a non-literal number in a modifier",
                code = RejectionCode.UNSUPPORTED_MODIFIER_ARGUMENT,
                detail = "computed",
                remedy = "Write sizes as literals, like 16.dp. A computed size is " +
                    "not bundled.",
            )
            return null
        }

        return number
    }

    private fun reject(
        node: SourceExpression?,
        found: String,
        code: RejectionCode,
        detail: String? = null,
        remedy: String = "Dootah bundles these modifiers: " +
            SupportedCatalog.SUPPORTED_MODIFIERS.joinToString(", ") + ". " +
            "Keep the rest of the styling on a native component.",
    ) {
        rejector.reject(node?.source?.startOffset, found, remedy, code, detail)
    }
}

fun SourceFunctionCall.resolvedCallableName(): FqName? =
    calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()

fun SourcePropertyAccessExpression.resolvedCallableName(): FqName? =
    calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()

/**
 * The name this access refers to.
 *
 * Read off the resolved reference rather than the symbol's callable id, because
 * a local `val` has no meaningful callable id -- and locals are most of what a
 * screen reads.
 */
fun SourcePropertyAccessExpression.resolvedName(): String? =
    (calleeReference as? SourceResolvedNamedReference)?.name?.asString()
        ?: calleeReference.toResolvedCallableSymbol()?.callableId?.callableName?.asString()
