package dev.dootah.compiler.fir

import dev.dootah.compiler.COMPOSABLE_ANNOTATION
import dev.dootah.compiler.model.BundleCapability
import dev.dootah.compiler.model.BundleExpression
import dev.dootah.compiler.model.BundleModifierOp
import dev.dootah.compiler.model.BundleProp
import dev.dootah.compiler.model.BundleUi
import dev.dootah.contract.AdapterId
import dev.dootah.contract.CapabilityId
import dev.dootah.contract.ModifierOps
import dev.dootah.contract.PropValue
import dev.dootah.contract.ResourceKey
import dev.dootah.contract.Shapes
import dev.dootah.contract.ThemeColors
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.name.FqName

/** What lowering a screen's components produced, beyond the tree itself. */
internal class ComponentRequirements {

    /** Every adapter the screen places, so the app knows what to generate. */
    val adapters = linkedSetOf<String>()

    /** Every action lifted out of the source, deduplicated by what it does. */
    val capabilities = linkedSetOf<BundleCapability>()

    /** Every resource named, so the app can map the names to its own numbers. */
    val resources = linkedSetOf<String>()

    /** Every screen parameter routed into a component untouched. */
    val handles = linkedSetOf<String>()
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
@OptIn(SymbolInternals::class)
internal class ComponentLowering(
    private val signature: List<ScreenParameter>,
    private val reject: (Int?, String, String) -> Unit,
    private val lowerExpression: (FirExpression) -> BundleExpression?,
) {

    val requirements = ComponentRequirements()

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
        call: FirFunctionCall,
        lowerContent: (FirAnonymousFunction) -> List<BundleUi>?,
    ): BundleUi.ComponentUi? {

        val callee = call.calleeReference.toResolvedCallableSymbol()
        val qualifiedName = callee?.callableId?.asSingleFqName()?.asString()
        val shortName = callee?.name?.asString() ?: "unknown"

        if (callee == null || qualifiedName == null) {
            reject(
                call.sourceOffset(),
                "a call Dootah could not resolve",
                "Keep this screen native.",
            )
            return null
        }

        // Named by the declaration's own parameters, never by the arguments this
        // call happened to supply: which arguments are supplied is exactly what
        // an update has to be free to change.
        val declaredParameters = (callee.fir as? FirFunction)
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
            )
            return null
        }

        val props = LinkedHashMap<String, BundleProp>()
        val children = LinkedHashMap<String, List<BundleUi>>()

        for ((expression, parameter) in mapping) {

            val name = parameter.name.asString()

            if (parameter.isComposableContent()) {

                val lambda = (expression as? FirAnonymousFunctionExpression)?.anonymousFunction

                if (lambda == null) {
                    reject(
                        call.sourceOffset(),
                        "`$shortName()`, whose `$name` is not written as a lambda",
                        "Pass the content as a lambda, or keep this screen native.",
                    )
                    return null
                }

                children[name] = lowerContent(lambda) ?: return null
                continue
            }

            props[name] = lowerProp(expression, parameter, shortName) ?: return null
        }

        requirements.adapters += adapterId

        return BundleUi.ComponentUi(
            adapterId = adapterId,
            props = props,
            children = children,
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
        expression: FirExpression,
        parameter: FirValueParameter,
        shortName: String,
    ): BundleProp? {

        constantProp(expression)?.let { return it }

        // An `if` whose branches are both expressible. This is where a colour
        // chosen by a remotely computed condition comes from, and lowering it to
        // a constant would throw away the part an update is for.
        (expression as? FirWhenExpression)?.let { conditional ->
            conditionalProp(conditional, parameter, shortName)?.let { return it }
        }

        modifierProp(expression)?.let { return it }

        callbackProp(expression, parameter)?.let { return it }

        // Anything the bundle can work out for itself, sent as the scalar the
        // parameter declares.
        val kind = parameter.returnTypeRef.coneTypeSafe<ConeKotlinType>().scalarKind()

        if (kind != null) {
            lowerExpression(expression)?.let { computed ->
                return BundleProp.Computed(kind, computed)
            }
        }

        reject(
            expression.sourceOffset(),
            "`$shortName()`'s `${parameter.name.asString()}`, which Dootah cannot carry",
            "A native component may be given a constant, a value this screen " +
                "computes, one of the screen's own parameters, a resource or " +
                "theme token, a modifier, or one of the screen's handlers. " +
                "Anything else has to stay in a native screen.",
        )

        return null
    }

    /** The forms whose value is fixed the moment the screen is lowered. */
    private fun constantProp(expression: FirExpression): BundleProp? {

        literalValue(expression)?.let { return BundleProp.Constant(it) }

        resourceProp(expression)?.let { return BundleProp.Constant(it) }

        themeColour(expression)?.let { return BundleProp.Constant(it) }

        shapeToken(expression)?.let { return BundleProp.Constant(it) }

        dpValue(expression)?.let { return BundleProp.Constant(it) }

        colourValue(expression)?.let { return BundleProp.Constant(it) }

        screenParameterProp(expression)?.let { return BundleProp.Constant(it) }

        return null
    }

    private fun literalValue(expression: FirExpression): PropValue? =
        when (val value = (expression as? FirLiteralExpression)?.value) {
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
    private fun resourceProp(expression: FirExpression): PropValue? {

        val call = expression as? FirFunctionCall ?: return null
        val callable = call.resolvedCallableName() ?: return null

        val key = call.arguments.firstOrNull()?.let { resourceKey(it) } ?: return null

        return when (callable) {
            SupportedCatalog.PAINTER_RESOURCE -> PropValue.PainterResourceValue(key)
            SupportedCatalog.STRING_RESOURCE -> PropValue.StringResourceValue(key)
            else -> null
        }?.also { requirements.resources += key }
    }

    /** `R.drawable.brush_24px` as `drawable:brush_24px`. */
    private fun resourceKey(expression: FirExpression): String? {

        val access = expression as? FirPropertyAccessExpression ?: return null
        val name = access.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null

        val qualifier = access.explicitReceiver as? FirResolvedQualifier ?: return null
        val relative = qualifier.relativeClassFqName ?: return null

        // Guarded so an unrelated `Something.drawable.x` cannot look like one.
        if (relative.parent().shortName().asString() != "R") return null

        return ResourceKey.of(relative.shortName().asString(), name)
    }

    /** `MaterialTheme.colorScheme.inversePrimary`, resolved by the app's theme. */
    private fun themeColour(expression: FirExpression): PropValue? {

        val access = expression as? FirPropertyAccessExpression ?: return null
        val token = access.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null

        val scheme = access.explicitReceiver as? FirPropertyAccessExpression ?: return null
        if (scheme.resolvedCallableName() != SupportedCatalog.COLOR_SCHEME_PROPERTY) return null

        if (!ThemeColors.isKnown(token)) return null

        return PropValue.ThemeColorValue(token)
    }

    private fun shapeToken(expression: FirExpression): PropValue? =
        when ((expression as? FirPropertyAccessExpression)?.resolvedCallableName()) {
            SupportedCatalog.CIRCLE_SHAPE -> PropValue.ShapeValue(Shapes.CIRCLE)
            SupportedCatalog.RECTANGLE_SHAPE -> PropValue.ShapeValue(Shapes.RECTANGLE)
            else -> null
        }

    /** `48.dp`, whichever numeric type it is written on. */
    private fun dpValue(expression: FirExpression): PropValue? {

        val access = expression as? FirPropertyAccessExpression ?: return null
        if (access.resolvedCallableName() != SupportedCatalog.DP_PROPERTY) return null

        // Any number, because the frontend chooses the box: an integer literal
        // arrives as a `Long` whatever it was written as, so listing the types
        // a developer can type silently refused every `48.dp` ever written.
        val amount = (access.explicitReceiver as? FirLiteralExpression)?.value as? Number
            ?: return null

        return PropValue.DpValue(amount.toDouble())
    }

    /** `Color(0xFF2196F3)` and `Color.White`. */
    private fun colourValue(expression: FirExpression): PropValue? {

        (expression as? FirFunctionCall)
            ?.takeIf { it.resolvedCallableName() == SupportedCatalog.COLOR_FUNCTION }
            ?.arguments?.firstOrNull()
            ?.let { argument -> (argument as? FirLiteralExpression)?.value as? Long }
            ?.let { return PropValue.ColorValue(it) }

        val access = expression as? FirPropertyAccessExpression ?: return null
        val name = access.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null
        val qualifier = access.explicitReceiver as? FirResolvedQualifier ?: return null

        if (qualifier.classId?.asSingleFqName() != SupportedCatalog.COLOR_COMPANION) return null

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
    private fun screenParameterProp(expression: FirExpression): PropValue? {

        val access = expression as? FirPropertyAccessExpression ?: return null
        val name = access.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null

        if (name == "value") {
            val owner = (access.explicitReceiver as? FirPropertyAccessExpression)
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

    /** The name, if this reads a parameter Dootah cannot serialise. */
    private fun FirPropertyAccessExpression.nativeParameterName(): String? {

        if (explicitReceiver != null) return null

        val name = calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null

        return name.takeIf { candidate ->
            signature.any { parameter ->
                parameter is ScreenParameter.NativeOnly && parameter.name == candidate
            }
        }
    }

    private fun conditionalProp(
        expression: FirWhenExpression,
        parameter: FirValueParameter,
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
    private fun modifierProp(expression: FirExpression): BundleProp? {

        val operations = mutableListOf<BundleModifierOp>()
        var current: FirExpression? = expression

        while (true) {

            // Two spellings of the same start. `Modifier.size(...)` resolves
            // its receiver to the type, while `Modifier.Companion.size(...)`
            // resolves it to the companion property.
            val qualifier = current as? FirResolvedQualifier
            if (qualifier?.classId?.asSingleFqName() == SupportedCatalog.MODIFIER_TYPE) {
                return BundleProp.Modifier(operations.reversed())
            }

            val access = current as? FirPropertyAccessExpression
            if (access != null && access.resolvedCallableName() == SupportedCatalog.MODIFIER_COMPANION) {
                return BundleProp.Modifier(operations.reversed())
            }

            val call = current as? FirFunctionCall ?: return null
            val name = modifierOperation(call) ?: return null
            val arguments = modifierArguments(call, name) ?: return null

            operations += BundleModifierOp(name, arguments)
            current = call.explicitReceiver ?: return null
        }
    }

    private fun modifierOperation(call: FirFunctionCall): String? =
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
        call: FirFunctionCall,
        operation: String,
    ): Map<String, BundleProp>? {

        val mapping = call.resolvedArgumentMapping ?: return null
        val arguments = LinkedHashMap<String, BundleProp>()

        for ((expression, parameter) in mapping) {
            val value = constantProp(expression)
                ?: (expression as? FirWhenExpression)?.let { conditional ->
                    conditionalProp(conditional, parameter, operation)
                }
                ?: return null
            arguments[parameter.name.asString()] = value
        }

        // `size(48.dp)` is one argument where the app takes two.
        if (operation == ModifierOps.SIZE && arguments.size == 1) {
            val only = arguments.values.first()
            return mapOf("width" to only, "height" to only)
        }

        return arguments
    }

    /**
     * A handler written in the source, lifted into the APK as a capability.
     *
     * The bundle gets the capability's name and nothing else: it can attach the
     * handler to any component it likes, and cannot write one, change what one
     * does, or reach anything the original lambda did not already touch.
     */
    private fun callbackProp(
        expression: FirExpression,
        parameter: FirValueParameter,
    ): BundleProp? {

        val type = parameter.returnTypeRef.coneTypeSafe<ConeKotlinType>() ?: return null
        val arity = type.unitFunctionArity() ?: return null

        // A screen callback passed straight through is the same capability as
        // one written inline that does nothing but call it.
        (expression as? FirPropertyAccessExpression)?.callbackParameterName()?.let { name ->
            return capability(listOf(CapabilityId.Invoke(null, name)), arity)
        }

        val lambda = (expression as? FirAnonymousFunctionExpression)?.anonymousFunction
            ?: return null

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

        if (statement is FirVariableAssignment) {

            val target = statement.lValue as? FirPropertyAccessExpression ?: return null
            val property = target.calleeReference.toResolvedCallableSymbol()?.name?.asString()
                ?: return null

            val receiver = (target.explicitReceiver as? FirPropertyAccessExpression)
                ?.calleeReference?.toResolvedCallableSymbol()?.name?.asString()
                ?: return null

            val value = capabilityArgument(statement.rValue, parameterNames) ?: return null

            return CapabilityId.Assign(receiver, property, value)
        }

        if (statement is FirFunctionCall) {

            val member = statement.calleeReference.toResolvedCallableSymbol()?.name?.asString()
                ?: return null

            val receiver = (statement.explicitReceiver as? FirPropertyAccessExpression)
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
        expression: FirExpression,
        parameterNames: List<String>,
    ): CapabilityId.Argument? {

        (expression as? FirLiteralExpression)?.value?.let { value ->
            return CapabilityId.Literal(
                when (value) {
                    is String -> "\"$value\""
                    else -> value.toString()
                }
            )
        }

        val access = expression as? FirPropertyAccessExpression ?: return null
        val name = access.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null

        val index = parameterNames.indexOf(name)
        if (index >= 0) return CapabilityId.Parameter(index)

        return CapabilityId.Read(name)
    }

    private fun FirPropertyAccessExpression.callbackParameterName(): String? {

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
private fun org.jetbrains.kotlin.fir.expressions.FirBlock.onlyExpression(): FirExpression? =
    statements.singleOrNull() as? FirExpression

/** Whether this parameter takes composable content rather than a value. */
private fun FirValueParameter.isComposableContent(): Boolean {

    val type = returnTypeRef.coneTypeSafe<ConeKotlinType>() ?: return false

    if (type.unitFunctionArity() == null) return false

    return type.customAnnotations.any { annotation ->
        annotation.annotationTypeRef.coneTypeSafe<ConeKotlinType>()
            ?.classId?.asSingleFqName() == COMPOSABLE_ANNOTATION
    }
}

/** How many arguments this takes, if it is a `(…) -> Unit`. */
private fun ConeKotlinType.unitFunctionArity(): Int? {

    val name = classId?.asSingleFqName()?.asString() ?: return null
    if (!name.startsWith("kotlin.Function")) return null

    val arity = name.removePrefix("kotlin.Function").toIntOrNull() ?: return null

    val returned = (typeArguments.lastOrNull() as? ConeKotlinType)?.classId?.asSingleFqName()

    return arity.takeIf { returned == FqName("kotlin.Unit") }
}

/** The contract kind a scalar of this type is sent as. */
private fun ConeKotlinType?.scalarKind(): String? =
    when (this?.classId?.asSingleFqName()?.asString()) {
        "kotlin.String" -> PropValue.Kind.STRING
        "kotlin.Int" -> PropValue.Kind.INT
        "kotlin.Boolean" -> PropValue.Kind.BOOL
        "kotlin.Long" -> PropValue.Kind.LONG
        "kotlin.Float" -> PropValue.Kind.FLOAT
        "kotlin.Double" -> PropValue.Kind.DOUBLE
        else -> null
    }
