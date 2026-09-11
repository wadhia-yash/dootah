package dev.dootah.compiler.ir

import dev.dootah.compiler.COMPOSABLE_ANNOTATION
import dev.dootah.compiler.LAYOUT_COMPOSABLES
import dev.dootah.compiler.identity.dootahScreenId
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.name.Name

/**
 * Rewrites a `@Bundlable` function so Dootah can substitute a remote
 * implementation, keeping the original body as the fallback.
 *
 * The rewrite produces, in effect:
 *
 *     val screen = rememberDootahScreen(
 *         "<id>",
 *         dootahArguments("name,price", name, price),
 *         dootahCallbacks("onSave", onSave),
 *         dootahSlots("slot@120", { Icon(...) }),
 *     )
 *     if (hasRemoteImplementation(screen)) DootahRemoteContent(screen)
 *     else { <the original body, unmoved> }
 *
 * Two properties make this shape the right one. The original body stays in the
 * same function, so `return` statements keep their target and no value
 * parameters need remapping -- there is nothing to move and therefore nothing to
 * get wrong. And the inserted code is ordinary Compose: a call, a condition and
 * an `if`, which the Compose compiler lowers afterwards exactly as it lowers
 * hand-written code.
 *
 * The slot lambdas are the exception to "nothing is moved": each one is a copy
 * of a component the bundle keeps native, so the app can draw it inside a
 * remotely described screen. They are copies, not moves -- the originals stay in
 * the fallback, which has to keep working on its own.
 */
// Reading a resolved symbol's `owner` is only unsafe while IR is still being
// built. This runs from IrGenerationExtension.generate, after the module and
// its dependencies are complete.
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class InterceptionTransformer(
    private val pluginContext: IrPluginContext,
    private val symbols: DootahRuntimeSymbols,
) {

    /** The identity of the function rewritten, for reporting. */
    fun transform(function: IrSimpleFunction): String? {

        val originalBody = function.body as? IrBlockBody ?: return null
        val screenId = function.dootahScreenId()

        val binding = ScreenBinding.of(function)
        val composable = function.annotations
            .filter { it.type.classFqName == COMPOSABLE_ANNOTATION }

        // Without the annotation to copy there is no way to declare a lambda
        // composable, and a slot that is not composable cannot draw anything.
        // The screen is still intercepted; it simply offers no native slots.
        val slots = if (composable.isEmpty()) emptyList()
        else originalBody.nativeSlots(LAYOUT_COMPOSABLES)

        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        val unitType = pluginContext.irBuiltIns.unitType

        // Built before the body is replaced: it adopts the original statements,
        // and reading them afterwards would read the replacement instead.
        val nativeFallback = originalBody.asExpression(unitType)

        function.body = builder.irBlockBody {

            val screenState = buildVariable(
                parent = function,
                startOffset = function.startOffset,
                endOffset = function.endOffset,
                origin = IrDeclarationOrigin.DEFINED,
                name = Name.identifier(SCREEN_STATE_VARIABLE),
                type = symbols.rememberScreen.owner.returnType,
            ).apply {
                initializer = irCall(symbols.rememberScreen).apply {
                    arguments[0] = irString(screenId)
                    arguments[1] = buildArguments(binding)
                    arguments[2] = buildCallbacks(binding)
                    arguments[3] = buildSlots(function, slots, composable)
                }
            }

            +screenState

            +irIfThenElse(
                type = unitType,
                condition = irCall(symbols.hasRemoteImplementation).apply {
                    arguments[0] = irGet(screenState)
                },
                thenPart = irCall(symbols.renderRemoteContent).apply {
                    arguments[0] = irGet(screenState)
                },
                elsePart = nativeFallback,
            )
        }

        return screenId
    }

    /**
     * Builds the values the screen's caller passed.
     *
     * The names travel as one comma-separated constant rather than interleaved
     * with the values. The names are known at compile time and the values are
     * not, so keeping them apart makes the pairing positional and impossible to
     * get half right.
     */
    private fun IrBuilderWithScope.buildArguments(binding: ScreenBinding): IrExpression {

        val modifier = binding.modifier

        val callee = if (modifier == null) symbols.arguments else symbols.modifiedArguments

        return irCall(callee).apply {

            var index = 0

            if (modifier != null) arguments[index++] = irGet(modifier)

            arguments[index] = irString(binding.valueNames)
            arguments[index + 1] = varargOf(
                parameter = callee.owner.parameters[index + 1],
                elements = binding.values.map { irGet(it) },
            )
        }
    }

    private fun IrBuilderWithScope.buildCallbacks(binding: ScreenBinding): IrExpression =
        irCall(symbols.callbacks).apply {
            arguments[0] = irString(binding.callbackNames)
            arguments[1] = varargOf(
                parameter = symbols.callbacks.owner.parameters[1],
                elements = binding.callbacks.map { irGet(it) },
            )
        }

    private fun IrBuilderWithScope.buildSlots(
        function: IrSimpleFunction,
        slots: List<NativeSlot>,
        composable: List<IrConstructorCall>,
    ): IrExpression {

        val parameter = symbols.slots.owner.parameters[1]

        return irCall(symbols.slots).apply {
            arguments[0] = irString(slots.joinToString(",") { it.id })
            arguments[1] = varargOf(
                parameter = parameter,
                elements = slots.map { slot ->
                    composableLambda(
                        parent = function,
                        slot = slot,
                        type = parameter.varargElementType ?: parameter.type,
                        composable = composable,
                    )
                },
            )
        }
    }

    /**
     * Wraps one native component in a composable lambda.
     *
     * The lambda's type comes from the runtime function's own vararg element
     * type, which is already `@Composable () -> Unit`. Taking it from the
     * declaration rather than constructing it means the annotation on the type
     * cannot drift from what the runtime expects.
     */
    private fun composableLambda(
        parent: IrSimpleFunction,
        slot: NativeSlot,
        type: IrType,
        composable: List<IrConstructorCall>,
    ): IrExpression {

        val lambda = pluginContext.irFactory.buildFun {
            name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = pluginContext.irBuiltIns.unitType
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            startOffset = slot.call.startOffset
            endOffset = slot.call.endOffset
        }.apply {
            this.parent = parent
            annotations = composable.map { annotation ->
                annotation.deepCopyWithSymbols(parent)
            }
            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                +slot.call.deepCopyWithSymbols(this@apply)
            }
        }

        return IrFunctionExpressionImpl(
            startOffset = slot.call.startOffset,
            endOffset = slot.call.endOffset,
            type = type,
            function = lambda,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    private fun IrBuilderWithScope.varargOf(
        parameter: IrValueParameter,
        elements: List<IrExpression>,
    ): IrExpression = IrVarargImpl(
        startOffset = startOffset,
        endOffset = endOffset,
        type = parameter.type,
        varargElementType = parameter.varargElementType ?: parameter.type,
        elements = elements,
    )

    private companion object {
        /**
         * Prefixed so it cannot collide with a name from the developer's body,
         * which now shares this function's scope.
         */
        const val SCREEN_STATE_VARIABLE = "\$dootahScreen"
    }
}

/**
 * Repackages a function body as a single Unit-typed expression.
 *
 * Wrapping rather than inlining the statements keeps the fallback a self
 * contained branch, so a declaration in the original body cannot leak into the
 * scope of the code Dootah inserts around it.
 */
private fun IrBlockBody.asExpression(unitType: IrType): IrExpression =
    IrBlockImpl(startOffset, endOffset, unitType).also { block ->
        block.statements += statements
    }

