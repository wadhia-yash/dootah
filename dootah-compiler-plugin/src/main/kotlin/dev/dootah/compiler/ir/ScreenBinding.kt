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
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.name.FqName
import dev.dootah.contract.CallbackId

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

    /**
     * The parameters Dootah cannot serialise, which a bundle may route into a
     * native component by name.
     *
     * A view model, a list of domain objects, a `MutableState`. The bundle never
     * holds one; it names one, and the app takes it out of this table.
     */
    val natives: List<IrValueParameter>,
) {

    val valueNames: String get() = values.joinToString(",") { it.name.asString() }

    val callbackNames: String get() = callbacks.joinToString(",") { it.name.asString() }

    /**
     * The declared types of each callback, in the same order as [callbackNames].
     *
     * Sent alongside the functions themselves so the runtime can turn the values
     * a bundle sends back into the types the app's own signature asked for. One
     * string rather than a structure for the same reason the names are: both are
     * compile-time constants, and pairing them positionally cannot go half
     * right.
     */
    val callbackSignatures: String
        get() = callbacks.joinToString(SIGNATURE_SEPARATOR) { parameter ->
            parameter.type.unitFunctionParameterTypes()
                .orEmpty()
                .joinToString(CallbackId.SEPARATOR)
        }

    /** What this screen lets a bundle invoke, as the contract records it. */
    val callbackIds: List<String>
        get() = callbacks.map { parameter ->
            CallbackId.of(
                name = parameter.name.asString(),
                parameterTypes = parameter.type.unitFunctionParameterTypes().orEmpty(),
            )
        }

    companion object {

        @OptIn(UnsafeDuringIrConstructionAPI::class)
        fun of(function: IrSimpleFunction): ScreenBinding {

            val parameters = function.parameters.filter { it.kind == IrParameterKind.Regular }

            return ScreenBinding(
                values = parameters.filter { it.type.isBundleValue() },
                callbacks = parameters.filter { it.type.unitFunctionParameterTypes() != null },
                modifier = parameters.firstOrNull { it.type.classFqName == MODIFIER },
                natives = parameters.filterNot { parameter ->
                    parameter.type.isBundleValue() ||
                        parameter.type.unitFunctionParameterTypes() != null ||
                        parameter.type.classFqName == MODIFIER
                },
            )
        }
    }
}

private fun IrType.isBundleValue(): Boolean =
    classFqName?.asString() in BUNDLE_VALUE_TYPES

/**
 * The values a `(T...) -> Unit` parameter takes, or null if it is not one.
 *
 * The same rule the extraction pass applies, by name rather than by shared code
 * because the two passes read different trees. Every parameter type has to be
 * one a bundle can produce; a callback taking anything else stays a native-only
 * value, which is what keeps the app's own objects out of a bundle's reach.
 */
internal fun IrType.unitFunctionParameterTypes(): List<String>? {

    val name = classFqName?.asString() ?: return null
    if (!name.startsWith(FUNCTION_PREFIX)) return null
    if (name.removePrefix(FUNCTION_PREFIX).toIntOrNull() == null) return null

    val arguments = (this as? IrSimpleType)?.arguments ?: return null
    if (arguments.lastOrNull()?.typeOrNull?.classFqName != UNIT) return null

    return arguments.dropLast(1).map { argument ->
        val type = argument.typeOrNull ?: return null
        val simple = type.classFqName?.asString()?.removePrefix("kotlin.") ?: return null
        if (simple !in BUNDLE_VALUE_NAMES || type.isMarkedNullable()) return null
        simple
    }
}

private val IrTypeArgument.typeOrNull: IrType?
    get() = (this as? IrTypeProjection)?.type

private val MODIFIER = FqName("androidx.compose.ui.Modifier")
private const val FUNCTION_PREFIX = "kotlin.Function"
private const val SIGNATURE_SEPARATOR = ","
private val UNIT = FqName("kotlin.Unit")

private val BUNDLE_VALUE_NAMES = setOf(
    "String", "Int", "Boolean", "Long", "Float", "Double",
)

private val BUNDLE_VALUE_TYPES = setOf(
    "kotlin.String",
    "kotlin.Int",
    "kotlin.Boolean",
    "kotlin.Long",
    "kotlin.Float",
    "kotlin.Double",
)
