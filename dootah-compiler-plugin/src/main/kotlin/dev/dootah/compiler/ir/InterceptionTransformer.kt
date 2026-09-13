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
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.hasAnnotation
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
        // composable, and an adapter that is not composable cannot draw
        // anything. The screen is still intercepted; it simply offers no native
        // components.
        val native = if (composable.isEmpty()) NativeBindings(emptyList(), emptyList(), emptyList())
        else originalBody.nativeBindings(LAYOUT_COMPOSABLES)

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
                    arguments[3] = buildBindings(function, native, binding, composable)
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

    /**
     * Registers everything this screen lets a bundle reach.
     *
     * Four tables, and together they are the complete boundary: the components
     * that can be placed, the actions that can be run, the screen's own values
     * that can be routed into a component, and the resources that can be named.
     * A bundle may use any of it and nothing beyond it, and every entry is here
     * because the screen's own source referred to it.
     */
    private fun IrBuilderWithScope.buildBindings(
        function: IrSimpleFunction,
        native: NativeBindings,
        binding: ScreenBinding,
        composable: List<IrConstructorCall>,
    ): IrExpression = irCall(symbols.bindings).apply {
        arguments[0] = buildAdapters(function, native.adapters, composable)
        arguments[1] = buildCapabilities(function, native.capabilities)
        arguments[2] = buildHandles(binding)
        arguments[3] = buildResources(native.resources)
    }

    private fun IrBuilderWithScope.buildAdapters(
        function: IrSimpleFunction,
        adapters: List<NativeAdapter>,
        composable: List<IrConstructorCall>,
    ): IrExpression {

        val parameter = symbols.adapters.owner.parameters[0]

        return irCall(symbols.adapters).apply {
            arguments[0] = varargOf(
                parameter = parameter,
                elements = adapters.map { adapter ->
                    irCall(symbols.adapter).apply {
                        arguments[0] = irString(adapter.id)
                        arguments[1] = adapterLambda(function, adapter, composable)
                    }
                },
            )
        }
    }

    /**
     * Builds the composable that places one native component.
     *
     * Synthesised, not copied. The old design lifted the call out of the source
     * with its arguments frozen inside it, which is why it could only ever be
     * placed where it had been written. This builds a fresh call whose arguments
     * are read from whatever the bundle supplied, so the same adapter serves any
     * number of instances with different arguments each.
     *
     * Only the parameters the source supplies somewhere are passed through. The
     * rest keep the composable's own defaults, which is the only way to leave
     * them alone -- an adapter that passed every parameter would overwrite a
     * default with whatever a missing argument fell back to.
     */
    private fun adapterLambda(
        parent: IrSimpleFunction,
        adapter: NativeAdapter,
        composable: List<IrConstructorCall>,
    ): IrExpression {

        val entry = symbols.adapter.owner.parameters[1]
        val type = entry.type

        val lambda = pluginContext.irFactory.buildFun {
            name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = pluginContext.irBuiltIns.unitType
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
        }.apply {
            this.parent = parent
            annotations = composable.map { annotation -> annotation.deepCopyWithSymbols(parent) }
        }

        val props = lambda.addValueParameter(PROPS_PARAMETER, symbols.props.owner.defaultType)

        lambda.body = DeclarationIrBuilder(pluginContext, lambda.symbol).irBlockBody {
            +placeComponent(lambda, props, adapter, composable)
        }

        return IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = type,
            function = lambda,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    private fun IrBuilderWithScope.placeComponent(
        lambda: IrSimpleFunction,
        props: IrValueParameter,
        adapter: NativeAdapter,
        composable: List<IrConstructorCall>,
    ): IrExpression = irCall(adapter.callee).apply {

        val callee = adapter.callee.owner

        callee.parameters.forEachIndexed { index, parameter ->

            if (parameter.kind != IrParameterKind.Regular) return@forEachIndexed

            val name = parameter.name.asString()
            if (name !in adapter.suppliedParameters) return@forEachIndexed

            arguments[index] = argumentFor(lambda, props, parameter, name, composable)
                ?: return@forEachIndexed
        }
    }

    /**
     * Reads one of a component's arguments out of what the bundle supplied.
     *
     * The accessor is chosen by the parameter's own declared type, so the
     * composable is handed the type it asked for. A type with no accessor is
     * routed through the screen's handle table instead -- that is how a view
     * model or a list of domain objects reaches a component the bundle placed,
     * without the bundle ever holding one.
     */
    private fun IrBuilderWithScope.argumentFor(
        lambda: IrSimpleFunction,
        props: IrValueParameter,
        parameter: IrValueParameter,
        name: String,
        composable: List<IrConstructorCall>,
    ): IrExpression? {

        val type = parameter.type

        if (type.isComposableContent()) {
            return contentLambda(lambda, props, name, type, composable)
        }

        type.unitFunctionArity()?.let { arity ->

            if (arity == 0) {
                return readProp(props, CALLBACK_ACCESSOR, name) ?: return null
            }

            return typedCallback(lambda, props, name, type, arity)
        }

        accessorFor(type)?.let { accessor ->
            return readProp(props, accessor, name)
        }

        val handle = readProp(props, HANDLE_ACCESSOR, name) ?: return null

        return IrTypeOperatorCallImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = type,
            operator = IrTypeOperator.CAST,
            typeOperand = type,
            argument = handle,
        )
    }

    private fun IrBuilderWithScope.readProp(
        props: IrValueParameter,
        accessor: String,
        name: String,
    ): IrExpression? {

        val symbol = symbols.accessor(accessor) ?: return null

        return irCall(symbol).apply {
            arguments[0] = irGet(props)
            arguments[1] = irString(name)
        }
    }

    /** `{ props.children("content") }`, as the composable content a component takes. */
    private fun contentLambda(
        parent: IrSimpleFunction,
        props: IrValueParameter,
        name: String,
        type: IrType,
        composable: List<IrConstructorCall>,
    ): IrExpression? {

        val children = symbols.accessor(CHILDREN_ACCESSOR) ?: return null

        val lambda = pluginContext.irFactory.buildFun {
            this.name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = pluginContext.irBuiltIns.unitType
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
        }.apply {
            this.parent = parent
            annotations = composable.map { annotation -> annotation.deepCopyWithSymbols(parent) }
            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                +irCall(children).apply {
                    arguments[0] = irGet(props)
                    arguments[1] = irString(name)
                }
            }
        }

        return IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = type,
            function = lambda,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    /**
     * `{ a -> props.callback1("onBrushChange")(a) }`.
     *
     * A component calls its own handler with its own value -- a chosen brush, a
     * new size -- so the argument comes from the app, never from the bundle. The
     * bundle only says which action to attach.
     */
    private fun typedCallback(
        parent: IrSimpleFunction,
        props: IrValueParameter,
        name: String,
        type: IrType,
        arity: Int,
    ): IrExpression? {

        if (arity != 1) return null

        val accessor = symbols.accessor(CALLBACK_ONE_ACCESSOR) ?: return null
        val argumentType = (type as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull
            ?: pluginContext.irBuiltIns.anyNType

        val lambda = pluginContext.irFactory.buildFun {
            this.name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = pluginContext.irBuiltIns.unitType
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
        }.apply {
            this.parent = parent
        }

        val value = lambda.addValueParameter("it", argumentType)

        lambda.body = DeclarationIrBuilder(pluginContext, lambda.symbol).irBlockBody {

            val supplied = irCall(accessor).apply {
                arguments[0] = irGet(props)
                arguments[1] = irString(name)
            }

            +irCall(pluginContext.irBuiltIns.functionN(1).getSimpleFunction("invoke")!!).apply {
                arguments[0] = supplied
                arguments[1] = irGet(value)
            }
        }

        return IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = type,
            function = lambda,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    /**
     * Lifts the screen's handlers into the APK as named actions.
     *
     * The body is copied verbatim, so a handler keeps closing over exactly the
     * screen parameters it was written against. What a bundle gets is the name.
     */
    private fun IrBuilderWithScope.buildCapabilities(
        function: IrSimpleFunction,
        capabilities: List<NativeCapability>,
    ): IrExpression {

        val parameter = symbols.capabilities.owner.parameters[0]

        return irCall(symbols.capabilities).apply {
            arguments[0] = varargOf(
                parameter = parameter,
                elements = capabilities.mapNotNull { capability ->
                    capabilityLambda(function, capability)?.let { action ->
                        irCall(symbols.capability).apply {
                            arguments[0] = irString(capability.id)
                            arguments[1] = action
                        }
                    }
                },
            )
        }
    }

    private fun capabilityLambda(
        parent: IrSimpleFunction,
        capability: NativeCapability,
    ): IrExpression? {

        // Only handlers taking nothing are lifted for now. One taking a value
        // gets that value from the component, which means the copied body has to
        // read it out of the argument list -- worth doing, and not yet done.
        if (capability.arity != 0) return null

        val entry = symbols.capability.owner.parameters[1]

        val lambda = pluginContext.irFactory.buildFun {
            name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = pluginContext.irBuiltIns.unitType
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
        }.apply {
            this.parent = parent
        }

        lambda.addValueParameter(
            ARGUMENTS_PARAMETER,
            pluginContext.irBuiltIns.listClass.typeWith(pluginContext.irBuiltIns.anyNType),
        )

        lambda.body = DeclarationIrBuilder(pluginContext, lambda.symbol).irBlockBody {

            val forwarded = capability.forwarded

            if (forwarded != null) {
                // `onClick = onPick` names the same action as `onClick = { onPick() }`,
                // and the extraction pass renders both the same way.
                +irCall(pluginContext.irBuiltIns.functionN(0).getSimpleFunction("invoke")!!).apply {
                    arguments[0] = irGet(forwarded)
                }
            }

            (capability.lambda?.body as? IrBlockBody)?.statements?.forEach { statement ->
                +statement.deepCopyWithSymbols(lambda)
            }
        }

        return IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = entry.type,
            function = lambda,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    /**
     * The screen's own parameters that cannot be serialised, by name.
     *
     * A bundle names one of these to route it into a native component. It is a
     * coordinate into this table and nothing more: the bundle never sees the
     * object, cannot construct one, and cannot name a parameter belonging to a
     * different screen.
     */
    private fun IrBuilderWithScope.buildHandles(binding: ScreenBinding): IrExpression {

        val parameter = symbols.handles.owner.parameters[0]

        return irCall(symbols.handles).apply {
            arguments[0] = varargOf(
                parameter = parameter,
                elements = binding.natives.map { native ->
                    irCall(symbols.handle).apply {
                        arguments[0] = irString(native.name.asString())
                        arguments[1] = irGet(native)
                    }
                },
            )
        }
    }

    /** The resources the screen names, mapped to this build's own numbers. */
    private fun IrBuilderWithScope.buildResources(
        resources: List<NativeResource>,
    ): IrExpression {

        val parameter = symbols.resources.owner.parameters[0]

        return irCall(symbols.resources).apply {
            arguments[0] = varargOf(
                parameter = parameter,
                elements = resources.map { resource ->
                    irCall(symbols.resource).apply {
                        arguments[0] = irString(resource.key)
                        arguments[1] = resource.id.deepCopyWithSymbols()
                    }
                },
            )
        }
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

    private fun accessorFor(type: IrType): String? =
        ACCESSORS[type.classFqName?.asString()]

    private companion object {

        const val PROPS_PARAMETER = "props"
        const val ARGUMENTS_PARAMETER = "arguments"
        const val CALLBACK_ACCESSOR = "callback"
        const val CALLBACK_ONE_ACCESSOR = "callback1"
        const val CHILDREN_ACCESSOR = "children"
        const val HANDLE_ACCESSOR = "handle"

        /**
         * Which accessor reads a parameter of each type.
         *
         * A type not listed here is routed through the handle table, so this is
         * a list of what a bundle may *describe*, not a list of what a component
         * may take.
         */
        val ACCESSORS: Map<String, String> = mapOf(
            "kotlin.String" to "string",
            "kotlin.Int" to "int",
            "kotlin.Boolean" to "boolean",
            "kotlin.Long" to "long",
            "kotlin.Float" to "float",
            "kotlin.Double" to "double",
            "androidx.compose.ui.Modifier" to "modifier",
            "androidx.compose.ui.graphics.Color" to "color",
            "androidx.compose.ui.unit.Dp" to "dp",
            "androidx.compose.ui.graphics.Shape" to "shape",
            "androidx.compose.ui.graphics.painter.Painter" to "painter",
        )

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
/** Whether this parameter takes composable content rather than a value. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrType.isComposableContent(): Boolean =
    unitFunctionArity() == 0 && annotations.any { annotation ->
        annotation.type.classFqName == COMPOSABLE_ANNOTATION
    }

/** How many arguments this takes, if it is a `(…) -> Unit`. */
private fun IrType.unitFunctionArity(): Int? {

    val name = classFqName?.asString() ?: return null
    if (!name.startsWith("kotlin.Function")) return null

    val arity = name.removePrefix("kotlin.Function").toIntOrNull() ?: return null

    val returned = (this as? IrSimpleType)?.arguments?.lastOrNull()?.typeOrNull?.classFqName

    return arity.takeIf { returned == UNIT_NAME }
}

private val UNIT_NAME = org.jetbrains.kotlin.name.FqName("kotlin.Unit")

private fun IrBlockBody.asExpression(unitType: IrType): IrExpression =
    IrBlockImpl(startOffset, endOffset, unitType).also { block ->
        block.statements += statements
    }

