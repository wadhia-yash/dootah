package dev.dootah.compiler.ir

import dev.dootah.compiler.COMPOSABLE_ANNOTATION
import dev.dootah.contract.AdapterId
import dev.dootah.contract.CapabilityId
import dev.dootah.contract.ResourceKey
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName

/**
 * A native composable this screen can be asked to place.
 *
 * One entry per composable, not per call: three icon buttons written in the
 * source register one adapter, and a bundle may then place it nowhere, once or
 * five times. That is the whole difference from the design this replaced, where
 * each call was copied and named by its position among its identical siblings --
 * so deleting one silently rebound the rest.
 *
 * [suppliedParameters] is the union of the arguments the source gives this
 * composable anywhere in the screen. It is what the adapter passes through, and
 * therefore what a bundle may vary. An argument no call site ever supplied is
 * genuinely absent from the binary, and needs a new one.
 */
internal data class NativeAdapter(
    val id: String,

    /**
     * One of the calls the source wrote, used as the shape to build from.
     *
     * The adapter is a *copy of this call with its arguments replaced*, rather
     * than a call built from nothing. Building one from nothing leaves the
     * composable's defaulted parameters empty, and the Compose compiler then
     * tries to evaluate a default like `IconButtonDefaults.iconButtonColors()`
     * inside the adapter -- where it has no composer, and the build fails in the
     * backend with `Unexpected null argument for composable call`.
     *
     * Copying keeps whatever the frontend set up for the defaults, which is the
     * one arrangement Compose is guaranteed to understand.
     */
    val template: IrCall,

    /**
     * The arguments the adapter passes through, and therefore the ones a bundle
     * may vary.
     *
     * Taken from whichever call supplies the most, because that call is the only
     * one with an argument slot for each of them.
     */
    val suppliedParameters: Set<String>,
)

/**
 * An action lifted out of the screen's source and kept in the APK.
 *
 * Named by what it does, so it survives being moved, having its neighbour
 * deleted, or being attached to a different component -- and so two components
 * given the same handler share one entry.
 */
internal data class NativeCapability(
    val id: String,
    val arity: Int,

    /** A handler written inline, whose body is copied into the APK. */
    val lambda: IrFunction? = null,

    /**
     * One of the screen's own function parameters, passed straight through.
     *
     * `IconButton(onClick = onPick)` names the same action as writing
     * `onClick = { onPick() }`, and the extraction pass renders both the same
     * way -- so this pass has to register both, or the app has no action under
     * the name the bundle asks for.
     */
    val forwarded: IrValueDeclaration? = null,
)

/** A resource the screen names, mapped to this build's own identifier. */
internal data class NativeResource(
    val key: String,
    val id: IrExpression,
)

internal class NativeBindings(
    val adapters: List<NativeAdapter>,
    val capabilities: List<NativeCapability>,
    val resources: List<NativeResource>,
)

/**
 * Finds everything a screen's remote implementation may reach.
 *
 * Deliberately a superset of what the extraction pass will actually select. The
 * two run over two different versions of the source -- the one the APK was built
 * from, and the edited one a bundle is published from -- so registering
 * something unused costs a little code, while failing to register something the
 * bundle went on to use is a hole in a shipped screen.
 *
 * Layouts are excluded because the extraction pass never turns one into a
 * component: a layout holds the remote children, and a native copy of it would
 * swallow the part of the screen that is supposed to be updatable.
 *
 * A component whose call reads the scope of the layout around it is excluded
 * too. An adapter is a standalone lambda with no receiver, so a composable
 * declared on `ColumnScope`, or one whose modifier calls `weight`, cannot be
 * built at all -- and the extraction pass refuses the same case rather than
 * naming a component the app could not register.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrBody.nativeBindings(layouts: Set<FqName>): NativeBindings {

    val adapters = LinkedHashMap<String, NativeAdapter>()
    val capabilities = LinkedHashMap<String, NativeCapability>()
    val resources = LinkedHashMap<String, NativeResource>()

    val declaredInBody = declarations()

    acceptVoid(object : IrVisitorVoid() {

        override fun visitElement(element: IrElement) {

            if (element is IrCall && element.isComponent(layouts, declaredInBody)) {

                element.record(adapters, capabilities, resources)

                // Descending anyway: the content of a native component may hold
                // components of its own, and the bundle can place those too.
            }

            if (element is IrCall) element.recordResource(resources)

            element.acceptChildrenVoid(this)
        }
    })

    return NativeBindings(
        adapters = adapters.values.toList(),
        capabilities = capabilities.values.toList(),
        resources = resources.values.toList(),
    )
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrCall.record(
    adapters: MutableMap<String, NativeAdapter>,
    capabilities: MutableMap<String, NativeCapability>,
    resources: MutableMap<String, NativeResource>,
) {

    val callee = symbol.owner
    val qualifiedName = callee.fqNameWhenAvailable?.asString() ?: return

    val declared = callee.declaredParameters()
    val id = AdapterId.of(qualifiedName, declared.map { parameter -> parameter.name.asString() })

    val supplied = declared
        .filter { parameter -> arguments.getOrNull(parameter.indexInParameters) != null }
        .map { parameter -> parameter.name.asString() }
        .toSet()

    val existing = adapters[id]

    // The call with the most arguments wins: it is the only one whose shape has
    // somewhere to put each of them.
    if (existing == null || supplied.size > existing.suppliedParameters.size) {
        adapters[id] = NativeAdapter(id = id, template = this, suppliedParameters = supplied)
    }

    for (parameter in declared) {

        val argument = arguments.getOrNull(parameter.indexInParameters) ?: continue

        // A screen parameter handed straight to a component.
        (argument as? IrGetValue)?.let { forwarded ->

            val arity = forwarded.type.unitFunctionArity() ?: return@let
            val name = forwarded.symbol.owner.name.asString()
            val id = CapabilityId.of(listOf(CapabilityId.Invoke(null, name)))

            capabilities[id] = NativeCapability(
                id = id,
                arity = arity,
                forwarded = forwarded.symbol.owner,
            )
        }

        // Composable content is drawn by the bundle, not run as an action.
        if (parameter.type.isComposableContent()) continue

        val lambda = (argument as? IrFunctionExpression)?.function ?: continue

        // An empty handler reaches IR as a synthetic Unit, which does nothing
        // rather than being something this cannot name. The extraction pass
        // drops the same statement, so the two agree on the empty case.
        val statements = (lambda.body as? IrBlockBody)?.statements
            .orEmpty()
            .filterNot { statement -> statement.producesNothing() }
        val parameterNames = lambda.parameters
            .filter { it.kind == IrParameterKind.Regular }
            .map { it.name.asString() }

        val rendered = statements.map { statement ->
            statement.capabilityStatement(parameterNames) ?: continue
        }

        val capabilityId = CapabilityId.of(rendered)

        capabilities[capabilityId] = NativeCapability(
            id = capabilityId,
            arity = parameterNames.size,
            lambda = lambda,
        )
    }
}

/**
 * `painterResource(R.drawable.x)` and its siblings, as a name plus this build's
 * own number.
 *
 * The number is what the app needs and the name is what the bundle carries: the
 * number is assigned by the build and is not the same next time, so a bundle
 * that carried it would resolve to whatever resource landed on it.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrCall.recordResource(resources: MutableMap<String, NativeResource>) {

    val name = symbol.owner.fqNameWhenAvailable?.asString() ?: return

    if (name != PAINTER_RESOURCE && name != STRING_RESOURCE) return

    val id = arguments.filterNotNull().firstOrNull() ?: return
    val key = id.resourceKey() ?: return

    resources[key] = NativeResource(key, id)
}

/** `R.drawable.brush_24px` as `drawable:brush_24px`. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrExpression.resourceKey(): String? {

    val call = this as? IrCall ?: return null
    val owner = call.symbol.owner

    // A resource is read through its generated getter, so the property's own
    // name is what the getter is named after.
    val property = owner.correspondingPropertySymbol?.owner?.name?.asString()
        ?: owner.name.asString().removePrefix("<get-").removeSuffix(">")

    val parent = owner.parentClassName() ?: return null
    if (parent.parent().shortName().asString() != "R") return null

    return ResourceKey.of(parent.shortName().asString(), property)
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrSimpleFunction.parentClassName(): FqName? =
    (parent as? org.jetbrains.kotlin.ir.declarations.IrClass)?.fqNameWhenAvailable

/**
 * One statement of a handler, in the form the contract renders.
 *
 * Must produce exactly what the extraction pass produces from the same source,
 * because the two are separate compilations and the name is the only thing
 * joining them. Where this cannot name a statement the extraction pass cannot
 * either, so the screen keeps its native implementation instead.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrElement.capabilityStatement(
    parameterNames: List<String>,
): CapabilityId.Statement? {

    val call = this as? IrCall ?: return null
    val owner = call.symbol.owner
    val name = owner.name.asString()

    val receiver = call.arguments.getOrNull(0)?.let { argument ->
        (argument as? IrGetValue)?.symbol?.owner?.name?.asString()
    }

    val supplied = owner.declaredParameters()
        .mapNotNull { parameter -> call.arguments.getOrNull(parameter.indexInParameters) }

    // `state.value = x` reaches IR as a call to the property's setter.
    if (name.startsWith("<set-") && receiver != null) {
        val property = name.removePrefix("<set-").removeSuffix(">")
        val value = supplied.firstOrNull()?.capabilityArgument(parameterNames) ?: return null
        return CapabilityId.Assign(receiver, property, value)
    }

    val arguments = supplied.map { argument ->
        argument.capabilityArgument(parameterNames) ?: return null
    }

    // Calling a parameter that is itself a function reaches IR as `invoke` on
    // the function object. The extraction pass sees the parameter's own name,
    // so this has to report that rather than `invoke`.
    if (name == "invoke" && receiver != null) {
        return CapabilityId.Invoke(null, receiver, arguments)
    }

    return CapabilityId.Invoke(receiver, name, arguments)
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrExpression.capabilityArgument(
    parameterNames: List<String>,
): CapabilityId.Argument? {

    (this as? IrConst)?.let { constant ->
        val value = constant.value
        return CapabilityId.Literal(
            if (value is String) "\"$value\"" else value.toString()
        )
    }

    val name = (this as? IrGetValue)?.symbol?.owner?.name?.asString() ?: return null

    val index = parameterNames.indexOf(name)

    return if (index >= 0) CapabilityId.Parameter(index) else CapabilityId.Read(name)
}

private fun IrElement.producesNothing(): Boolean {

    val produced = (this as? IrReturn)?.value ?: this

    if (produced is IrGetObjectValue) return true

    return produced is IrConst && produced.value == null
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrCall.isComponent(
    layouts: Set<FqName>,
    declaredInBody: Set<IrValueDeclaration>,
): Boolean {

    val callee = symbol.owner

    if (!callee.hasAnnotation(COMPOSABLE_ANNOTATION)) return false

    // A composable that produces a value is read, not placed. `MaterialTheme
    // .colorScheme` is a composable property, and registering it as a component
    // gave the app an adapter whose whole body was an expression it threw away.
    if (!callee.returnType.isUnit()) return false

    if ((callee.fqNameWhenAvailable ?: FqName.ROOT) in layouts) return false

    val outer = declaredInBody - declarations()

    var readsOuter = false

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (element is IrGetValue && element.symbol.owner in outer) readsOuter = true
            element.acceptChildrenVoid(this)
        }
    })

    return !readsOuter
}

/**
 * Everything declared inside this element, including the receiver a layout
 * hands its content.
 *
 * An adapter is lifted out into a standalone lambda, so a component that reads
 * one of these has nothing left to read.
 */
private fun IrElement.declarations(): Set<IrValueDeclaration> {

    val declared = mutableSetOf<IrValueDeclaration>()

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (element is IrVariable) declared += element
            if (element is IrFunction) declared += element.parameters
            element.acceptChildrenVoid(this)
        }
    })

    return declared
}

/**
 * The parameters a composable actually declares.
 *
 * Not the ones it ends up with. The Compose compiler adds `${'$'}composer` and
 * `${'$'}changed` to every composable, and a declaration read from a dependency has
 * already been through that -- while the same declaration read during analysis
 * has not. Naming an adapter from the unfiltered list would give the app
 * `Icon(${'$'}changed|${'$'}composer|painter|…)` and the bundle `Icon(painter|…)`, which
 * is a disagreement with no symptom until a component is missing on a device.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrFunction.declaredParameters(): List<IrValueParameter> =
    parameters.filter { parameter ->
        parameter.kind == IrParameterKind.Regular &&
            !parameter.name.asString().startsWith("$")
    }

private val IrValueParameter.indexInParameters: Int
    get() = (parent as IrFunction).parameters.indexOf(this)

private const val PAINTER_RESOURCE = "androidx.compose.ui.res.painterResource"
private const val STRING_RESOURCE = "androidx.compose.ui.res.stringResource"
