package dev.dootah.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.name.FqName

/**
 * What a screen's parameters are, as the interception needs to see them.
 *
 * Deliberately the same four kinds the extraction pass classifies, decided by
 * the same rule -- the parameter's type -- so the app and the bundle agree on
 * which values travel, which stay native, and what the bundle may ask for by
 * name. Disagreement here would show up as a screen rendering with the wrong
 * arguments, which is exactly the failure worth designing out.
 */
internal class ScreenBinding private constructor(
    val values: List<IrValueParameter>,
    val callbacks: List<IrValueParameter>,
    val modifier: IrValueParameter?,
) {

    val valueNames: String get() = values.joinToString(",") { it.name.asString() }

    val callbackNames: String get() = callbacks.joinToString(",") { it.name.asString() }

    companion object {

        @OptIn(UnsafeDuringIrConstructionAPI::class)
        fun of(function: IrSimpleFunction): ScreenBinding {

            val parameters = function.parameters.filter { it.kind == IrParameterKind.Regular }

            return ScreenBinding(
                values = parameters.filter { it.type.isBundleValue() },
                callbacks = parameters.filter { it.type.isNoArgumentUnitFunction() },
                modifier = parameters.firstOrNull { it.type.classFqName == MODIFIER },
            )
        }
    }
}

private fun IrType.isBundleValue(): Boolean =
    classFqName?.asString() in BUNDLE_VALUE_TYPES

/**
 * Whether this is `() -> Unit`.
 *
 * Only that shape: a callback taking arguments would need those arguments to
 * cross back from the bundle, which the command model does not do.
 */
private fun IrType.isNoArgumentUnitFunction(): Boolean {

    if (classFqName != FUNCTION_ZERO) return false

    val returned = (this as? IrSimpleType)
        ?.arguments
        ?.singleOrNull()
        ?.typeOrNull
        ?.classFqName

    return returned == UNIT
}

private val IrTypeArgument.typeOrNull: IrType?
    get() = (this as? IrTypeProjection)?.type

private val MODIFIER = FqName("androidx.compose.ui.Modifier")
private val FUNCTION_ZERO = FqName("kotlin.Function0")
private val UNIT = FqName("kotlin.Unit")

private val BUNDLE_VALUE_TYPES = setOf("kotlin.String", "kotlin.Int", "kotlin.Boolean")
