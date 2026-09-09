package dev.dootah.compiler.ir

import dev.dootah.compiler.identity.dootahScreenId
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.name.Name

/**
 * Rewrites a `@Bundlable` function so Dootah can substitute a remote
 * implementation, keeping the original body as the fallback.
 *
 * The rewrite produces, in effect:
 *
 *     val screen = rememberDootahScreen("<id>")
 *     if (hasRemoteImplementation(screen)) DootahRemoteContent(screen)
 *     else { <the original body, unmoved> }
 *
 * Two properties make this shape the right one. The original body stays in the
 * same function, so `return` statements keep their target and no value
 * parameters need remapping -- there is nothing to move and therefore nothing to
 * get wrong. And the inserted code is ordinary Compose: a call, a condition and
 * an `if`, which the Compose compiler lowers afterwards exactly as it lowers
 * hand-written code.
 */
// Reading a resolved symbol's `owner` is only unsafe while IR is still being
// built. This runs from IrGenerationExtension.generate, after the module and
// its dependencies are complete.
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class InterceptionTransformer(
    private val pluginContext: IrPluginContext,
    private val symbols: DootahRuntimeSymbols,
) {

    /** The identities of the functions rewritten, for reporting. */
    fun transform(function: IrSimpleFunction): String? {

        val originalBody = function.body as? IrBlockBody ?: return null
        val screenId = function.dootahScreenId()

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
private fun IrBlockBody.asExpression(unitType: org.jetbrains.kotlin.ir.types.IrType): IrExpression =
    IrBlockImpl(startOffset, endOffset, unitType).also { block ->
        block.statements += statements
    }
