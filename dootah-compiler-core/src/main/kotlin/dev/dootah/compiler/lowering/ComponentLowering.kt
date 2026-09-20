package dev.dootah.compiler.lowering

import dev.dootah.compiler.source.*
import dev.dootah.compiler.source.COMPOSABLE_ANNOTATION
import dev.dootah.compiler.model.BundleCapability
import dev.dootah.compiler.model.BundleEntry
import dev.dootah.compiler.model.BundleExpression
import dev.dootah.compiler.model.BundleModifierOp
import dev.dootah.compiler.model.BundleProp
import dev.dootah.compiler.model.BundleUi
import dev.dootah.contract.AdapterId
import dev.dootah.contract.BuilderScopes
import dev.dootah.contract.CapabilityId
import dev.dootah.contract.ComposeFunctionTypes
import dev.dootah.contract.ModifierOps
import dev.dootah.contract.PropValue
import dev.dootah.contract.ResourceKey
import dev.dootah.contract.Shapes
import dev.dootah.contract.ThemeColors

/** What lowering a screen's components produced, beyond the tree itself. */
class ComponentRequirements {

    /** Every adapter the screen places, so the app knows what to generate. */
    val adapters = linkedSetOf<String>()

    /** Every action lifted out of the source, deduplicated by what it does. */
    val capabilities = linkedSetOf<BundleCapability>()

    /** Every resource named, so the app can map the names to its own numbers. */
    val resources = linkedSetOf<String>()

    /** Every screen parameter routed into a component untouched. */
    val handles = linkedSetOf<String>()

    /** Every app-owned value named rather than computed -- see `AnchorId`. */
    val anchors = linkedSetOf<String>()

    /**
     * Every builder region the screen asks the app to perform.
     *
     * Kept apart from [adapters] because the app registers them as a different
     * kind of thing: an adapter draws, a builder region declares entries against
     * a scope. A build that has the one and not the other has to say so.
     */
    val builders = linkedSetOf<String>()

    /**
     * What this had collected at some earlier point, and how to go back to it.
     *
     * Lowering now tries things that are allowed to fail -- that is what lets one
     * unsupported corner degrade instead of refusing the screen -- and an attempt
     * that fails must leave nothing behind. An adapter recorded by a subtree that
     * was then thrown away would be a requirement on the installed app that
     * nothing in the published bundle ever asks for.
     */
    fun snapshot(): Snapshot = Snapshot(
        adapters = adapters.toList(),
        capabilities = capabilities.toList(),
        resources = resources.toList(),
        handles = handles.toList(),
        anchors = anchors.toList(),
        builders = builders.toList(),
    )

    fun restore(snapshot: Snapshot) {
        adapters.clear(); adapters += snapshot.adapters
        capabilities.clear(); capabilities += snapshot.capabilities
        resources.clear(); resources += snapshot.resources
        handles.clear(); handles += snapshot.handles
        anchors.clear(); anchors += snapshot.anchors
        builders.clear(); builders += snapshot.builders
    }

    class Snapshot(
        val adapters: List<String>,
        val capabilities: List<BundleCapability>,
        val resources: List<String>,
        val handles: List<String>,
        val anchors: List<String>,
        val builders: List<String>,
    )
}

/**
 * Turns a call to a composable Dootah does not describe into a component
 * instance.
 *
 * This is how a real screen is bundled without Dootah reimplementing Compose. An
 * icon, a dropdown menu, an app's own card: the composable itself keeps running
 * natively, and what the bundle carries is which one to place and what to give
 * it.
 *
 * Every argument has to be expressible in the contract's vocabulary, and an
 * argument that is not refuses the call. That refusal is deliberate and it is
 * the whole security boundary: there is no case here that serialises a JVM
 * object, and no case that names something the app did not already have. A
 * bundle can choose among an app's resources, actions and parameters; it cannot
 * introduce one.
 */

class ComponentLowering(
    private val signature: List<ScreenParameter>,
    private val reject: (Int?, String, String, RejectionCode, String?) -> Unit,
    private val lowerExpression: (SourceExpression) -> BundleExpression?,

    /** Whether an element reads something the screen's own body declares. */
    private val readsBody: (SourceElement) -> Boolean,
) {

    val requirements = ComponentRequirements()

    private companion object {
        const val UNNAMED = "unknown"
    }

    /**
     * Lowers one call, with [lowerContent] used for any argument that is itself
     * composable content.
     *
     * Content recurses through the ordinary UI lowering rather than through this
     * one, because the children of a native component are not required to be
     * native: an icon button's content may be an `Icon` this cannot describe, a
     * `Text` it can, or a whole layout.
     */
    fun lower(
        call: SourceFunctionCall,
        lowerContent: (SourceAnonymousFunction) -> List<BundleUi>?,
        lowerEntries: (SourceAnonymousFunction) -> List<BundleEntry>?,
    ): BundleUi.ComponentUi? {

        val callee = call.calleeReference.toResolvedCallableSymbol()
        val qualifiedName = callee?.callableId?.asSingleFqName()?.asString()
        val shortName = callee?.name?.asString() ?: "unknown"

        if (callee == null || qualifiedName == null) {
            reject(
                call.sourceOffset(),
                "a call Dootah could not resolve",
                "Keep this screen native.",
                RejectionCode.UNRESOLVED_CALL,
                null,
            )
            return null
        }

        // Named by the declaration's own parameters, never by the arguments this
        // call happened to supply: which arguments are supplied is exactly what
        // an update has to be free to change.
        val declaredParameters = (callee.declaration as? SourceFunction)
            ?.valueParameters
            ?.map { parameter -> parameter.name.asString() }
            .orEmpty()
        val adapterId = AdapterId.of(qualifiedName, declaredParameters)

        val mapping = call.resolvedArgumentMapping

        if (mapping == null) {
            reject(
                call.sourceOffset(),
                "`$shortName()`, whose arguments Dootah could not read",
                "Simplify the call, or keep this screen native.",
                RejectionCode.UNREADABLE_COMPONENT_ARGUMENTS,
                qualifiedName,
            )
            return null
        }

        val props = LinkedHashMap<String, BundleProp>()
        val children = LinkedHashMap<String, List<BundleUi>>()
        val entries = LinkedHashMap<String, List<BundleEntry>>()

        for ((expression, parameter) in mapping) {

            val name = parameter.name.asString()

            if (parameter.isComposableContent()) {

                val lambda = (expression as? SourceAnonymousFunctionExpression)?.anonymousFunction

                if (lambda == null) {
                    reject(
                        call.sourceOffset(),
                        "`$shortName()`, whose `$name` is not written as a lambda",
                        "Pass the content as a lambda, or keep this screen native.",
                        RejectionCode.CONTENT_NOT_A_LAMBDA,
                        qualifiedName,
                    )
                    return null
                }

                children[name] = lowerContent(lambda) ?: return null
                continue
            }

            // A slot the component builds rather than draws. The lambda is not
            // content and is never lowered as UI: its statements declare
            // entries, and what the bundle carries is which entries there are.
            if (parameter.isDescribableBuilder()) {

                val lambda = (expression as? SourceAnonymousFunctionExpression)?.anonymousFunction

                if (lambda == null) {
                    reject(
                        call.sourceOffset(),
                        "`$shortName()`, whose `$name` is not written as a lambda",
                        "Pass the entries as a lambda, or keep this screen native.",
                        RejectionCode.CONTENT_NOT_A_LAMBDA,
                        qualifiedName,
                    )
                    return null
                }

                entries[name] = lowerEntries(lambda) ?: return null
                continue
            }

            props[name] = lowerProp(expression, parameter, shortName) ?: return null
        }

        requirements.adapters += adapterId

        return BundleUi.ComponentUi(
            adapterId = adapterId,
            props = props,
            children = children,
            entries = entries,
        )
    }

    /**
     * Turns one argument into something the bundle can send.
     *
     * The order matters: the specific forms are recognised before the general
     * one, because `48.dp` and `MaterialTheme.colorScheme.primary` are both
     * property reads and only their resolved names tell them apart.
     */
    private fun lowerProp(
        expression: SourceExpression,
        parameter: SourceValueParameter,
        shortName: String,
    ): BundleProp? {

        constantProp(expression)?.let { return it }

        // An `if` whose branches are both expressible. This is where a colour
        // chosen by a remotely computed condition comes from, and lowering it to
        // a constant would throw away the part an update is for.
        (expression as? SourceWhenExpression)?.let { conditional ->
            conditionalProp(conditional, parameter, shortName)?.let { return it }
        }

        modifierProp(expression)?.let { return it }

        callbackProp(expression, parameter)?.let { return it }

        // Anything the bundle can work out for itself, sent as the scalar the
        // parameter declares.
        val kind = parameter.returnTypeRef.coneTypeSafe<SourceType>().scalarKind()

        if (kind != null) {
            lowerExpression(expression)?.let { computed ->
                return BundleProp.Computed(kind, computed)
            }
        }

        val type = parameter.returnTypeRef.coneTypeSafe<SourceType>()
            ?.classId?.asSingleFqName()?.asString()

        reject(
            expression.sourceOffset(),
            "`$shortName()`'s `${parameter.name.asString()}`" +
                (type?.let { " (a $it)" } ?: "") +
                ", which Dootah cannot work out",
            "A native component may be given a constant, a value this screen " +
                "computes, one of the screen's own parameters, a resource or " +
                "theme token, a modifier, or one of the screen's handlers. " +
                "Anything else has to stay in a native screen.",
            RejectionCode.UNSUPPORTED_COMPONENT_ARGUMENT,
            // What was written, not what the parameter is declared as. A
            // hundred arguments refused for being `MaterialTheme.colorScheme
            // .primary` are one problem; a hundred refused for "being a String"
            // are a hundred puzzles, because String is a type Dootah carries and
            // the type was never what stopped it.
            expression.describe(type),
        )

        return null
    }

    /**
     * Names an expression by what it is, for counting across apps.
     *
     * The argument's declared type is the last resort rather than the first,
     * because it is the answer least likely to be the reason. It is the reason
     * only when the expression is an ordinary read of an ordinary value -- a
     * `Context`, a view model -- and then the type is exactly what to report.
     */
    private fun SourceExpression.describe(type: String?): String = when (this) {

        is SourceFunctionCall ->
            "call:" + (resolvedCallableName()?.asString() ?: UNNAMED)

        is SourcePropertyAccessExpression -> {
            val name = calleeReference.toResolvedCallableSymbol()
                ?.callableId?.asSingleFqName()?.asString()
            if (name == null) "value:" + (type ?: UNNAMED) else "property:$name"
        }

        is SourceWhenExpression -> "conditional"

        is SourceAnonymousFunctionExpression -> "lambda:" + (type ?: UNNAMED)

        else -> "expression:" + (this::class.simpleName ?: UNNAMED)
    }

    /** The forms whose value is fixed the moment the screen is lowered. */
    private fun constantProp(expression: SourceExpression): BundleProp? {

        literalValue(expression)?.let { return BundleProp.Constant(it) }

        resourceProp(expression)?.let { return BundleProp.Constant(it) }

        themeColour(expression)?.let { return BundleProp.Constant(it) }

        shapeToken(expression)?.let { return BundleProp.Constant(it) }

        dpValue(expression)?.let { return BundleProp.Constant(it) }

        colourValue(expression)?.let { return BundleProp.Constant(it) }

        screenParameterProp(expression)?.let { return BundleProp.Constant(it) }

        return null
    }

    private fun literalValue(expression: SourceExpression): PropValue? =
        when (val value = (expression as? SourceLiteralExpression)?.value) {
            null -> null
            is Boolean -> PropValue.BoolValue(value)
            is Int -> PropValue.IntValue(value)
            is Long -> PropValue.LongValue(value)
            is Float -> PropValue.FloatValue(value)
            is Double -> PropValue.DoubleValue(value)
            is String -> PropValue.StringValue(value)
            else -> null
        }

    /**
     * `painterResource(R.drawable.x)` and `stringResource(R.string.x)`.
     *
     * The resource travels as its name. `R.drawable.x` is a number this build
     * assigned and will not assign again, so a bundle carrying the number would
     * resolve to whatever resource happened to land on it next time -- and a
     * bundle carrying the name can choose among what the app already ships
     * without being able to invent anything.
     */
    private fun resourceProp(expression: SourceExpression): PropValue? {

        val call = expression as? SourceFunctionCall ?: return null
        val callable = call.resolvedCallableName() ?: return null

        val key = call.arguments.firstOrNull()?.let { resourceKey(it) } ?: return null

        return when (callable) {
            SupportedCatalog.PAINTER_RESOURCE -> PropValue.PainterResourceValue(key)
            SupportedCatalog.STRING_RESOURCE -> PropValue.StringResourceValue(key)
            else -> null
        }?.also { requirements.resources += key }
    }

    /** `R.drawable.brush_24px` as `drawable:brush_24px`. */
    private fun resourceKey(expression: SourceExpression): String? {

        val access = expression as? SourcePropertyAccessExpression ?: return null
        val name = access.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null

        val qualifier = access.explicitReceiver as? SourceResolvedQualifier ?: return null
        val relative = qualifier.relativeClassFqName ?: return null

        // Guarded so an unrelated `Something.drawable.x` cannot look like one --
        // and guarded against having no enclosing name at all, which is what a
        // top-level object like `Modifier.fillMaxWidth` is. Asking such a name
        // for its parent's short name throws, and thrown from a frontend checker
        // that does not fail the build, it aborts the whole file's analysis.
        if (!relative.namesResource()) return null

        return ResourceKey.of(relative.shortName().asString(), name)
    }

    /**
     * Whether this is the `R.<type>` half of a resource reference.
     *
     * `R.drawable` has both an enclosing name and a short one; `Modifier` has
     * only a short one, and `FqName.parent()` on it is the root, which refuses
     * to be asked for a name.
     */
    private fun FqName.namesResource(): Boolean {
        if (isRoot) return false
        val enclosing = parent()
        return !enclosing.isRoot && enclosing.shortName().asString() == "R"
    }

    /** `MaterialTheme.colorScheme.inversePrimary`, resolved by the app's theme. */
    private fun themeColour(expression: SourceExpression): PropValue? {

        val access = expression as? SourcePropertyAccessExpression ?: return null
        val token = access.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null

        val scheme = access.explicitReceiver as? SourcePropertyAccessExpression ?: return null
        if (scheme.resolvedCallableName() != SupportedCatalog.COLOR_SCHEME_PROPERTY) return null

        if (!ThemeColors.isKnown(token)) return null

        return PropValue.ThemeColorValue(token)
    }

    private fun shapeToken(expression: SourceExpression): PropValue? =
        when ((expression as? SourcePropertyAccessExpression)?.resolvedCallableName()) {
            SupportedCatalog.CIRCLE_SHAPE -> PropValue.ShapeValue(Shapes.CIRCLE)
            SupportedCatalog.RECTANGLE_SHAPE -> PropValue.ShapeValue(Shapes.RECTANGLE)
            else -> null
        }

    /** `48.dp`, whichever numeric type it is written on. */
    private fun dpValue(expression: SourceExpression): PropValue? {

        val access = expression as? SourcePropertyAccessExpression ?: return null
        if (access.resolvedCallableName() != SupportedCatalog.DP_PROPERTY) return null

        // Any number, because the frontend chooses the box: an integer literal
        // arrives as a `Long` whatever it was written as, so listing the types
        // a developer can type silently refused every `48.dp` ever written.
        val amount = (access.explicitReceiver as? SourceLiteralExpression)?.value as? Number
            ?: return null

        return PropValue.DpValue(amount.toDouble())
    }

    /** `Color(0xFF2196F3)` and `Color.White`. */
    private fun colourValue(expression: SourceExpression): PropValue? {

        (expression as? SourceFunctionCall)
            ?.takeIf { it.resolvedCallableName() == SupportedCatalog.COLOR_FUNCTION }
            ?.arguments?.firstOrNull()
            ?.let { argument -> (argument as? SourceLiteralExpression)?.value as? Long }
            ?.let { return PropValue.ColorValue(it) }

        val access = expression as? SourcePropertyAccessExpression ?: return null
        val name = access.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null
        val qualifier = access.explicitReceiver as? SourceResolvedQualifier ?: return null

        if (!qualifier.namesCompanionOf(SupportedCatalog.COLOR_COMPANION)) return null

        return SupportedCatalog.NAMED_COLORS[name]?.let { PropValue.ColorValue(it) }
    }

    /**
     * One of the screen's own parameters, routed into a component untouched.
     *
     * A coordinate rather than a reference: the bundle says which parameter, and
     * the app takes the object out of a table it builds at its own call site.
     * This is how a view model, a list of domain objects or a `MutableState`
     * reaches a native component without the bundle ever seeing one -- and it is
     * why there is no need for a way to serialise an arbitrary object.
     *
     * `state.value` is recognised separately so the read happens inside the
     * composition, which is what makes the component recompose when the state
     * changes.
     */
    private fun screenParameterProp(expression: SourceExpression): PropValue? {

        val access = expression as? SourcePropertyAccessExpression ?: return null
        val name = access.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null

        if (name == "value") {
            val owner = (access.explicitReceiver as? SourcePropertyAccessExpression)
                ?.nativeParameterName()

            if (owner != null) {
                requirements.handles += owner
                return PropValue.StateValue(owner)
            }
        }

        val handle = access.nativeParameterName() ?: return null

        requirements.handles += handle

        return PropValue.HandleValue(handle)
    }

    /**
     * Whether this qualifier names [companion]'s owner or [companion] itself.
     *
     * `Color.White` and `Modifier.size(...)` resolve their leading `Color` and
     * `Modifier` to the *type*; only the spellings nobody writes --
     * `Color.Companion.White`, `Modifier.Companion.size(...)` -- resolve to the
     * companion. Comparing against the companion alone therefore matched
     * neither of the two forms a screen is actually written in, and refused
     * every named colour and every modifier chain in the codebase.
     */
    private fun SourceResolvedQualifier.namesCompanionOf(companion: FqName): Boolean {

        val named = classId?.asSingleFqName() ?: return false

        return named == companion || named == companion.parent()
    }

    /** The name, if this reads a parameter Dootah cannot serialise. */
    private fun SourcePropertyAccessExpression.nativeParameterName(): String? {

        if (explicitReceiver != null) return null

        val name = calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null

        return name.takeIf { candidate ->
            signature.any { parameter ->
                parameter is ScreenParameter.NativeOnly && parameter.name == candidate
            }
        }
    }

    private fun conditionalProp(
        expression: SourceWhenExpression,
        parameter: SourceValueParameter,
        shortName: String,
    ): BundleProp? {

        val branches = expression.branches
        if (branches.size != 2) return null

        val condition = lowerExpression(branches[0].condition) ?: return null

        val ifTrue = branches[0].result.onlyExpression()
            ?.let { result -> lowerProp(result, parameter, shortName) } ?: return null

        val ifFalse = branches[1].result.onlyExpression()
            ?.let { result -> lowerProp(result, parameter, shortName) } ?: return null

        return BundleProp.Conditional(condition, ifTrue, ifFalse)
    }

    /**
     * A `Modifier` chain, described rather than copied.
     *
     * Read back to front, because `Modifier.size(48.dp).background(...)` is a
     * call to `background` whose receiver is the call to `size`.
     */
    private fun modifierProp(expression: SourceExpression): BundleProp? {

        val operations = mutableListOf<BundleModifierOp>()
        var current: SourceExpression? = expression

        while (true) {

            // Two spellings of the same start. `Modifier.size(...)` resolves
            // its receiver to a qualifier, while `Modifier.Companion.size(...)`
            // resolves it to a property read.
            val qualifier = current as? SourceResolvedQualifier
            if (qualifier?.namesCompanionOf(SupportedCatalog.MODIFIER_COMPANION) == true) {
                return BundleProp.Modifier(operations.reversed())
            }

            val access = current as? SourcePropertyAccessExpression
            if (access != null && access.resolvedCallableName() == SupportedCatalog.MODIFIER_COMPANION) {
                return BundleProp.Modifier(operations.reversed())
            }

            // The screen's own `modifier` parameter, where a chain the caller
            // started bottoms out. `modifier.padding(...)` on a component is as
            // ordinary as it is on a layout, and a layout has always been able
            // to say so; refusing it here refused the component, and with it any
            // screen that passed its modifier down -- which is the convention
            // every Compose style guide asks for.
            if (access != null && access.explicitReceiver == null &&
                access.calleeReference.toResolvedCallableSymbol()?.name?.asString()
                    ?.let { name -> signature.any { it is ScreenParameter.LayoutModifier && it.name == name } } == true
            ) {
                operations += BundleModifierOp(ModifierOps.INHERITED, emptyMap())
                return BundleProp.Modifier(operations.reversed())
            }

            val call = current as? SourceFunctionCall ?: return null
            val name = modifierOperation(call) ?: return null
            val arguments = modifierArguments(call, name) ?: return null

            operations += BundleModifierOp(name, arguments)
            current = call.explicitReceiver ?: return null
        }
    }

    private fun modifierOperation(call: SourceFunctionCall): String? =
        when (call.resolvedCallableName()) {
            SupportedCatalog.SIZE -> ModifierOps.SIZE
            SupportedCatalog.WIDTH -> ModifierOps.WIDTH
            SupportedCatalog.HEIGHT -> ModifierOps.HEIGHT
            SupportedCatalog.PADDING -> ModifierOps.PADDING
            SupportedCatalog.FILL_MAX_WIDTH -> ModifierOps.FILL_MAX_WIDTH
            SupportedCatalog.FILL_MAX_HEIGHT -> ModifierOps.FILL_MAX_HEIGHT
            SupportedCatalog.FILL_MAX_SIZE -> ModifierOps.FILL_MAX_SIZE
            SupportedCatalog.BACKGROUND -> ModifierOps.BACKGROUND
            else -> null
        }

    private fun modifierArguments(
        call: SourceFunctionCall,
        operation: String,
    ): Map<String, BundleProp>? {

        val mapping = call.resolvedArgumentMapping ?: return null
        val arguments = LinkedHashMap<String, BundleProp>()

        for ((expression, parameter) in mapping) {
            val value = constantProp(expression)
                ?: (expression as? SourceWhenExpression)?.let { conditional ->
                    conditionalProp(conditional, parameter, operation)
                }
                // A length the app owns rather than one this bundle chose.
                // `modifier.padding(horizontal = defaultSpacerSize)` is how a
                // screen spaces itself by the app's own scale, and refusing it
                // refused the component it was on.
                ?: anchorProp(expression)
                ?: return null
            arguments[parameter.name.asString()] = value
        }

        // `size(48.dp)` is one argument where the app takes two.
        if (operation == ModifierOps.SIZE && arguments.size == 1) {
            val only = arguments.values.first()
            return mapOf("width" to only, "height" to only)
        }

        if (operation == ModifierOps.PADDING) return arguments.asEdges()

        // The names the app's renderer reads are the contract's, not the ones
        // Compose happened to spell the overload with. An argument outside them
        // is dropped on the way in and the operation is applied with nothing --
        // a padding that became zero, on a screen that looked almost right.
        // Refusing here keeps the component native instead.
        val declared = ModifierOps.ARGUMENTS[operation] ?: return null

        return arguments.takeIf { it.keys.all { name -> name in declared } }
    }

    /**
     * Padding as the four edges the contract names.
     *
     * Compose writes the same padding four ways -- `all`, `horizontal`,
     * `vertical`, or the edges -- and the renderer reads only the edges. The
     * layout path has always normalised this; the component path passed the
     * spelling straight through, so `padding(horizontal = defaultSpacerSize)`
     * arrived as an argument called `horizontal` that nothing read.
     */
    private fun Map<String, BundleProp>.asEdges(): Map<String, BundleProp>? {

        val zero: BundleProp = BundleProp.Constant(PropValue.DpValue(0.0))
        val edges = linkedMapOf<String, BundleProp>(
            "start" to zero, "top" to zero, "end" to zero, "bottom" to zero,
        )

        forEach { (name, value) ->
            when (name) {
                "all" -> edges.keys.forEach { edge -> edges[edge] = value }
                "horizontal" -> { edges["start"] = value; edges["end"] = value }
                "vertical" -> { edges["top"] = value; edges["bottom"] = value }
                "start", "top", "end", "bottom" -> edges[name] = value
                else -> return null
            }
        }

        return edges
    }

    /** A length the app computes, named rather than read -- see `AnchorId`. */
    private fun anchorProp(expression: SourceExpression): BundleProp? {

        val anchor = AnchorLowering.dimension(expression)?.anchor ?: return null

        requirements.anchors += anchor

        return BundleProp.Constant(PropValue.AnchorValue(anchor))
    }

    /**
     * A handler written in the source, lifted into the APK as a capability.
     *
     * The bundle gets the capability's name and nothing else: it can attach the
     * handler to any component it likes, and cannot write one, change what one
     * does, or reach anything the original lambda did not already touch.
     */
    private fun callbackProp(
        expression: SourceExpression,
        parameter: SourceValueParameter,
    ): BundleProp? {

        val type = parameter.returnTypeRef.coneTypeSafe<SourceType>() ?: return null

        // A lambda with a receiver is a builder, not a handler. `LazyColumn`'s
        // content is `LazyListScope.() -> Unit`: its body is a list of things to
        // declare on a scope only the app can make, and the statements inside it
        // are not calls the app runs on a tap. Lowering one anyway named
        // capabilities out of an app's own `LazyListScope` extensions, which the
        // app's pass does not register and would not know how to call -- a
        // screen that published and then had nothing in its list.
        //
        // Composable content with a receiver is a different thing and stays
        // allowed: there the app calls the component and supplies the scope, and
        // the bundle only says what goes inside.
        if (type.isExtensionFunctionType) return null

        val arity = type.unitFunctionArity() ?: return null

        // A screen callback passed straight through is the same capability as
        // one written inline that does nothing but call it.
        (expression as? SourcePropertyAccessExpression)?.callbackParameterName()?.let { name ->
            return capability(listOf(CapabilityId.Invoke(null, name)), arity)
        }

        val lambda = (expression as? SourceAnonymousFunctionExpression)?.anonymousFunction
            ?: return null

        // An action is lifted out of the body whole, so it cannot read anything
        // the body declares -- the app's own pass refuses to register one that
        // does, and naming a capability it will not have is a hole in the screen
        // rather than a component that keeps working.
        if (readsBody(lambda)) return null

        val parameterNames = lambda.valueParameters.map { it.name.asString() }
        // Through the same unwrapping the rest of lowering uses, so an empty
        // handler is a capability that does nothing rather than a refusal.
        val statements = lambda.body?.statements.orEmpty().mapNotNull { it.unwrapReturn() }

        val lowered = statements.map { statement ->
            capabilityStatement(statement, parameterNames) ?: return null
        }

        return capability(lowered, arity)
    }

    private fun capability(
        statements: List<CapabilityId.Statement>,
        arity: Int,
    ): BundleProp {

        val id = CapabilityId.of(statements)

        requirements.capabilities += BundleCapability(id, arity)

        return BundleProp.Constant(PropValue.CallbackValue(id, arity))
    }

    /**
     * One statement of a handler, in the normalised form both passes render.
     *
     * Only these forms can be named. A handler with a condition, a loop or a
     * local has no stable rendering, and the screen keeps its native
     * implementation rather than being given a capability whose identity the two
     * compilations might not agree on.
     */
    private fun capabilityStatement(
        statement: Any?,
        parameterNames: List<String>,
    ): CapabilityId.Statement? {

        if (statement is SourceVariableAssignment) {

            val target = statement.lValue as? SourcePropertyAccessExpression ?: return null
            val property = target.calleeReference.toResolvedCallableSymbol()?.name?.asString()
                ?: return null

            val receiver = (target.explicitReceiver as? SourcePropertyAccessExpression)
                ?.calleeReference?.toResolvedCallableSymbol()?.name?.asString()
                ?: return null

            val value = capabilityArgument(statement.rValue, parameterNames) ?: return null

            return CapabilityId.Assign(receiver, property, value)
        }

        if (statement is SourceFunctionCall) {

            val member = statement.calleeReference.toResolvedCallableSymbol()?.name?.asString()
                ?: return null

            val receiver = (statement.explicitReceiver as? SourcePropertyAccessExpression)
                ?.calleeReference?.toResolvedCallableSymbol()?.name?.asString()

            val arguments = statement.arguments.map { argument ->
                capabilityArgument(argument, parameterNames) ?: return null
            }

            // Calling one of the screen's own function parameters resolves to
            // `invoke` on the function object. The other pass sees the
            // parameter's own name, and the two have to render the same thing
            // or the capability the app generated is not the one the bundle
            // asks for.
            if (member == "invoke" && receiver != null) {
                return CapabilityId.Invoke(null, receiver, arguments)
            }

            return CapabilityId.Invoke(receiver, member, arguments)
        }

        return null
    }

    private fun capabilityArgument(
        expression: SourceExpression,
        parameterNames: List<String>,
    ): CapabilityId.Argument? {

        (expression as? SourceLiteralExpression)?.value?.let { value ->
            return CapabilityId.Literal(
                when (value) {
                    is String -> "\"$value\""
                    else -> value.toString()
                }
            )
        }

        val access = expression as? SourcePropertyAccessExpression ?: return null
        val name = access.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null

        val index = parameterNames.indexOf(name)
        if (index >= 0) return CapabilityId.Parameter(index)

        return CapabilityId.Read(name)
    }

    private fun SourcePropertyAccessExpression.callbackParameterName(): String? {

        if (explicitReceiver != null) return null

        val name = calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null

        return name.takeIf { candidate ->
            signature.any { parameter ->
                parameter is ScreenParameter.Callback && parameter.name == candidate
            }
        }
    }
}

/** The single expression a branch produces, if that is all it is. */
private fun SourceBlock.onlyExpression(): SourceExpression? =
    statements.singleOrNull() as? SourceExpression

/**
 * Whether this parameter takes composable content rather than a value.
 *
 * Any arity, because the extra parameters are scopes a layout hands its
 * children -- `Button` takes `@Composable RowScope.() -> Unit`.
 */
/**
 * Whether this parameter is a builder slot a bundle may describe.
 *
 * A lambda on a receiver, returning Unit, not composable, whose receiver is a
 * scope the installed runtime knows how to perform entries against. Everything
 * about that sentence is load-bearing: composable content is drawn rather than
 * built and is handled above; a receiver the runtime does not know is a scope
 * nobody can perform `item` on; and a lambda with no receiver at all is an
 * ordinary handler.
 */
private fun SourceValueParameter.isDescribableBuilder(): Boolean {

    val type = returnTypeRef.coneTypeSafe<SourceType>() ?: return false

    if (!type.isExtensionFunctionType) return false
    if (type.isComposableFunctionType()) return false
    if (!type.returnsUnit()) return false

    val receiver = (type.typeArguments.firstOrNull() as? SourceType)
        ?.classId?.asSingleFqName()?.asString()
        ?: return false

    return BuilderScopes.isDescribable(receiver)
}

private fun SourceValueParameter.isComposableContent(): Boolean {

    val type = returnTypeRef.coneTypeSafe<SourceType>() ?: return false

    return type.isComposableFunctionType() && type.returnsUnit()
}

/**
 * Whether this type is a composable function, however Compose spelled it.
 *
 * A lambda written in the screen being analysed is a `kotlin.FunctionN`
 * carrying `@Composable`; the parameter of an `IconButton` compiled into a
 * library is a `ComposableFunctionN` already. Reading only the first meant
 * every real component's content was taken for a handler, and every screen
 * built out of Material components was refused.
 */
fun SourceType.isComposableFunctionType(): Boolean {

    val name = classId?.asSingleFqName()?.asString() ?: return false

    if (ComposeFunctionTypes.isComposableFunction(name)) return true

    return ComposeFunctionTypes.isFunction(name) && customAnnotations.any { annotation ->
        annotation.annotationTypeRef.coneTypeSafe<SourceType>()
            ?.classId?.asSingleFqName() == COMPOSABLE_ANNOTATION
    }
}

private fun SourceType.returnsUnit(): Boolean =
    (typeArguments.lastOrNull() as? SourceType)?.classId?.asSingleFqName() ==
        FqName("kotlin.Unit")

/**
 * How many arguments this takes, if it is a plain `(…) -> Unit` handler.
 *
 * Composable function types are excluded: content is drawn by the bundle, and
 * attaching it as an action is the mistake this file keeps making.
 */
private fun SourceType.unitFunctionArity(): Int? {

    if (isComposableFunctionType()) return null

    val name = classId?.asSingleFqName()?.asString() ?: return null
    val arity = ComposeFunctionTypes.arityOf(name) ?: return null

    return arity.takeIf { returnsUnit() }
}

/** The contract kind a scalar of this type is sent as. */
private fun SourceType?.scalarKind(): String? =
    when (this?.classId?.asSingleFqName()?.asString()) {
        "kotlin.String" -> PropValue.Kind.STRING
        "kotlin.Int" -> PropValue.Kind.INT
        "kotlin.Boolean" -> PropValue.Kind.BOOL
        "kotlin.Long" -> PropValue.Kind.LONG
        "kotlin.Float" -> PropValue.Kind.FLOAT
        "kotlin.Double" -> PropValue.Kind.DOUBLE
        else -> null
    }
