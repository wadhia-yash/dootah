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

    /** A `() -> Unit` the bundle may ask the app to call, by name. */
    data class Callback(override val name: String) : ScreenParameter

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

    if (type != null && type.isNoArgumentUnitFunction()) {
        return ScreenParameter.Callback(name)
    }

    return ScreenParameter.NativeOnly(
        name = name,
        typeName = typeName?.asString() ?: "an unknown type",
    )
}

/**
 * Whether this is `() -> Unit`.
 *
 * Only that shape. A callback taking arguments would need those arguments to
 * cross back from the bundle, which is a wider hole than Dootah's command model
 * opens today, so it is left as a native-only value until a real screen needs it.
 */
private fun ConeKotlinType.isNoArgumentUnitFunction(): Boolean {

    val classId = classId?.asSingleFqName() ?: return false
    if (classId != FUNCTION_ZERO) return false

    val returnType = (typeArguments.singleOrNull() as? ConeKotlinType)
        ?.classId
        ?.asSingleFqName()

    return returnType == UNIT
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

internal fun List<ScreenParameter>.callbackNames(): List<String> =
    filterIsInstance<ScreenParameter.Callback>().map { it.name }

internal fun List<ScreenParameter>.modifierName(): String? =
    filterIsInstance<ScreenParameter.LayoutModifier>().singleOrNull()?.name

private val FUNCTION_ZERO = FqName("kotlin.Function0")
private val UNIT = FqName("kotlin.Unit")
