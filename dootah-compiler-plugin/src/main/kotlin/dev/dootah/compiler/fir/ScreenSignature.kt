package dev.dootah.compiler.fir

import dev.dootah.compiler.model.BundleParameter
import dev.dootah.compiler.model.BundleType
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.name.FqName

/**
 * What one parameter of a `@Bundlable` screen is, to Dootah.
 *
 * The four kinds are handled in four different ways, and the difference matters
 * for security as much as for capability: a serialisable value crosses into the
 * bundle, a callback is a name the bundle may ask the app to invoke, a modifier
 * and a native value never leave the app at all.
 */
internal sealed interface ScreenParameter {

    val name: String

    /** A value the bundle receives: String, Int or Boolean, possibly nullable. */
    data class Value(
        override val name: String,
        val type: BundleType,
        val isNullable: Boolean,
    ) : ScreenParameter

    /**
     * A `(T...) -> Unit` the bundle may ask the app to call, by name.
     *
     * [parameterTypes] are the values the bundle has to send with the call, and
     * every one of them is a type Dootah can carry. A callback taking anything
     * else stays a [NativeOnly] value: the bundle could name it but could not
     * produce a value to hand it, and a call it cannot complete is worse than no
     * call at all.
     */
    data class Callback(
        override val name: String,
        val parameterTypes: List<BundleType>,
    ) : ScreenParameter

    /** The `Modifier` the caller passed, spliced into the layout by position. */
    data class LayoutModifier(override val name: String) : ScreenParameter

    /**
     * A value Dootah cannot carry.
     *
     * Not an error on its own. A view model, a data class, an image loader: the
     * screen may still be bundled as long as every use of it sits inside a
     * native slot, where the value never leaves the app.
     */
    data class NativeOnly(
        override val name: String,
        val typeName: String,
    ) : ScreenParameter
}

/** Classifies a screen's parameters in declaration order. */
internal fun FirNamedFunction.screenParameters(): List<ScreenParameter> =
    valueParameters.map { parameter -> parameter.classify() }

private fun FirValueParameter.classify(): ScreenParameter {

    val name = this.name.asString()
    val type = returnTypeRef.coneTypeSafe<ConeKotlinType>()

    bundleTypeOf(type)?.let { bundleType ->
        return ScreenParameter.Value(
            name = name,
            type = bundleType,
            isNullable = type?.isMarkedNullable == true,
        )
    }

    val typeName = type?.classId?.asSingleFqName()

    if (typeName == SupportedCatalog.MODIFIER_TYPE) {
        return ScreenParameter.LayoutModifier(name)
    }

    type?.unitFunctionParameterTypes()?.let { parameterTypes ->
        return ScreenParameter.Callback(name, parameterTypes)
    }

    return ScreenParameter.NativeOnly(
        name = name,
        typeName = typeName?.asString() ?: "an unknown type",
    )
}

/**
 * The values a `(T...) -> Unit` parameter takes, or null if it is not one.
 *
 * Every parameter type has to be one Dootah can carry, because a bundle invoking
 * this callback has to produce each value itself. That is the whole widening
 * over `() -> Unit`: the bundle computes a value it already knows how to compute
 * and the app receives it as the type its own source declared. Nothing about the
 * app's memory becomes reachable -- a `(Post) -> Unit` is still a native-only
 * value, because a bundle has no way to make a `Post`.
 */
private fun ConeKotlinType.unitFunctionParameterTypes(): List<BundleType>? {

    val name = classId?.asSingleFqName()?.asString() ?: return null
    if (!name.startsWith(FUNCTION_PREFIX)) return null
    if (name.removePrefix(FUNCTION_PREFIX).toIntOrNull() == null) return null

    val arguments = typeArguments.map { argument -> argument as? ConeKotlinType }

    if (arguments.lastOrNull()?.classId?.asSingleFqName() != UNIT) return null

    return arguments.dropLast(1).map { argument ->
        if (argument == null || argument.isMarkedNullable) return null
        bundleTypeOf(argument) ?: return null
    }
}

internal fun bundleTypeOf(type: ConeKotlinType?): BundleType? =
    when (type?.classId?.asSingleFqName()?.asString()) {
        "kotlin.Int" -> BundleType.INT
        "kotlin.String" -> BundleType.STRING
        "kotlin.Boolean" -> BundleType.BOOLEAN
        "kotlin.Long" -> BundleType.LONG
        "kotlin.Float" -> BundleType.FLOAT
        "kotlin.Double" -> BundleType.DOUBLE
        else -> null
    }

/** The serialisable parameters, in declaration order, as the model records them. */
internal fun List<ScreenParameter>.valueParameters(): List<BundleParameter> =
    filterIsInstance<ScreenParameter.Value>().map { parameter ->
        BundleParameter(
            name = parameter.name,
            type = parameter.type,
            isNullable = parameter.isNullable,
        )
    }

internal fun List<ScreenParameter>.callbacks(): List<ScreenParameter.Callback> =
    filterIsInstance<ScreenParameter.Callback>()

internal fun List<ScreenParameter>.modifierName(): String? =
    filterIsInstance<ScreenParameter.LayoutModifier>().singleOrNull()?.name

private const val FUNCTION_PREFIX = "kotlin.Function"
private val UNIT = FqName("kotlin.Unit")
