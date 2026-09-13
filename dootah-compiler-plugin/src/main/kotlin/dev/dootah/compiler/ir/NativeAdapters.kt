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
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrGetField
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
import dev.dootah.contract.FrozenRegionId
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

    /**
     * Whether this adapter runs the call exactly as it was written.
     *
     * A frozen adapter takes nothing from the bundle. It exists for the regions
     * no vocabulary reaches -- a component reading the scope around it, a layout
     * holding one, an argument nothing can express -- so that the rest of the
     * screen can still be described. The bundle's whole power over it is where
     * it appears and whether it appears at all.
     */
    val frozen: Boolean = false,
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
internal fun IrBody.nativeBindings(
    layouts: Set<FqName>,
    sourceText: String = "",
): NativeBindings {

    val adapters = LinkedHashMap<String, NativeAdapter>()
    val capabilities = LinkedHashMap<String, NativeCapability>()
    val resources = LinkedHashMap<String, NativeResource>()

    val declaredInBody = declaredHere()

    acceptVoid(object : IrVisitorVoid() {

        override fun visitElement(element: IrElement) {

            // A frozen region is registered for anything that could become one,
            // layouts included: the extraction pass may choose to keep a whole
            // `Column` as it stands when something inside it cannot be described,
            // and it can only choose what this pass has already registered.
            if (element is IrCall) element.recordFrozen(adapters, declaredInBody, sourceText)

            if (element is IrCall && element.isComponent(layouts, declaredInBody)) {

                element.record(adapters, capabilities, resources, declaredInBody)

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

/**
 * Registers this call as a region the app can draw exactly as written.
 *
 * Named by what it says rather than where it sits, so that deleting a sibling
 * renames nothing -- and so that editing the region itself does rename it, which
 * is how a bundle published from changed native code is refused instead of
 * quietly drawing the old version.
 *
 * The conditions are the two that decide whether the code can be lifted out of
 * the body at all: it has to draw something, and it must not read a name the
 * body declares, because the lifted copy is prepared before the body runs.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrCall.recordFrozen(
    adapters: MutableMap<String, NativeAdapter>,
    declaredInBody: BodyScope,
    sourceText: String,
) {

    if (sourceText.isEmpty()) return

    val callee = symbol.owner

    if (!callee.hasAnnotation(COMPOSABLE_ANNOTATION)) return
    if (!callee.returnType.isUnit()) return

    val qualifiedName = callee.fqNameWhenAvailable?.asString() ?: return

    if (readsOuter(declaredInBody, replaced = emptySet())) return

    val text = sourceText.textOf(this) ?: return
    val id = FrozenRegionId.of(qualifiedName, text)

    adapters.getOrPut(id) {
        NativeAdapter(
            id = id,
            template = this,
            suppliedParameters = emptySet(),
            frozen = true,
        )
    }
}

/**
 * Whether this element reads a name declared in the body around it.
 *
 * Shared by everything that gets lifted out of the body: an action, a frozen
 * region, the parts of a component call that survive being copied.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrElement.readsOutside(declaredInBody: BodyScope): Boolean {

    val outer = declaredHere().let { own ->
        BodyScope(
            values = declaredInBody.values - own.values,
            functions = declaredInBody.functions - own.functions,
        )
    }

    var reads = false

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {

            if (element is IrGetValue && element.symbol.owner in outer.values) reads = true
            if (element is IrCall && element.symbol.owner in outer.functions) reads = true

            element.acceptChildrenVoid(this)
        }
    })

    return reads
}

/**
 * Every value this body declares for itself.
 *
 * The screen's own parameters are deliberately absent: they exist wherever the
 * screen does, so an adapter may read them. Only what the body introduces is out
 * of reach of something lifted above it.
 */
internal fun IrBody.localsDeclaredHere(): Set<IrValueDeclaration> {

    val locals = mutableSetOf<IrValueDeclaration>()

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (element is IrVariable) locals += element
            if (element is IrFunction) locals += element.parameters
            element.acceptChildrenVoid(this)
        }
    })

    return locals
}

/** The source this call was written as, by the offsets it carries. */
private fun String.textOf(element: IrElement): String? {

    val start = element.startOffset
    val end = element.endOffset

    if (start < 0 || end > length || end <= start) return null

    return substring(start, end)
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrCall.record(
    adapters: MutableMap<String, NativeAdapter>,
    capabilities: MutableMap<String, NativeCapability>,
    resources: MutableMap<String, NativeResource>,
    declaredInBody: BodyScope,
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

            // A *screen* parameter, and not something the body declared beside
            // it. The capability closes over whatever it names, and is built
            // before the body runs, so forwarding a local that holds a lambda
            // fails in the JVM backend with `Non-mapped local declaration` --
            // which is what JetNews' interests screen did, passing an
            // `updateSection` it had just declared.
            if (forwarded.symbol.owner in declaredInBody.values) return@let

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

        // An action is lifted out whole, unlike an argument, which is replaced
        // by whatever the bundle sent. So the question the adapter no longer has
        // to ask -- does this read something the body declares? -- is one the
        // action still does.
        //
        // The whole argument and not just the lambda inside it. A handler that
        // came out of a destructured `remember { mutableStateOf(...) }` reaches
        // here as a reference with the local bound *beside* the function rather
        // than read within it, so walking the function alone sees nothing and
        // the JVM backend fails with `Non-mapped local declaration` -- which is
        // what JetNews' interests screen did.
        if (argument.readsOutside(declaredInBody)) continue

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

/**
 * `R.drawable.brush_24px` as `drawable:brush_24px`.
 *
 * Two shapes, because `R` is generated as Java: every Android app reads a
 * resource as a static field, and only a Kotlin stand-in for one reads as a
 * property. Handling the property alone meant no app ever registered a
 * resource, so a bundle naming one was refused and the screen it belonged to
 * stayed native -- which is every screen with an icon in it.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrExpression.resourceKey(): String? {

    (this as? IrGetField)?.let { read ->
        val field = read.symbol.owner
        val parent = (field.parent as? IrClass)?.fqNameWhenAvailable ?: return null
        return resourceKeyIn(parent, field.name.asString())
    }

    val call = this as? IrCall ?: return null
    val owner = call.symbol.owner

    // A resource read through a generated getter, so the property's own name is
    // what the getter is named after.
    val property = owner.correspondingPropertySymbol?.owner?.name?.asString()
        ?: owner.name.asString().removePrefix("<get-").removeSuffix(">")

    val parent = owner.parentClassName() ?: return null

    return resourceKeyIn(parent, property)
}

/**
 * Guarded so an unrelated `Something.drawable.x` cannot look like a resource.
 *
 * And guarded against a name with nothing enclosing it, which is what a
 * top-level object is. `FqName.parent()` on one is the root, and the root
 * refuses to be asked for a short name -- so the check meant to be conservative
 * was instead throwing out of a compiler pass.
 */
private fun resourceKeyIn(owner: FqName, name: String): String? {

    if (owner.isRoot) return null

    val enclosing = owner.parent()
    if (enclosing.isRoot || enclosing.shortName().asString() != "R") return null

    return ResourceKey.of(owner.shortName().asString(), name)
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
    declaredInBody: BodyScope,
): Boolean {

    val callee = symbol.owner

    if (!callee.hasAnnotation(COMPOSABLE_ANNOTATION)) return false

    // A composable that produces a value is read, not placed. `MaterialTheme
    // .colorScheme` is a composable property, and registering it as a component
    // gave the app an adapter whose whole body was an expression it threw away.
    if (!callee.returnType.isUnit()) return false

    if ((callee.fqNameWhenAvailable ?: FqName.ROOT) in layouts) return false

    // Every argument this call supplies is replaced by a read from whatever the
    // bundle sent, so what it was written as cannot be read by the adapter and
    // cannot disqualify it. Only what survives the copy is asked about: the
    // receiver, and anything outside the argument list.
    return !readsOuter(declaredInBody, replaced = suppliedArgumentIndices())
}

/** The argument positions an adapter overwrites when it places this call. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrCall.suppliedArgumentIndices(): Set<Int> =
    symbol.owner.declaredParameters()
        .map { parameter -> parameter.indexInParameters }
        .filter { index -> arguments.getOrNull(index) != null }
        .toSet()

/**
 * Whether this call reads a name that will not exist where the adapter runs.
 *
 * An adapter is lifted out of the body and into a value prepared before the body
 * runs, so anything the body declares is out of reach. [replaced] names the
 * argument positions the adapter overwrites, whose contents therefore never
 * reach the lifted copy.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrCall.readsOuter(declaredInBody: BodyScope, replaced: Set<Int>): Boolean {

    val outer = declaredHere().let { own ->
        BodyScope(
            values = declaredInBody.values - own.values,
            functions = declaredInBody.functions - own.functions,
        )
    }

    val skipped = replaced.mapNotNullTo(mutableSetOf()) { index -> arguments.getOrNull(index) }

    var readsOuter = false

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {

            if (element in skipped) return

            if (element is IrGetValue && element.symbol.owner in outer.values) {
                readsOuter = true
            }

            // A read through something the body declared beside it. This is the
            // only way the read is invisible above: the expression holds a call
            // and never mentions the variable.
            if (element is IrCall && element.symbol.owner in outer.functions) {
                readsOuter = true
            }

            element.acceptChildrenVoid(this)
        }
    })

    return readsOuter
}

/**
 * What a piece of a screen declares for itself.
 *
 * An adapter is lifted out of the body and into an argument evaluated before the
 * body runs, so a component reading anything declared *in* the body has nothing
 * left to read -- and the read is not a compile error but an assertion inside
 * the JVM backend, thrown long after Dootah has finished and naming none of it.
 */
private class BodyScope(
    val values: Set<IrValueDeclaration>,
    val functions: Set<IrSimpleFunction>,
)

/**
 * Everything declared inside this element, including the receiver a layout
 * hands its content and the accessors behind a delegated local.
 *
 * The accessors matter because `var open by remember { mutableStateOf(false) }`
 * is how most screens hold state, and reading `open` compiles to a call to a
 * function the compiler generated beside it. A scan looking only for the
 * variable sees a component that mentions nothing at all, lifts it out, and
 * leaves a read of a local that does not exist yet.
 */
private fun IrElement.declaredHere(): BodyScope {

    val values = mutableSetOf<IrValueDeclaration>()
    val functions = mutableSetOf<IrSimpleFunction>()

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (element is IrVariable) values += element
            if (element is IrFunction) values += element.parameters
            if (element is IrSimpleFunction) functions += element
            element.acceptChildrenVoid(this)
        }
    })

    return BodyScope(values = values, functions = functions)
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
