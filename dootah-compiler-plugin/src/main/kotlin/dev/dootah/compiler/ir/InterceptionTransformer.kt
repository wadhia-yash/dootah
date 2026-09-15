package dev.dootah.compiler.ir

import dev.dootah.compiler.COMPOSABLE_ANNOTATION
import dev.dootah.contract.AdapterContract
import dev.dootah.contract.CapabilityContract
import dev.dootah.contract.ScreenContract
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
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
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
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
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
    private val sourceText: String = "",
) {

    /** What each rewritten screen can be asked for, in the order they were done. */
    val contracts = mutableListOf<ScreenContract>()

    /**
     * The locals of the screen being rewritten.
     *
     * Adapters and actions are lifted out of the body into values prepared
     * before it runs, so one that captures a local produces bytecode the JVM
     * backend refuses -- `Non-mapped local declaration`, thrown from a phase
     * with nothing in it that names Dootah or the screen.
     *
     * The passes that select these already refuse the cases they can see, and
     * they keep missing ones: a delegated `var` read through a generated
     * accessor, a handler from a destructured `remember` bound beside a
     * reference rather than read inside it. Each was found on a real app, after
     * shipping. So the last word is here, on the built lambda itself, where
     * every way of reading a local looks the same.
     */
    private var bodyLocals: Set<IrValueDeclaration> = emptySet()

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
        val native = if (composable.isEmpty())
            NativeBindings(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        else originalBody.nativeBindings(LAYOUT_COMPOSABLES, sourceText)

        // Recorded here rather than worked out again later, because this is the
        // only place that knows both what the app registered and what it called
        // the things it registered. A bundle is checked against this before it
        // is allowed to publish.
        contracts += ScreenContract(
            id = screenId,
            adapters = native.adapters.map { adapter ->
                AdapterContract(
                    id = adapter.id,
                    supportedProps = adapter.suppliedParameters.sorted(),
                )
            },
            capabilities = native.capabilities.map { capability ->
                CapabilityContract(id = capability.id, arity = capability.arity)
            },
            handles = binding.natives.map { native -> native.name.asString() }.sorted(),
            resources = native.resources.map { resource -> resource.key }.sorted(),
            anchors = native.anchors.map { anchor -> anchor.name }.sorted(),
            builders = native.builders.map { builder -> builder.id }.sorted(),
            callbacks = binding.callbackIds.sorted(),
        )

        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        val unitType = pluginContext.irBuiltIns.unitType

        // Built before the body is replaced: it adopts the original statements,
        // and reading them afterwards would read the replacement instead.
        // Before the body is taken apart. `asExpression` adopts the statements
        // into what becomes the fallback branch, leaving nothing behind to scan.
        bodyLocals = originalBody.localsDeclaredHere()

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
            arguments[1] = irString(binding.callbackSignatures)
            arguments[2] = varargOf(
                parameter = symbols.callbacks.owner.parameters[2],
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
        arguments[4] = buildAnchors(native.anchors)
        arguments[5] = buildBuilders(function, native.builders)
    }

    /**
     * The stretches of list-building this screen lets a bundle place.
     *
     * Each is the app's own statement lifted into a lambda of its own, with the
     * reads of the scope it was written against rebound to the scope the new
     * lambda is given. The bundle names one and says where it goes; the code
     * inside it is the app's, unchanged, and the scope it runs on is made by
     * Compose on this side of the wire.
     */
    private fun IrBuilderWithScope.buildBuilders(
        parent: IrSimpleFunction,
        builders: List<NativeBuilder>,
    ): IrExpression {

        val parameter = symbols.builders.owner.parameters[0]

        return irCall(symbols.builders).apply {
            arguments[0] = varargOf(
                parameter = parameter,
                elements = builders.mapNotNull { builder ->
                    builderLambda(parent, builder)?.let { entries ->
                        irCall(symbols.builder).apply {
                            arguments[0] = irString(builder.id)
                            arguments[1] = entries
                        }
                    }
                },
            )
        }
    }

    /** `{ <the app's own statement, on this lambda's scope> }`. */
    private fun builderLambda(
        parent: IrSimpleFunction,
        builder: NativeBuilder,
    ): IrExpression? {

        val copied = builder.statement.deepCopyWithSymbols(parent)

        // The type the runtime declares for a builder's entries, taken from the
        // runtime rather than rebuilt here: it is the same `Scope.() -> Unit`
        // either way, and constructing an extension function type by hand is a
        // way to get a lambda the backend cannot pass to the function it is for.
        val slot = symbols.builder.owner.parameters[1].type

        val lambda = pluginContext.irFactory.buildFun {
            this.name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = pluginContext.irBuiltIns.unitType
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
        }.apply {

            this.parent = parent

            val scope = addValueParameter("\$this\$entries", builder.scope.type)
                .also { it.kind = IrParameterKind.ExtensionReceiver }

            // Every read of the scope the statement was written against, moved
            // onto the one this lambda is handed. Without it the copy still
            // points at a receiver belonging to a lambda that is not here, which
            // the backend reports as a missing symbol long after Dootah has
            // finished and names none of it.
            copied.transformChildrenVoid(ScopeRebinder(builder.scope.symbol, scope.symbol))

            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody { +copied }
        }

        // Asked after the rebinding, not before. The scope the statement was
        // written against is a parameter of a lambda inside the screen's body,
        // so it counts as one of the body's own locals -- and asking first
        // refused every region there is, for reading the one thing it is here
        // to read. What matters is whether anything is *left* that the body
        // declares once the scope has been moved across.
        if (lambda.capturesBodyLocal()) return null

        return IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = slot,
            function = lambda,
            origin = IrStatementOrigin.LAMBDA,
        )
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
                elements = adapters.mapNotNull { adapter ->
                    adapterLambda(function, adapter, composable)
                        .takeIf { content -> !content.capturesBodyLocal() }
                        ?.let { content ->
                            irCall(symbols.adapter).apply {
                                arguments[0] = irString(adapter.id)
                                arguments[1] = irString(
                                    adapter.suppliedParameters.sorted().joinToString(",")
                                )
                                arguments[2] = content
                            }
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

        val entry = symbols.adapter.owner.parameters[2]
        val type = entry.type

        // Offsets from the call this adapter is built out of. Compose keys and
        // names its hoisted lambdas off them, and an undefined offset is a
        // different thing from a real one.
        val lambda = pluginContext.irFactory.buildFun {
            name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = pluginContext.irBuiltIns.unitType
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            startOffset = adapter.template.startOffset
            endOffset = adapter.template.endOffset
        }.apply {
            this.parent = parent
            annotations = composable.map { annotation -> annotation.deepCopyWithSymbols(parent) }
        }

        val props = lambda.addValueParameter(PROPS_PARAMETER, symbols.props.owner.defaultType)

        lambda.body = DeclarationIrBuilder(pluginContext, lambda.symbol).irBlockBody {
            +placeComponent(lambda, props, adapter, composable)
        }

        lambda.patchDeclarationParents(parent)

        return IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = type,
            function = lambda,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    /**
     * Builds the call the adapter makes, as a copy of one the source wrote.
     *
     * Every argument the source supplied is replaced by a read from whatever the
     * bundle sent; everything else -- the defaulted parameters a composable
     * relies on -- is left exactly as the frontend arranged it.
     *
     * A copy rather than a fresh call, because a fresh one leaves those defaults
     * empty and the Compose compiler then evaluates them inside this lambda,
     * which has no composer to do it with. That failed in the backend with
     * `Unexpected null argument for composable call`, on an icon button whose
     * unsupplied `colors` default reads the Material theme.
     */
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun IrBuilderWithScope.placeComponent(
        lambda: IrSimpleFunction,
        props: IrValueParameter,
        adapter: NativeAdapter,
        composable: List<IrConstructorCall>,
    ): IrExpression {

        // A frozen region is the code as written, and reading anything from the
        // bundle is exactly what it must not do.
        if (adapter.frozen) {
            return (adapter.region ?: adapter.template).deepCopyWithSymbols(lambda)
        }

        val call = adapter.template.deepCopyWithSymbols(lambda)
        val callee = call.symbol.owner

        callee.declaredParameters().forEach { parameter ->

            val index = callee.parameters.indexOf(parameter)
            val name = parameter.name.asString()

            if (name !in adapter.suppliedParameters) return@forEach
            if (call.arguments.getOrNull(index) == null) return@forEach

            call.arguments[index] =
                argumentFor(lambda, props, parameter, name, composable) ?: return@forEach
        }

        return call
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

        // A builder slot: the real container is handed the bundle's entries and
        // makes the scope itself. Before this, the slot fell through to the
        // handler case below, and the adapter Compose got called a bundle's
        // action with a live `LazyListScope` as its argument -- a Compose object
        // handed to code that is not allowed to hold one. Nothing named that
        // adapter, so nothing ever ran it; it was still the wrong thing to have
        // generated.
        if (type.isDescribableBuilder()) {
            return readProp(props, ENTRIES_ACCESSOR, name)
        }

        type.unitFunctionArity()?.let { arity ->

            if (arity == 0) {
                return readProp(props, CALLBACK_ACCESSOR, name) ?: return null
            }

            return typedCallback(lambda, props, name, type, arity)
        }

        accessorFor(type)?.let { accessor ->
            // A nullable parameter takes the accessor that can answer null, so
            // an argument the bundle left out stays left out rather than
            // becoming an empty string or a blank image.
            val chosen = if (type.isMarkedNullable()) NULLABLE_ACCESSORS[accessor] ?: accessor
            else accessor
            return readProp(props, chosen, name)
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

    /** Whether something about to be lifted out reads one of the body's locals. */
    private fun IrElement.capturesBodyLocal(): Boolean {

        if (bodyLocals.isEmpty()) return false

        var captures = false

        acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (element is IrGetValue && element.symbol.owner in bodyLocals) captures = true
                element.acceptChildrenVoid(this)
            }
        })

        return captures
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

            // The scopes the component hands its content, declared so this is
            // the type the component asked for. Nothing reads them: a component
            // whose call reads its surrounding scope is refused by both passes,
            // so the bundle's children never have one to read.
            type.contentScopes().forEachIndexed { index, scope ->
                addValueParameter("\$this\$content$index", scope).also { scopeParameter ->
                    if (index == 0 && type.hasReceiverScope()) {
                        scopeParameter.kind = IrParameterKind.ExtensionReceiver
                    }
                }
            }

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
                    capabilityLambda(function, capability)
                        ?.takeIf { action -> !action.capturesBodyLocal() }
                        ?.let { action ->
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

        val entry = symbols.capability.owner.parameters[1]

        val lambda = pluginContext.irFactory.buildFun {
            name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = pluginContext.irBuiltIns.unitType
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
        }.apply {
            this.parent = parent
        }

        val supplied = lambda.addValueParameter(
            ARGUMENTS_PARAMETER,
            pluginContext.irBuiltIns.listClass.typeWith(pluginContext.irBuiltIns.anyNType),
        )

        lambda.body = DeclarationIrBuilder(pluginContext, lambda.symbol).irBlockBody {

            val forwarded = capability.forwarded

            if (forwarded != null) {
                // `onClick = onPick` names the same action as `onClick = { onPick() }`,
                // and the extraction pass renders both the same way.
                +irCall(
                    pluginContext.irBuiltIns.functionN(capability.arity)
                        .getSimpleFunction("invoke")!!
                ).apply {
                    arguments[0] = irGet(forwarded)
                    capability.arity.let { count ->
                        for (index in 0 until count) {
                            arguments[index + 1] = suppliedArgument(
                                supplied = supplied,
                                index = index,
                                type = pluginContext.irBuiltIns.anyNType,
                            )
                        }
                    }
                }
            }

            val original = capability.lambda

            if (original != null) {

                val parameters = original.parameters
                    .filter { parameter -> parameter.kind == IrParameterKind.Regular }

                (original.body as? IrBlockBody)?.statements?.forEach { statement ->
                    +statement.deepCopyWithSymbols(lambda)
                        .readArgumentsFrom(supplied, parameters)
                        .returningTo(from = original.symbol, to = lambda.symbol)
                }
            }
        }

        lambda.patchDeclarationParents(parent)

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

    /**
     * The lengths the screen reads, under the names a bundle may call them by.
     *
     * The app's own expression, copied, and evaluated here on every composition
     * -- so a value that follows the theme or the window keeps following it. The
     * number is never read at build time and never travels.
     *
     * Guarded against capturing a body local for the same reason the adapters
     * are: this whole argument list is built above the body, so a local declared
     * inside it does not exist yet. A chain rooted at an object cannot capture
     * one, and the guard is here so that stays true if the rule widens.
     */
    private fun IrBuilderWithScope.buildAnchors(
        anchors: List<NativeAnchor>,
    ): IrExpression {

        val parameter = symbols.anchors.owner.parameters[0]

        return irCall(symbols.anchors).apply {
            arguments[0] = varargOf(
                parameter = parameter,
                elements = anchors.mapNotNull { anchor ->
                    anchor.value.deepCopyWithSymbols()
                        .takeIf { value -> !value.capturesBodyLocal() }
                        ?.let { value ->
                            irCall(symbols.anchor).apply {
                                arguments[0] = irString(anchor.name)
                                arguments[1] = value
                            }
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

    /**
     * Rewrites a copied handler so its own parameters come from the argument list.
     *
     * A handler taking a value -- `onBrushChange = { brush -> … }` -- is called by
     * the component with a value the app produced, never one the bundle chose.
     * The bundle only says which action to attach, so the value crosses no
     * boundary and the cast here is against a type the component itself declares.
     */
    private fun IrElement.readArgumentsFrom(
        supplied: IrValueParameter,
        parameters: List<IrValueParameter>,
    ): IrStatement {

        val bySymbol = parameters.withIndex().associate { (index, parameter) ->
            parameter.symbol to (index to parameter.type)
        }

        val builder = DeclarationIrBuilder(pluginContext, supplied.parent.let {
            (it as IrSimpleFunction).symbol
        })

        transform(object : IrElementTransformerVoid() {
            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val (index, type) = bySymbol[expression.symbol] ?: return expression
                return builder.suppliedArgument(supplied, index, type)
            }
        }, null)

        return this as IrStatement
    }

    /**
     * Points a copied handler's returns at the function it now lives in.
     *
     * Every lambda body ends in a return to the lambda it came from, including
     * the empty one an ignored callback is written as. Copying the statements
     * into a new function leaves those returns aimed at the original, which is
     * a return out of a function the copy is not inside -- and the backend emits
     * it as a placeholder for the inliner, which never runs over it. The
     * placeholder then reaches dexing, where it fails as a class name nobody
     * wrote.
     *
     * Retargeting is exact rather than approximate: Kotlin does not allow a
     * non-inline lambda to return anywhere but out of itself, so every return
     * being moved meant "leave this handler" and still does.
     */
    private fun IrStatement.returningTo(
        from: IrReturnTargetSymbol,
        to: IrReturnTargetSymbol,
    ): IrStatement {

        // The result is taken rather than discarded: the statement being copied
        // is often the return itself -- that is exactly what an empty handler is
        // -- and a transform that replaces the root cannot report it by mutating
        // in place.
        return transform(object : IrElementTransformerVoid() {
            override fun visitReturn(expression: IrReturn): IrExpression {

                expression.transformChildren(this, null)

                if (expression.returnTargetSymbol != from) return expression

                return IrReturnImpl(
                    startOffset = expression.startOffset,
                    endOffset = expression.endOffset,
                    type = expression.type,
                    returnTargetSymbol = to,
                    value = expression.value,
                )
            }
        }, null) as IrStatement
    }

    /** `arguments[index] as T`. */
    private fun IrBuilderWithScope.suppliedArgument(
        supplied: IrValueParameter,
        index: Int,
        type: IrType,
    ): IrExpression {

        val read = irCall(
            pluginContext.irBuiltIns.listClass.getSimpleFunction("get")!!
        ).apply {
            arguments[0] = irGet(supplied)
            arguments[1] = irInt(index)
        }

        return IrTypeOperatorCallImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = type,
            operator = IrTypeOperator.CAST,
            typeOperand = type,
            argument = read,
        )
    }

    private fun accessorFor(type: IrType): String? =
        ACCESSORS[type.classFqName?.asString()]

    private companion object {

        const val PROPS_PARAMETER = "props"
        const val ARGUMENTS_PARAMETER = "arguments"
        const val CALLBACK_ACCESSOR = "callback"
        const val CALLBACK_ONE_ACCESSOR = "callback1"
        const val CHILDREN_ACCESSOR = "children"
        const val ENTRIES_ACCESSOR = "entries"
        const val HANDLE_ACCESSOR = "handle"

        /**
         * Which accessor reads a parameter of each type.
         *
         * A type not listed here is routed through the handle table, so this is
         * a list of what a bundle may *describe*, not a list of what a component
         * may take.
         */
        /** The accessor to use instead, when the parameter is nullable. */
        val NULLABLE_ACCESSORS: Map<String, String> = mapOf(
            "string" to "stringOrNull",
            "painter" to "painterOrNull",
            "shape" to "shapeOrNull",
        )

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
private fun IrBlockBody.asExpression(unitType: IrType): IrExpression =
    IrBlockImpl(startOffset, endOffset, unitType).also { block ->
        block.statements += statements
    }

/**
 * Moves reads of one receiver onto another.
 *
 * A statement lifted out of a lambda and into a lambda of its own still reads
 * the receiver of the lambda it came from, which is not in scope where it now
 * lives. There is nothing subtle here and there had better not be: exactly the
 * reads of exactly that parameter are repointed, and everything else is left
 * alone.
 */
private class ScopeRebinder(
    private val from: IrValueParameterSymbol,
    private val to: IrValueParameterSymbol,
) : IrElementTransformerVoid() {

    override fun visitGetValue(expression: IrGetValue): IrExpression {
        if (expression.symbol != from) return super.visitGetValue(expression)
        return IrGetValueImpl(expression.startOffset, expression.endOffset, to.owner.type, to)
    }
}
