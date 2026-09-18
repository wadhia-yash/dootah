package dev.dootah.compiler.fir

import dev.dootah.compiler.COMPOSABLE_ANNOTATION
import dev.dootah.contract.CallbackId
import dev.dootah.compiler.model.ArithmeticOperator
import dev.dootah.compiler.model.BundleAction
import dev.dootah.compiler.model.BundleCallback
import dev.dootah.compiler.model.BundleCallbackArgument
import dev.dootah.compiler.model.BundleCommandModel
import dev.dootah.compiler.model.BundleEntry
import dev.dootah.compiler.model.BundleExpression
import dev.dootah.compiler.model.BundleFunction
import dev.dootah.compiler.model.BundleModifier
import dev.dootah.compiler.model.BundleScreen
import dev.dootah.compiler.model.BundleStatement
import dev.dootah.compiler.model.BundleType
import dev.dootah.compiler.model.BundleUi
import dev.dootah.compiler.model.ComparisonOperator
import dev.dootah.compiler.model.LogicalOperator
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirComparisonExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.impl.FirUnitExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.types.ConstantValueKind
import dev.dootah.contract.BuilderScopes
import dev.dootah.contract.FrozenRegionId
import dev.dootah.contract.LayoutArrangement
import org.jetbrains.kotlin.name.FqName
import java.io.File
import java.util.IdentityHashMap

/** The outcome of lowering one screen. */
internal sealed interface LoweringResult {

    /**
     * [degraded] is what could not be described and stayed native instead.
     *
     * Not errors. A screen is worth publishing when enough of it is remote, and
     * the parts that are not are the ordinary case rather than a failure -- but
     * they are what a developer needs to see to understand why an edit did not
     * take effect, and what the coverage figures have to count.
     */
    data class Lowered(
        val screen: BundleScreen,
        val degraded: List<UnsupportedConstruct> = emptyList(),
    ) : LoweringResult

    /**
     * Describable, but publishing it would change nothing.
     *
     * Not a failure and not a success. The screen came out of lowering intact
     * and is simply not worth the bundle size, the download or the risk, so it
     * keeps its native implementation and says why.
     */
    data class NotWorthShipping(
        val screen: BundleScreen,
        val shape: RemoteShape,
    ) : LoweringResult

    /** Nothing is emitted for a rejected screen; the app keeps its native body. */
    data class Rejected(val reasons: List<UnsupportedConstruct>) : LoweringResult
}

/**
 * Turns a resolved ordinary Compose function body into a [BundleScreen].
 *
 * Every construct is either recognised explicitly, turned into a native slot, or
 * rejected. There is no best-effort branch, because the failure it would cause
 * -- a bundle that runs and renders the wrong thing -- is worse than a build that
 * stops and says what it cannot do.
 *
 * One instance handles one screen; rejections accumulate so a developer sees
 * everything wrong with a screen at once rather than one problem per build.
 */
internal class ScreenLowering(
    private val function: FirNamedFunction,
    private val filePath: String,
) {

    private val functionName = function.symbol.callableId.asSingleFqName().asString()
    private val reasons = mutableListOf<UnsupportedConstruct>()

    private val signature = function.screenParameters()
    private val modifierParameter = signature.modifierName()
    private val callbacks = signature.callbacks().associateBy { callback -> callback.name }

    /** The callbacks this screen's bundle actually invokes, as `CallbackId`s. */
    private val requiredCallbacks = linkedSetOf<String>()

    private val nativeOnlyParameters = signature
        .filterIsInstance<ScreenParameter.NativeOnly>()
        .associateBy { it.name }

    /** Immutable names a remote expression may read: parameters and `val`s. */
    private val values = LinkedHashMap<String, BundleType>()

    /** `var`s, which live in the screen's remote state rather than in a local. */
    private val states = LinkedHashMap<String, BundleType>()

    /**
     * Names declared inside the body.
     *
     * A native slot may close over anything the app already has -- parameters,
     * top-level declarations, theme -- but not over one of these, because their
     * values exist only on the remote side.
     */
    private val bodyDeclarations = mutableSetOf<String>()

    /** `when` subjects, which FIR stores in a synthetic variable. */
    private val subjects = mutableListOf<Pair<String, BundleExpression>>()

    /**
     * Turns the composables Dootah does not describe into component instances.
     *
     * Holds what the screen ended up needing from the app -- which adapters,
     * actions, values and resources -- which is what the app generates and what
     * a published bundle is checked against.
     */
    private val components = ComponentLowering(
        signature = signature,
        reject = { offset, found, remedy, code, detail ->
            reject(offset, found, code, detail, remedy)
        },
        lowerExpression = { expression -> lowerExpression(expression) },
        readsBody = { element -> element.declarationsReadFromBody().isNotEmpty() },
    )

    private val prelude = mutableListOf<BundleStatement>()
    private val actions = mutableListOf<BundleAction>()
    private val functions = LinkedHashMap<String, BundleFunction>()

    /**
     * Locals whose value exists only on the Android side.
     *
     * A `CoroutineScope`, a view model, a lazy list state: a screen declares
     * these constantly, and until now the first one refused the whole screen.
     * They are not a problem in themselves -- they are a problem only for the
     * parts of the screen that read them, and those parts stay native.
     */
    private val nativeOnlyLocals = mutableSetOf<String>()

    /** What could not be described and stayed native. Never fatal. */
    private val degraded = mutableListOf<UnsupportedConstruct>()

    /**
     * The file's own text, for naming a region by exactly what it says.
     *
     * Read from disk rather than from the compiler's source element, because the
     * pass that registers these regions in the app's own build has the file path
     * and nothing else in common with this one.
     */
    private val sourceText: String by lazy {
        runCatching { File(filePath).readText() }.getOrDefault("")
    }

    private val modifiers = ModifierLowering(
        rejector = { offset, found, remedy, code, detail ->
            reject(offset, found, code, detail, remedy)
        },
        modifierParameterName = modifierParameter,
        onAnchor = { anchor -> components.requirements.anchors += anchor },
    )

    private val layouts = LayoutLowering(
        rejector = { offset, found, remedy, code, detail ->
            reject(offset, found, code, detail, remedy)
        },
        onAnchor = { anchor -> components.requirements.anchors += anchor },
    )

    /**
     * Everything lowering has built so far, and how to go back to it.
     *
     * An attempt that fails has usually already declared a value, recorded an
     * adapter or registered an action before it hit the thing it could not do.
     * Rolling back only the complaints -- which is all this used to do -- left a
     * screen carrying values nothing reads and requirements nothing asks for.
     */
    private inner class Snapshot {
        val reasonCount = reasons.size
        val values = LinkedHashMap(this@ScreenLowering.values)
        val states = LinkedHashMap(this@ScreenLowering.states)
        val bodyDeclarations = this@ScreenLowering.bodyDeclarations.toList()
        val nativeOnlyLocals = this@ScreenLowering.nativeOnlyLocals.toList()
        val subjects = this@ScreenLowering.subjects.toList()
        val prelude = this@ScreenLowering.prelude.toList()
        val actions = this@ScreenLowering.actions.toList()
        val functions = LinkedHashMap(this@ScreenLowering.functions)
        val requirements = components.requirements.snapshot()

        fun restore() {
            while (reasons.size > reasonCount) reasons.removeAt(reasons.lastIndex)
            this@ScreenLowering.values.clear()
            this@ScreenLowering.values.putAll(values)
            this@ScreenLowering.states.clear()
            this@ScreenLowering.states.putAll(states)
            this@ScreenLowering.bodyDeclarations.clear()
            this@ScreenLowering.bodyDeclarations += bodyDeclarations
            this@ScreenLowering.nativeOnlyLocals.clear()
            this@ScreenLowering.nativeOnlyLocals += nativeOnlyLocals
            this@ScreenLowering.subjects.clear()
            this@ScreenLowering.subjects += subjects
            this@ScreenLowering.prelude.clear()
            this@ScreenLowering.prelude += prelude
            this@ScreenLowering.actions.clear()
            this@ScreenLowering.actions += actions
            this@ScreenLowering.functions.clear()
            this@ScreenLowering.functions.putAll(functions)
            components.requirements.restore(requirements)
        }
    }

    /** What one attempt produced, and what it complained about if it failed. */
    private class Outcome<T>(val value: T?, val causes: List<UnsupportedConstruct>) {
        val failed: Boolean get() = value == null
    }

    /**
     * Runs a lowering that is allowed to fail, leaving nothing behind if it does.
     *
     * Failure is "complained about something", not "returned null": most of the
     * lowering reports what it cannot do and carries on with what it can, and
     * for an attempt those two are the same thing.
     */
    private fun <T : Any> attempt(block: () -> T?): Outcome<T> {

        val snapshot = Snapshot()
        val value = block()

        if (value != null && reasons.size == snapshot.reasonCount) {
            return Outcome(value, emptyList())
        }

        val causes = reasons.subList(snapshot.reasonCount, reasons.size).toList()
        snapshot.restore()

        return Outcome(null, causes)
    }

    fun lower(screenId: String): LoweringResult {

        signature.filterIsInstance<ScreenParameter.Value>().forEach { parameter ->
            values[parameter.name] = parameter.type
        }

        val body = function.body
        if (body == null) {
            reject(
                null,
                "a function with no body",
                code = RejectionCode.NO_BODY,
                remedy = "Give the function a body.",
            )
            return LoweringResult.Rejected(reasons)
        }

        val ui = lowerBody(body)

        if (reasons.isNotEmpty() || ui == null) return LoweringResult.Rejected(reasons)

        val lowered = LoweringResult.Lowered(
            degraded = degraded.toList(),
            screen = BundleScreen(
                screenId = screenId,
                functionName = functionName,
                parameters = signature.valueParameters(),
                callbacks = callbacks.values.map { callback ->
                    BundleCallback(callback.name, callback.parameterTypes)
                },
                prelude = prelude.toList(),
                ui = ui,
                actions = actions.toList(),
                functions = functions.values.toList(),
                adapters = components.requirements.adapters.toList(),
                capabilities = components.requirements.capabilities.toList(),
                handles = components.requirements.handles.toList(),
                resources = components.requirements.resources.toList(),
                anchors = components.requirements.anchors.toList(),
                builders = components.requirements.builders.toList(),
                requiredCallbacks = requiredCallbacks.toList(),
            ),
        )

        val shape = lowered.screen.remoteShape()

        return if (shape.worthShipping) lowered
        else LoweringResult.NotWorthShipping(lowered.screen, shape)
    }

    /**
     * A screen body is a run of declarations followed by its content.
     *
     * The content is usually one layout, but it does not have to be: a screen
     * that is several components in a row is laid out by whatever the caller
     * wrapped the call in, and those are exactly the screens worth updating.
     * Several roots become a fragment, which draws them in place and adds no
     * layout of its own.
     */
    private fun lowerBody(body: FirBlock): BundleUi? {

        val roots = mutableListOf<BundleUi>()

        for (statement in body.statements) {

            when (val unwrapped = statement.unwrapReturn()) {

                null -> Unit

                is FirProperty -> lowerDeclaration(unwrapped, into = prelude)

                else -> roots += lowerUiStatementPartially(unwrapped) ?: return null
            }
        }

        if (roots.isEmpty()) {
            if (reasons.isEmpty()) {
                reject(
                    function.source?.startOffset,
                    "a screen body with no composable content",
                    code = RejectionCode.NO_COMPOSABLE_CONTENT,
                    remedy = "Add a Column { } containing the screen's content.",
                )
            }
            return null
        }

        return roots.singleOrNull() ?: BundleUi.FragmentUi(roots.toList())
    }

    // ---- declarations ---------------------------------------------------

    private fun lowerDeclaration(property: FirProperty, into: MutableList<BundleStatement>) {

        val name = property.name.asString()
        val type = bundleTypeOf(property.returnTypeRef.coneTypeSafe<ConeKotlinType>())

        if (type == null) {
            keepNative(
                property.source?.startOffset,
                "the local `$name`, which is not a number, String or Boolean",
                code = RejectionCode.UNSUPPORTED_LOCAL_TYPE,
                detail = property.returnTypeRef.coneTypeSafe<ConeKotlinType>()
                    ?.classId?.asSingleFqName()?.asString(),
                remedy = "The local stays on the Android side. Anything that reads it " +
                    "stays there too; the rest of the screen is unaffected.",
            )
            nativeOnly(name)
            return
        }

        val initializer = property.initializer ?: property.delegate?.rememberedInitialValue()

        if (initializer == null) {
            keepNative(
                property.source?.startOffset,
                "the local `$name` with no value Dootah could read",
                code = RejectionCode.UNREADABLE_LOCAL_INITIALIZER,
                remedy = "Give the local a literal or an expression over other " +
                    "bundled values, or leave it native.",
            )
            nativeOnly(name)
            return
        }

        val value = speculate { lowerExpression(initializer) }

        if (value == null) {
            keepNative(
                property.source?.startOffset,
                "the value of the local `$name`",
                code = RejectionCode.UNREADABLE_LOCAL_INITIALIZER,
                detail = (initializer as? FirFunctionCall)
                    ?.resolvedCallableName()?.asString()
                    ?.let { callee -> "call:$callee" },
                remedy = "The local stays on the Android side, and so does anything " +
                    "that reads it.",
            )
            nativeOnly(name)
            return
        }

        bodyDeclarations += name

        if (property.isVal && property.delegate == null) {
            values[name] = type
            into += BundleStatement.DeclareValue(name = name, type = type, value = value)
        } else {
            states[name] = type
            into += BundleStatement.DeclareState(name = name, type = type, initial = value)
        }
    }

    /**
     * Reads the initial value out of `by remember { mutableStateOf(x) }`.
     *
     * Supported because it is the shape Compose developers actually write for
     * screen state, and because it keeps the native fallback and the remote
     * implementation in agreement: a plain `var` in a composable resets on every
     * recomposition natively, while Dootah would keep it.
     */
    private fun FirExpression.rememberedInitialValue(): FirExpression? {

        val remember = this as? FirFunctionCall ?: return null
        if (remember.resolvedCallableName() != REMEMBER) return null

        val produced = remember.arguments.lastOrNull()?.lambdaBody()
            ?.statements
            ?.firstNotNullOfOrNull { it.unwrapReturn() as? FirFunctionCall }
            ?: return null

        if (produced.resolvedCallableName() != MUTABLE_STATE_OF) return null

        return produced.arguments.firstOrNull()
    }

    // ---- UI -------------------------------------------------------------

    /**
     * Lowers one position in the tree as much of it as can be described.
     *
     * The ladder, smallest step first. Describe the statement; failing that keep
     * the call it is native but still under the bundle's control, so an update
     * can move it, repeat it, drop it or change what it is given; failing that
     * keep it exactly as written, which leaves the bundle only the choice of
     * where it goes and whether it appears at all.
     *
     * Only when none of those work does the failure travel outwards, and the
     * region that degrades grows by one level. That is what stops a single
     * unsupported corner from costing a whole screen -- and why the boundary
     * ends up at the smallest place that still preserves what the code does.
     */
    private fun lowerUiStatementPartially(statement: FirElement): List<BundleUi>? {

        val direct = attempt { lowerUiStatement(statement) }
        if (!direct.failed) return direct.value

        val call = statement.unwrapReturn() as? FirFunctionCall

        if (call != null) {

            attempt { lowerNativeComponent(call) }.value?.let { component ->
                degradeTo("a component the bundle places", direct.causes)
                return listOf(component)
            }

            attempt { freeze(call) }.value?.let { region ->
                degradeTo("a region kept exactly as written", direct.causes)
                return listOf(region)
            }
        }

        reasons += direct.causes

        return null
    }

    /** Records why a region stopped short of being described. */
    private fun degradeTo(boundary: String, causes: List<UnsupportedConstruct>) {
        causes.forEach { cause ->
            degraded += cause.copy(
                remedy = "Dootah kept this as $boundary. " + cause.remedy,
            )
        }
    }

    /**
     * Keeps a region as the native code it already is.
     *
     * The last step before giving up on a region, and the one that makes the
     * rest of a screen updatable regardless of what is inside this part of it.
     * Nothing about the region is described: the app runs the call it already
     * compiled, with the arguments it was already written with.
     *
     * The two conditions are the ones that decide whether the app can lift the
     * code out at all. It has to be a composable -- there is nothing to place
     * otherwise -- and it must not read a name the body declares, because the
     * region is lifted out of the body and into a value prepared before the body
     * runs, where that name does not exist.
     */
    private fun freeze(call: FirFunctionCall): BundleUi? {

        val callable = call.resolvedCallableName()
        val shortName = callable?.shortName()?.asString() ?: "unknown"

        if (callable == null || !call.isComposableCall()) {
            reject(
                call.sourceOffset(),
                "the call `$shortName()` inside a layout",
                code = RejectionCode.UNSUPPORTED_CALL_IN_LAYOUT,
                detail = callable?.asString(),
                remedy = "A layout may contain components. Move other work out of " +
                    "the screen body, or keep this screen native.",
            )
            return null
        }

        if (call.readsAnOuterReceiver()) {
            reject(
                call.sourceOffset(),
                "`$shortName()`, which reads the scope of the layout around it",
                code = RejectionCode.COMPONENT_READS_SCOPE,
                detail = callable.asString(),
                remedy = "A native region is lifted out into a standalone lambda, so " +
                    "it cannot read a `ColumnScope` or `RowScope` from around it -- " +
                    "`weight` is the usual reason. The layout holding it can be kept " +
                    "whole instead.",
            )
            return null
        }

        val readFromBody = call.declarationsReadFromBody()

        if (readFromBody.isNotEmpty()) {
            reject(
                call.sourceOffset(),
                "`$shortName()`, which reads `${readFromBody.first()}` from this body",
                code = RejectionCode.COMPONENT_READS_BODY,
                detail = callable.asString(),
                remedy = "A native region is prepared before the screen's own body " +
                    "runs, so it cannot read something the body declares. Move the " +
                    "declaration out of the screen, or keep the screen native.",
            )
            return null
        }

        val text = call.sourceText()

        if (text == null) {
            reject(
                call.sourceOffset(),
                "`$shortName()`, whose source Dootah could not read",
                code = RejectionCode.UNREADABLE_REGION_SOURCE,
                detail = callable.asString(),
                remedy = "Keep this screen native.",
            )
            return null
        }

        val id = FrozenRegionId.of(callable.asString(), text)
        components.requirements.adapters += id

        return BundleUi.ComponentUi(adapterId = id)
    }

    /** The source this element was written as, for naming it by what it says. */
    private fun FirElement.sourceText(): String? {

        val start = source?.startOffset ?: return null
        val end = source?.endOffset ?: return null

        if (start < 0 || end > sourceText.length || end <= start) return null

        return sourceText.substring(start, end)
    }

    /** A statement in a UI position contributes zero, one or several children. */
    private fun lowerUiStatement(statement: FirElement): List<BundleUi> =
        when (val unwrapped = statement.unwrapReturn()) {

            null -> emptyList()

            is FirFunctionCall -> listOfNotNull(lowerUi(unwrapped))

            is FirWhenExpression -> listOfNotNull(lowerConditionalUi(unwrapped))

            else -> {
                reject(
                    unwrapped.sourceOffset(),
                    "the statement ${unwrapped::class.simpleName} in a layout",
                    code = RejectionCode.UNSUPPORTED_STATEMENT_IN_LAYOUT,
                    detail = unwrapped::class.simpleName,
                    remedy = "A layout may contain components, and `if` / `when` " +
                        "choosing between them.",
                )
                emptyList()
            }
        }

    private fun lowerUi(call: FirFunctionCall): BundleUi? =
        when (call.resolvedCallableName()) {

            SupportedCatalog.COLUMN -> lowerContainer(call) { args, kids ->
                BundleUi.ColumnUi(
                    modifiers = args.modifiers,
                    children = kids,
                    horizontalAlignment = args.horizontalAlignment,
                    verticalArrangement = args.verticalArrangement,
                )
            }

            SupportedCatalog.ROW -> lowerContainer(call) { args, kids ->
                BundleUi.RowUi(
                    modifiers = args.modifiers,
                    children = kids,
                    verticalAlignment = args.verticalAlignment,
                    horizontalArrangement = args.horizontalArrangement,
                )
            }

            SupportedCatalog.BOX -> lowerContainer(call) { args, kids ->
                BundleUi.BoxUi(
                    modifiers = args.modifiers,
                    children = kids,
                    contentAlignment = args.contentAlignment,
                )
            }

            // A component Dootah understands, unless it is styled or wired in a
            // way it does not -- in which case the whole component stays native
            // rather than being drawn without its styling or its behaviour.
            SupportedCatalog.TEXT -> lowerComponent(call) { lowerText(call) }

            SupportedCatalog.BUTTON -> lowerComponent(call) { lowerButton(call) }

            else -> lowerNativeComponent(call)
        }

    /**
     * Lowers a layout and its children.
     *
     * A layout is never turned into a native slot. Its children are the remote
     * part of the screen, and swallowing them into a native component would
     * quietly make the whole subtree un-updatable.
     */
    private fun lowerContainer(
        call: FirFunctionCall,
        build: (ContainerArguments, List<BundleUi>) -> BundleUi,
    ): BundleUi? {

        val name = call.resolvedCallableName()?.shortName()?.asString() ?: "layout"
        val mapping = call.resolvedArgumentMapping

        if (mapping == null) {
            reject(
                call.sourceOffset(),
                "a $name call Dootah could not read",
                code = RejectionCode.UNREADABLE_LAYOUT_CALL,
                detail = name,
                remedy = "Simplify the call.",
            )
            return null
        }

        val arguments = ContainerArguments()
        var content: FirBlock? = null
        var rejected = false

        // Dispatched by parameter name alone, because Compose's own signatures
        // already decide which names a layout has: only `Row` declares
        // `verticalAlignment`, and the callee was matched by resolved name
        // before we got here.
        for ((expression, parameter) in mapping) {
            when (parameter.name.asString()) {

                "modifier" -> arguments.modifiers = modifiers.lower(expression)
                    ?: run { rejected = true; emptyList() }

                "content" -> content = expression.lambdaBody()

                "horizontalAlignment" ->
                    arguments.horizontalAlignment = layouts.horizontalAlignment(expression, name)
                        ?: run { rejected = true; null }

                "verticalAlignment" ->
                    arguments.verticalAlignment = layouts.verticalAlignment(expression, name)
                        ?: run { rejected = true; null }

                "contentAlignment" ->
                    arguments.contentAlignment = layouts.boxAlignment(expression, name)
                        ?: run { rejected = true; null }

                "verticalArrangement" ->
                    arguments.verticalArrangement = layouts.verticalArrangement(expression, name)
                        ?: run { rejected = true; null }

                "horizontalArrangement" ->
                    arguments.horizontalArrangement = layouts.horizontalArrangement(expression, name)
                        ?: run { rejected = true; null }

                else -> {
                    reject(
                        expression.source?.startOffset,
                        "the $name argument `${parameter.name.asString()}`",
                        code = RejectionCode.UNSUPPORTED_LAYOUT_ARGUMENT,
                        detail = "$name.${parameter.name.asString()}",
                        remedy = "Dootah bundles $name(modifier, alignment, " +
                            "arrangement) { }.",
                    )
                    rejected = true
                }
            }
        }

        if (rejected) return null

        if (content == null) {
            reject(
                call.sourceOffset(),
                "a $name without a content lambda",
                code = RejectionCode.LAYOUT_WITHOUT_CONTENT,
                detail = name,
                remedy = "Write $name { } with its content inside the braces.",
            )
            return null
        }

        val children = content.statements.flatMap { statement ->
            lowerUiStatementPartially(statement) ?: return null
        }

        return build(arguments, children)
    }

    /**
     * What a layout call said, gathered before any of it is built.
     *
     * One holder for all three layouts rather than one per layout: the loop that
     * fills it reads arguments by name in whatever order they were written, and
     * three holders would mean three nearly identical loops.
     */
    private class ContainerArguments {
        var modifiers: List<BundleModifier> = emptyList()
        var horizontalAlignment: String? = null
        var verticalAlignment: String? = null
        var contentAlignment: String? = null
        var verticalArrangement: LayoutArrangement? = null
        var horizontalArrangement: LayoutArrangement? = null
    }

    private fun lowerConditionalUi(expression: FirWhenExpression): BundleUi? {

        val branches = lowerBranches(expression) { branch -> lowerUiStatement(branch) }
            ?: return null

        return branches.collapse { condition, body, otherwise ->
            listOf(BundleUi.ConditionalUi(condition, body, otherwise))
        }.singleOrNull()
    }

    private fun lowerText(call: FirFunctionCall): BundleUi? {

        val mapping = call.resolvedArgumentMapping ?: return null

        var text: BundleExpression? = null
        var modifierList = emptyList<BundleModifier>()

        for ((expression, parameter) in mapping) {
            when (parameter.name.asString()) {
                "text" -> text = lowerExpression(expression) ?: return null
                "modifier" -> modifierList = modifiers.lower(expression) ?: return null
                else -> return null
            }
        }

        return BundleUi.TextUi(text ?: return null, modifierList)
    }

    /**
     * Lowers `Button(onClick = { ... }) { Text("...") }`.
     *
     * The click handler becomes an action the bundle performs when the app sends
     * the tap back, so a bundled button does real work: it can change the
     * screen's own state, and it can ask the app to invoke one of the callbacks
     * the screen declares.
     */
    private fun lowerButton(call: FirFunctionCall): BundleUi? {

        val mapping = call.resolvedArgumentMapping ?: return null

        var onClick: FirExpression? = null
        var content: FirBlock? = null
        var modifierList = emptyList<BundleModifier>()

        for ((expression, parameter) in mapping) {
            when (parameter.name.asString()) {
                "onClick" -> onClick = expression
                "content" -> content = expression.lambdaBody()
                "modifier" -> modifierList = modifiers.lower(expression) ?: return null
                else -> return null
            }
        }

        val label = content
            ?.statements
            ?.mapNotNull { it.unwrapReturn() }
            ?.singleOrNull()
            ?.let { it as? FirFunctionCall }
            ?.takeIf { it.resolvedCallableName() == SupportedCatalog.TEXT }
            ?.let { text -> speculate { lowerText(text) } as? BundleUi.TextUi }
            ?.text
            ?: return null

        val body = onClick?.let { lowerClickHandler(it) } ?: return null

        val action = registerAction(label, body)

        return BundleUi.ButtonUi(label = label, action = action, modifiers = modifierList)
    }

    /**
     * Reads what a tap should do.
     *
     * `onClick = onSave` -- passing a callback straight through -- is handled
     * separately from `onClick = { ... }`, because it is how real screens forward
     * a callback and it carries no body to lower.
     */
    private fun lowerClickHandler(expression: FirExpression): List<BundleStatement>? {

        // `onClick` is `() -> Unit`, so a callback forwarded straight into it is
        // one too; a callback taking values cannot reach here without arguments
        // to send, and typing it as anything else would be Kotlin that does not
        // compile in the app either.
        (expression as? FirPropertyAccessExpression)?.resolvedName()?.let { name ->
            callbacks[name]?.takeIf { it.parameterTypes.isEmpty() }?.let {
                return listOf(BundleStatement.Perform(invokeCallback(name, emptyList())))
            }
        }

        val body = expression.lambdaBody() ?: return null

        return lowerStatements(body.statements)
    }

    /**
     * Names a tap after its label, or after its position when the label is
     * computed.
     *
     * Names have to be stable across builds of the same source and unique within
     * a screen, because the app sends one back and the bundle matches on it.
     */
    private fun registerAction(
        label: BundleExpression,
        body: List<BundleStatement>,
    ): String {

        val base = when (label) {
            is BundleExpression.StringConstant ->
                label.value.lowercase().replace(NON_ACTION_CHARACTERS, "-").trim('-')
            else -> "action"
        }.ifEmpty { "action" }

        val taken = actions.map { it.name }.toSet()
        val name = if (base !in taken) base else generateSequence(2) { it + 1 }
            .map { "$base-$it" }
            .first { it !in taken }

        actions += BundleAction(name = name, body = body)

        return name
    }

    /**
     * Turns a call Dootah cannot describe into a hole filled by the APK.
     *
     * This is what lets a real screen be bundled without Dootah reimplementing
     * Compose: an icon, a themed text, a card, an app's own component all stay
     * exactly as they were written and keep running natively, while the
     * structure and logic around them become remote.
     *
     * The condition is that the call reads nothing declared inside the body. A
     * slot runs on the Android side, where a remote `val` simply does not exist,
     * so closing over one is refused rather than silently producing a slot that
     * cannot be built.
     */
    private fun lowerNativeComponent(call: FirFunctionCall): BundleUi? {

        val callable = call.resolvedCallableName()
        val shortName = callable?.shortName()?.asString() ?: "unknown"

        if (!call.isComposableCall()) {
            reject(
                call.sourceOffset(),
                "the call `$shortName()` inside a layout",
                code = RejectionCode.UNSUPPORTED_CALL_IN_LAYOUT,
                detail = call.resolvedCallableName()?.asString(),
                remedy = "A layout may contain components. Move other work out of " +
                    "the screen body, or keep this screen native.",
            )
            return null
        }

        if (call.readsAnOuterReceiver()) {
            reject(
                call.sourceOffset(),
                "`$shortName()`, which reads the scope of the layout around it",
                code = RejectionCode.COMPONENT_READS_SCOPE,
                detail = call.resolvedCallableName()?.asString(),
                remedy = "A native component is placed from a standalone adapter, " +
                    "so it cannot read a `ColumnScope` or `RowScope` from around it -- " +
                    "`weight` is the usual reason. Give it a size instead, or " +
                    "keep this screen native.",
            )
            return null
        }

        return components.lower(
            call,
            lowerContent = { content -> lowerContent(content) },
            lowerEntries = { builder -> lowerBuilderEntries(builder) },
        )
    }

    /**
     * Lowers the entries a native container's builder declares.
     *
     * The builder's body is a list of statements, each declaring entries against
     * a scope. An `item { ... }` is described, so what it holds becomes remote
     * and a bundle may change it; anything else is kept as the app's own code
     * and performed against the real scope. That second case is the degradation
     * ladder one level in: the price of an entry the bundle cannot describe is
     * that entry, not the list, and not the screen.
     *
     * Nothing here composes, measures or orders anything. It decides which
     * declarations exist and in what sequence, and Compose does the rest.
     */
    private fun lowerBuilderEntries(builder: FirAnonymousFunction): List<BundleEntry>? {

        val mark = reasons.size

        val entries = builder.body?.statements.orEmpty().flatMap { statement ->
            lowerBuilderStatement(statement) ?: return null
        }

        return if (reasons.size > mark) null else entries
    }

    /** One statement of a builder: a described entry, or the app's own region. */
    private fun lowerBuilderStatement(statement: FirElement): List<BundleEntry>? {

        val call = statement.unwrapReturn() as? FirFunctionCall ?: return regionEntry(statement)

        val callable = call.resolvedCallableName()?.asString()

        if (callable != null && BuilderScopes.isItemCall(callable)) {
            attempt { describedItem(call) }.value?.let { item -> return listOf(item) }
        }

        return regionEntry(statement)
    }

    /** `item { ... }`, with its content lowered as ordinary bundle UI. */
    private fun describedItem(call: FirFunctionCall): BundleEntry? {

        val content = call.arguments
            .filterIsInstance<FirAnonymousFunctionExpression>()
            .singleOrNull()
            ?.anonymousFunction
            ?: return null

        // An `item` given a key is given something to compare across
        // recompositions, which is Compose's business and not describable here.
        // Refusing it keeps the region rather than silently dropping the key.
        if (call.arguments.size > 1) return null

        return BundleEntry.Item(children = lowerContent(content) ?: return null)
    }

    /**
     * Entries the app declares for itself, kept exactly as written.
     *
     * Named the same way any other frozen region is -- by what it says -- so an
     * edit to the app's own builder renames it and the bundle that named the old
     * one is refused rather than drawing something that no longer exists.
     */
    private fun regionEntry(statement: FirElement): List<BundleEntry>? {

        val call = statement.unwrapReturn() as? FirFunctionCall

        val callable = call?.resolvedCallableName()

        if (call == null || callable == null) {
            reject(
                statement.sourceOffset(),
                "something in a list that is not a call",
                code = RejectionCode.UNSUPPORTED_CALL_IN_LAYOUT,
                detail = null,
                remedy = "A list may declare entries. Move other work out of the " +
                    "list, or keep this screen native.",
            )
            return null
        }

        // The same two conditions every frozen region has to meet: it is lifted
        // out of the body, so it cannot read what the body declares, and it is
        // named by its text, so the text has to be readable.
        val readFromBody = call.declarationsReadFromBody()

        if (readFromBody.isNotEmpty()) {
            reject(
                call.sourceOffset(),
                "`${callable.shortName()}()`, which reads `${readFromBody.first()}` from this body",
                code = RejectionCode.COMPONENT_READS_BODY,
                detail = callable.asString(),
                remedy = "A list's own entries are prepared before the screen's " +
                    "body runs, so they cannot read something the body declares. " +
                    "Move the declaration out of the screen, or keep it native.",
            )
            return null
        }

        val text = call.sourceText()

        if (text == null) {
            reject(
                call.sourceOffset(),
                "`${callable.shortName()}()`, whose source Dootah could not read",
                code = RejectionCode.UNREADABLE_REGION_SOURCE,
                detail = callable.asString(),
                remedy = "Keep this screen native.",
            )
            return null
        }

        val id = FrozenRegionId.of(callable.asString(), text)
        components.requirements.builders += id

        return listOf(BundleEntry.Region(adapterId = id))
    }

    /**
     * Lowers the content a native component was given.
     *
     * Through the ordinary UI lowering, not through component lowering: the
     * children of a native component need not themselves be native. An icon
     * button's content is usually an `Icon`, which becomes a component in turn,
     * but it may equally be a `Text` or a whole layout that Dootah describes and
     * can therefore change over the air.
     */
    private fun lowerContent(content: FirAnonymousFunction): List<BundleUi>? {

        val mark = reasons.size

        // While lowering inside this lambda, the names it introduces are names
        // the body declares. Anything lifted out of here -- a region kept as
        // written, a component placed from an adapter -- runs where they do not
        // exist, and the app's own pass refuses to register such a thing for
        // exactly that reason. Leaving them out let the bundle name a region
        // the app had not got: `PostList(hasPostsUiState.postsFeed, …)` inside
        // a content lambda, caught by the publish check rather than by a device,
        // but only because the check exists.
        val outer = bodyDeclarations.toSet()
        bodyDeclarations += content.valueParameters.map { it.name.asString() }

        val children = try {
            content.body?.statements.orEmpty()
                .flatMap { statement -> lowerUiStatementPartially(statement) ?: return null }
        } finally {
            bodyDeclarations.clear()
            bodyDeclarations += outer
        }

        return if (reasons.size > mark) null else children
    }

    /**
     * Whether [this] reads a `this` belonging to something outside it.
     *
     * The app registers a native component as its own lambda, lifted out of the
     * layout it was written in, so a component declared on `ColumnScope` -- or
     * one whose modifier calls `weight` -- has no receiver left to read. The
     * app's build refuses to register that case because it cannot build it, so
     * this pass has to refuse to name it, or the bundle would ask for a
     * component the app never registered.
     *
     * A `this` introduced *inside* the call is fine: it is lifted along with it.
     * Which is the whole of the question, and the reason this is about where a
     * scope came from rather than about whether one was used. `Row { Text(...) }`
     * kept native reads a `RowScope` throughout, all of it belonging to the
     * `Row` being lifted, and builds; `Icon(Modifier.weight(1f))` kept native
     * reads a `ColumnScope` belonging to the `Column` left behind, and does not.
     * Asking only whether a `this` appeared refused both -- and with them every
     * screen whose native part held a `LazyColumn`, a `Scaffold` or a `Card`,
     * which is most screens.
     *
     * The app's pass draws the line by identity: it subtracts what the region
     * declares, receiver parameters included, from what it may not read. This
     * asks the same question of the same thing, so the two agree by
     * construction rather than by both being cautious in the same places.
     */
    private fun FirFunctionCall.readsAnOuterReceiver(): Boolean {

        val introducedHere = mutableSetOf<FirBasedSymbol<*>>()

        accept(
            object : org.jetbrains.kotlin.fir.visitors.FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element is FirAnonymousFunction) introducedHere += element.symbol
                    element.acceptChildren(this)
                }
            }
        )

        var readsOuter = false

        accept(
            object : org.jetbrains.kotlin.fir.visitors.FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element is FirThisReceiverExpression &&
                        !element.introducedIn(introducedHere)
                    ) {
                        readsOuter = true
                    }
                    element.acceptChildren(this)
                }
            }
        )

        return readsOuter
    }

    /**
     * Whether the scope this `this` refers to is one of [introduced].
     *
     * A lambda's receiver is read through the receiver parameter the lambda
     * declares, not through the lambda itself, so the symbol a `this` is bound
     * to is one step away from the symbol collected above -- and asking it which
     * declaration it belongs to closes that step exactly.
     *
     * An unresolved `this`, or one belonging to an enclosing class rather than
     * to a lambda, is outside by default: there is nothing here that says it
     * travels with the region, so it is refused rather than assumed.
     */
    private fun FirThisReceiverExpression.introducedIn(
        introduced: Set<FirBasedSymbol<*>>,
    ): Boolean {

        val bound = calleeReference.boundSymbol ?: return false

        if (bound in introduced) return true

        return bound is FirReceiverParameterSymbol &&
            bound.containingDeclarationSymbol in introduced
    }

    /** Names declared in this body that [this] reads, which a slot may not. */
    private fun FirElement.declarationsReadFromBody(): List<String> {

        val found = linkedSetOf<String>()

        accept(
            object : org.jetbrains.kotlin.fir.visitors.FirVisitorVoid() {

                override fun visitElement(element: FirElement) {
                    if (element is FirPropertyAccessExpression) {
                        element.resolvedName()
                            ?.takeIf { it in bodyDeclarations }
                            ?.let { found += it }
                    }
                    element.acceptChildren(this)
                }
            }
        )

        return found.toList()
    }

    // ---- statements -----------------------------------------------------

    private fun lowerStatements(statements: List<FirElement>): List<BundleStatement>? {

        val lowered = mutableListOf<BundleStatement>()

        for (statement in statements) {

            when (val unwrapped = statement.unwrapReturn()) {

                null -> Unit

                is FirProperty -> lowerDeclaration(unwrapped, into = lowered)

                is FirVariableAssignment -> lowered += lowerAssignment(unwrapped) ?: return null

                is FirWhenExpression -> lowered += lowerConditionalStatement(unwrapped)
                    ?: return null

                is FirFunctionCall -> lowered += lowerCallStatement(unwrapped) ?: return null

                else -> {
                    reject(
                        unwrapped.sourceOffset(),
                        "the statement ${unwrapped::class.simpleName} in a click handler",
                        code = RejectionCode.UNSUPPORTED_STATEMENT_IN_HANDLER,
                        detail = unwrapped::class.simpleName,
                        remedy = "A bundled click handler may assign to the screen's own " +
                            "`var`s, call the screen's callbacks, and branch with " +
                            "`if` / `when`.",
                    )
                    return null
                }
            }
        }

        return lowered
    }

    private fun lowerAssignment(assignment: FirVariableAssignment): BundleStatement? {

        val target = (assignment.lValue as? FirPropertyAccessExpression)?.resolvedName()
        val type = target?.let { states[it] }

        if (target == null || type == null) {
            reject(
                assignment.sourceOffset(),
                "an assignment to `${target ?: "something Dootah could not read"}`",
                code = RejectionCode.UNSUPPORTED_ASSIGNMENT_TARGET,
                remedy = "A bundled click handler may assign to a `var` declared in the " +
                    "same screen.",
            )
            return null
        }

        val value = lowerExpression(assignment.rValue) ?: return null

        return BundleStatement.Assign(name = target, type = type, value = value)
    }

    private fun lowerCallStatement(call: FirFunctionCall): BundleStatement? {

        val callback = call.invokedCallbackName()?.let { name -> callbacks.getValue(name) }

        if (callback != null) {
            val arguments = lowerCallbackArguments(call, callback) ?: return null
            return BundleStatement.Perform(invokeCallback(callback.name, arguments))
        }

        reject(
            call.sourceOffset(),
            "the call `${call.resolvedCallableName()?.shortName()?.asString() ?: "unknown"}()` " +
                "in a click handler",
            code = RejectionCode.UNSUPPORTED_CALL_IN_HANDLER,
            detail = call.resolvedCallableName()?.asString(),
            remedy = "Remote code reaches the app only through the screen's own callback " +
                "parameters. Add a parameter whose type is a function of the values " +
                "Dootah can carry -- `() -> Unit`, `(String) -> Unit` -- and call that.",
        )

        return null
    }

    /**
     * The values a call hands one of the screen's callbacks.
     *
     * Each one is lowered as an ordinary bundle expression, so the bundle can
     * only send something it was already able to work out. The count is checked
     * here rather than trusted from the call: a default or a trailing lambda
     * would leave the bundle sending fewer values than the app reads back.
     */
    private fun lowerCallbackArguments(
        call: FirFunctionCall,
        callback: ScreenParameter.Callback,
    ): List<BundleCallbackArgument>? {

        val supplied = call.arguments

        if (supplied.size != callback.parameterTypes.size) {
            reject(
                call.sourceOffset(),
                "the call `${callback.name}()` with ${supplied.size} value(s) in a " +
                    "click handler",
                code = RejectionCode.UNSUPPORTED_CALL_IN_HANDLER,
                detail = callback.name,
                remedy = "`${callback.name}` takes ${callback.parameterTypes.size} " +
                    "value(s). Call it with exactly that many.",
            )
            return null
        }

        return supplied.zip(callback.parameterTypes) { argument, type ->
            BundleCallbackArgument(type, lowerExpression(argument) ?: return null)
        }
    }

    /** Records a callback as required, and builds the command that invokes it. */
    private fun invokeCallback(
        name: String,
        arguments: List<BundleCallbackArgument>,
    ): BundleCommandModel.InvokeCallback {

        val callback = callbacks.getValue(name)

        requiredCallbacks += CallbackId.of(
            name = name,
            parameterTypes = callback.parameterTypes.map { type -> type.kotlinName },
        )

        return BundleCommandModel.InvokeCallback(name, arguments)
    }

    /** The screen callback this call invokes, if that is what it is. */
    private fun FirFunctionCall.invokedCallbackName(): String? {

        val receiverName = (explicitReceiver as? FirPropertyAccessExpression)?.resolvedName()
        if (receiverName != null && receiverName in callbacks.keys) return receiverName

        val directName = (calleeReference.toResolvedCallableSymbol()?.callableId
            ?.callableName?.asString())

        return directName?.takeIf { it in callbacks.keys }
    }

    private fun lowerConditionalStatement(expression: FirWhenExpression): List<BundleStatement>? {

        val branches = lowerBranches(expression) { branch ->
            lowerStatements(listOf(branch)) ?: emptyList()
        } ?: return null

        return branches.collapse { condition, body, otherwise ->
            listOf(BundleStatement.Conditional(condition, body, otherwise))
        }
    }

    /**
     * Lowers a `when`'s branches, innermost-last, with its subject bound.
     *
     * `if` is a `when` in FIR, and `when (x)` puts `x` in a synthetic variable
     * the branch conditions read. Binding that variable here is what lets one
     * expression lowering serve both forms.
     */
    private fun <T> lowerBranches(
        expression: FirWhenExpression,
        lowerBody: (FirElement) -> List<T>,
    ): List<Pair<BundleExpression, List<T>>>? {

        val subject = expression.subjectVariable

        if (subject != null) {
            val initializer = subject.initializer ?: return null
            val lowered = lowerExpression(initializer) ?: return null
            subjects += subject.name.asString() to lowered
        }

        try {
            val branches = mutableListOf<Pair<BundleExpression, List<T>>>()

            for (branch in expression.branches) {

                val condition = if (branch.condition.isElse()) {
                    BundleExpression.BooleanConstant(true)
                } else {
                    lowerExpression(branch.condition) ?: return null
                }

                branches += condition to branch.result.statements.flatMap { lowerBody(it) }
            }

            if (branches.none { (condition, _) ->
                    condition == BundleExpression.BooleanConstant(true)
                }
            ) {
                // A `when` used for UI or for statements with no `else` produces
                // nothing on the paths it does not cover, which is what an
                // uncovered `if` does too. Modelled explicitly so the generated
                // code has a branch to emit rather than falling off the end.
                branches += BundleExpression.BooleanConstant(true) to emptyList()
            }

            return branches.asReversed()
        } finally {
            if (subject != null) subjects.removeAt(subjects.lastIndex)
        }
    }

    /**
     * Folds branches, innermost first, into one nested conditional.
     *
     * An always-true branch -- the `else` -- contributes its body directly
     * rather than as a conditional wrapping nothing. Wrapping it would produce a
     * branch with an empty alternative, which is both redundant and, at the root
     * of a screen, a path that produces no layout at all.
     */
    private fun <T> List<Pair<BundleExpression, List<T>>>.collapse(
        wrap: (BundleExpression, List<T>, List<T>) -> List<T>,
    ): List<T> {

        var otherwise = emptyList<T>()

        for ((condition, body) in this) {
            otherwise = if (condition == BundleExpression.BooleanConstant(true)) body
            else wrap(condition, body, otherwise)
        }

        return otherwise
    }

    // ---- expressions ----------------------------------------------------

    private fun lowerExpression(expression: FirExpression): BundleExpression? =
        when (expression) {

            is FirLiteralExpression -> lowerLiteral(expression)

            is FirStringConcatenationCall -> lowerInterpolation(expression)

            is FirEqualityOperatorCall -> lowerEquality(expression)

            is FirComparisonExpression -> lowerComparison(expression)

            is FirBooleanOperatorExpression -> lowerBooleanOperator(expression)

            is FirWhenExpression -> lowerConditionalExpression(expression)

            is FirPropertyAccessExpression -> lowerReference(expression)

            is FirFunctionCall -> lowerCallExpression(expression)

            else -> {
                reject(
                    expression.sourceOffset(),
                    "the expression ${expression::class.simpleName}",
                    code = RejectionCode.UNSUPPORTED_EXPRESSION,
                    detail = expression::class.simpleName,
                    remedy = "Dootah bundles literals, the screen's own values, arithmetic, " +
                        "comparisons, Boolean logic, string templates and `if` / `when`.",
                )
                null
            }
        }

    /**
     * Reads a literal as the type it actually is.
     *
     * By its kind, not by the class of the boxed value: FIR stores every integer
     * literal as a `Long` whatever its type, so `0` and `0L` are indistinguishable
     * from the value alone -- and reading them that way turns every `Int` in a
     * screen into a `Long`, which the generated bundle then fails to type-check
     * against the app's own arguments.
     */
    private fun lowerLiteral(literal: FirLiteralExpression): BundleExpression? {

        val value = literal.value

        return when (literal.kind) {

            ConstantValueKind.Int,
            ConstantValueKind.IntegerLiteral,
            -> BundleExpression.IntConstant((value as Number).toInt())

            ConstantValueKind.Long -> BundleExpression.LongConstant((value as Number).toLong())
            ConstantValueKind.Float -> BundleExpression.FloatConstant((value as Number).toFloat())
            ConstantValueKind.Double -> BundleExpression.DoubleConstant((value as Number).toDouble())

            ConstantValueKind.String -> BundleExpression.StringConstant(value as String)
            ConstantValueKind.Boolean -> BundleExpression.BooleanConstant(value as Boolean)

            else -> {
                reject(
                    literal.sourceOffset(),
                    "the literal `$value`",
                    code = RejectionCode.UNSUPPORTED_LITERAL,
                    remedy = "Dootah bundles numbers, String and Boolean literals.",
                )
                null
            }
        }
    }

    private fun lowerReference(access: FirPropertyAccessExpression): BundleExpression? {

        val name = access.resolvedName()

        subjects.lastOrNull { (subjectName, _) -> subjectName == name }
            ?.let { (_, value) -> return value }

        states[name]?.let { type ->
            return BundleExpression.StateReference(name!!, type)
        }

        if (name != null && name in values) return BundleExpression.LocalReference(name)

        nativeOnlyParameters[name]?.let { parameter ->
            reject(
                access.sourceOffset(),
                "`${parameter.name}`, which is ${parameter.typeName}",
                code = RejectionCode.UNSUPPORTED_PARAMETER_TYPE,
                detail = parameter.typeName,
                remedy = "Dootah carries numbers, String and Boolean values to a bundle. " +
                    "`${parameter.name}` can still be used inside a component " +
                    "that stays native.",
            )
            return null
        }

        // Reading through something the screen holds natively. `note.title` is
        // not a name Dootah is missing, it is a field of an object it never
        // has, and reporting the field name alone sends a developer looking for
        // the wrong thing.
        val receiver = (access.explicitReceiver as? FirPropertyAccessExpression)?.resolvedName()

        if (receiver != null && (receiver in nativeOnlyParameters || receiver in nativeOnlyLocals)) {
            reject(
                access.sourceOffset(),
                "`$receiver.${name ?: "?"}`, read from something this screen holds natively",
                code = RejectionCode.UNKNOWN_REFERENCE,
                detail = "read through a native value:" +
                    (nativeOnlyParameters[receiver]?.typeName ?: receiver),
                remedy = "A bundled screen may read its own parameters and the values it " +
                    "declares. `$receiver` stays native, and so does anything read from it.",
            )
            return null
        }

        reject(
            access.sourceOffset(),
            if (name != null && name in nativeOnlyLocals) {
                "the reference `$name`, which this screen holds natively"
            } else {
                "the reference `${name ?: "unknown"}`"
            },
            code = RejectionCode.UNKNOWN_REFERENCE,
            // Whether the name is one the screen declared natively or one from
            // outside it altogether. They look identical in a build log and they
            // are completely different problems.
            detail = when {
                name == null -> null
                name in nativeOnlyLocals -> "native local:$name"
                else -> "outside the screen:$name"
            },
            remedy = "A bundled screen may read its own parameters and the values it " +
                "declares. Everything else stays native.",
        )

        return null
    }

    private fun lowerInterpolation(call: FirStringConcatenationCall): BundleExpression? {

        val parts = call.arguments.map { part -> lowerExpression(part) ?: return null }

        return BundleExpression.Interpolation(parts)
    }

    private fun lowerEquality(call: FirEqualityOperatorCall): BundleExpression? {

        val operator = when (call.operation.operator) {
            "==", "===" -> ComparisonOperator.EQUAL
            "!=", "!==" -> ComparisonOperator.NOT_EQUAL
            else -> {
                reject(
                    call.sourceOffset(),
                    "the operator `${call.operation.operator}`",
                    code = RejectionCode.UNSUPPORTED_OPERATOR,
                    detail = call.operation.operator,
                )
                return null
            }
        }

        val left = lowerExpression(call.arguments[0]) ?: return null
        val right = lowerExpression(call.arguments[1]) ?: return null

        return BundleExpression.Comparison(operator, left, right)
    }

    private fun lowerComparison(comparison: FirComparisonExpression): BundleExpression? {

        val operator = when (comparison.operation.operator) {
            "<" -> ComparisonOperator.LESS
            "<=" -> ComparisonOperator.LESS_OR_EQUAL
            ">" -> ComparisonOperator.GREATER
            ">=" -> ComparisonOperator.GREATER_OR_EQUAL
            else -> {
                reject(
                    comparison.sourceOffset(),
                    "the operator `${comparison.operation.operator}`",
                    code = RejectionCode.UNSUPPORTED_OPERATOR,
                    detail = comparison.operation.operator,
                )
                return null
            }
        }

        val call = comparison.compareToCall
        val left = call.explicitReceiver?.let { lowerExpression(it) } ?: return null
        val right = call.arguments.singleOrNull()?.let { lowerExpression(it) } ?: return null

        return BundleExpression.Comparison(operator, left, right)
    }

    private fun lowerBooleanOperator(
        expression: FirBooleanOperatorExpression,
    ): BundleExpression? {

        val operator = when (expression.kind) {
            LogicOperationKind.AND -> LogicalOperator.AND
            LogicOperationKind.OR -> LogicalOperator.OR
        }

        val left = lowerExpression(expression.leftOperand) ?: return null
        val right = lowerExpression(expression.rightOperand) ?: return null

        return BundleExpression.Logical(operator, left, right)
    }

    /** `if` and `when` used as a value. */
    private fun lowerConditionalExpression(expression: FirWhenExpression): BundleExpression? {

        val branches = lowerBranches(expression) { branch ->
            listOfNotNull((branch.unwrapReturn() as? FirExpression)?.let { lowerExpression(it) })
        } ?: return null

        var result: BundleExpression? = null

        for ((condition, values) in branches) {

            val value = values.singleOrNull()

            if (value == null) {
                reject(
                    expression.sourceOffset(),
                    "an `if` or `when` branch that does not produce a value",
                    code = RejectionCode.BRANCH_WITHOUT_VALUE,
                    remedy = "Every branch of a value must produce one, and a `when` used " +
                        "as a value needs an `else`.",
                )
                return null
            }

            result = if (condition == BundleExpression.BooleanConstant(true)) value
            else BundleExpression.Conditional(condition, value, result ?: return null)
        }

        return result
    }

    private fun lowerCallExpression(call: FirFunctionCall): BundleExpression? {

        val callable = call.resolvedCallableName()

        SupportedCatalog.arithmeticFor(callable ?: FqName.ROOT)?.let { operator ->
            return lowerBinary(call, operator)
        }

        if (callable != null && SupportedCatalog.isNot(callable)) {
            val operand = call.explicitReceiver?.let { lowerExpression(it) } ?: return null
            return BundleExpression.Not(operand)
        }

        if (callable != null && SupportedCatalog.isUnaryMinus(callable)) {
            val operand = call.explicitReceiver?.let { lowerExpression(it) } ?: return null
            return BundleExpression.Negate(operand)
        }

        return lowerBundledCall(call)
    }

    private fun lowerBinary(
        call: FirFunctionCall,
        operator: ArithmeticOperator,
    ): BundleExpression? {

        val left = call.explicitReceiver?.let { lowerExpression(it) } ?: return null
        val right = call.arguments.singleOrNull()?.let { lowerExpression(it) } ?: return null

        return BundleExpression.Arithmetic(operator, left, right)
    }

    /**
     * Lowers a call to a function the bundle can carry alongside the screen.
     *
     * Limited to a function whose body is one expression over the supported
     * types. That covers the pricing rules and formatting helpers screens
     * actually factor out, and stops well short of shipping arbitrary Kotlin.
     */
    private fun lowerBundledCall(call: FirFunctionCall): BundleExpression? {

        val symbol = call.calleeReference.toResolvedCallableSymbol()
        val declaration = symbol?.fir as? FirNamedFunction
        val name = symbol?.callableId?.callableName?.asString()

        if (declaration == null || name == null || call.explicitReceiver != null) {
            reject(
                call.sourceOffset(),
                "the call `${call.resolvedCallableName()?.asString() ?: "unknown"}` in a value",
                code = RejectionCode.UNSUPPORTED_CALL_IN_VALUE,
                detail = call.resolvedCallableName()?.asString(),
                remedy = "Dootah bundles arithmetic, comparisons and calls to simple " +
                    "single-expression functions in the same file.",
            )
            return null
        }

        val arguments = call.resolvedArgumentMapping?.keys
            ?.map { argument -> lowerExpression(argument) ?: return null }
            ?: emptyList()

        if (name !in functions && !lowerFunction(name, declaration)) return null

        return BundleExpression.Invoke(name, arguments)
    }

    private fun lowerFunction(name: String, declaration: FirNamedFunction): Boolean {

        val returnType = bundleTypeOf(declaration.returnTypeRef.coneTypeSafe<ConeKotlinType>())
        val body = declaration.body

        if (returnType == null || body == null) {
            reject(
                declaration.source?.startOffset,
                "the function `$name`, which Dootah cannot bundle",
                code = RejectionCode.UNSUPPORTED_FUNCTION_RETURN,
                remedy = "A bundled function returns an Int, String or Boolean.",
            )
            return false
        }

        val parameters = declaration.screenParameters()
        val unsupported = parameters.firstOrNull { it !is ScreenParameter.Value }

        if (unsupported != null) {
            reject(
                declaration.source?.startOffset,
                "the parameter `${unsupported.name}` of `$name`",
                code = RejectionCode.UNSUPPORTED_FUNCTION_PARAMETER,
                remedy = "A bundled function takes Int, String and Boolean parameters.",
            )
            return false
        }

        // Reserved before the body is lowered so a function that calls itself
        // does not recurse forever here.
        functions[name] = BundleFunction(
            name = name,
            parameters = parameters.valueParameters(),
            returnType = returnType,
            body = BundleExpression.BooleanConstant(false),
        )

        val outerValues = LinkedHashMap(values)
        val outerStates = LinkedHashMap(states)

        values.clear()
        states.clear()
        parameters.valueParameters().forEach { parameter ->
            values[parameter.name] = parameter.type
        }

        val expression = body.statements
            .mapNotNull { it.unwrapReturn() as? FirExpression }
            .singleOrNull()
            ?.let { lowerExpression(it) }

        values.clear()
        values.putAll(outerValues)
        states.clear()
        states.putAll(outerStates)

        if (expression == null) {
            functions.remove(name)
            reject(
                declaration.source?.startOffset,
                "the body of `$name`",
                code = RejectionCode.UNSUPPORTED_FUNCTION_BODY,
                remedy = "A bundled function's body is a single expression.",
            )
            return false
        }

        functions[name] = functions.getValue(name).copy(body = expression)

        return true
    }

    // ---- helpers --------------------------------------------------------

    /**
     * Lowers a component Dootah knows, falling back to keeping it native.
     *
     * A styled `Text`, or a `Button` wired to something Dootah cannot express,
     * is not an error: the component stays exactly as written and keeps working,
     * and only the structure around it becomes remote. So the direct attempt's
     * complaint is discarded when the fallback succeeds.
     *
     * When neither works, both reasons are reported, direct one first. The
     * direct reason names the construct the developer actually wrote, which is
     * far more useful than "this component reads a value the bundle computes".
     */
    private fun lowerComponent(
        call: FirFunctionCall,
        direct: () -> BundleUi?,
    ): BundleUi? {

        val attempted = attempt(direct)

        if (!attempted.failed) return attempted.value

        attempt { lowerNativeComponent(call) }.value?.let { component ->
            degradeTo("a component the bundle places", attempted.causes)
            return component
        }

        attempt { freeze(call) }.value?.let { region ->
            degradeTo("a region kept exactly as written", attempted.causes)
            return region
        }

        reasons += attempted.causes

        return null
    }

    /** Runs a lowering that may legitimately fail, discarding what it reported. */
    private fun <T : Any> speculate(block: () -> T?): T? = attempt(block).value

    private fun FirFunctionCall.isComposableCall(): Boolean = isComposable()

    private fun FirExpression.lambdaBody(): FirBlock? =
        (this as? FirAnonymousFunctionExpression)?.anonymousFunction?.body

    /**
     * Records that a region stayed native, without failing the screen.
     *
     * The distinction from [reject] is the whole of partial lowering: a reason
     * recorded here describes a boundary Dootah chose, and one recorded there
     * describes a screen it could not take on at all.
     */
    private fun keepNative(
        offset: Int?,
        found: String,
        code: RejectionCode,
        detail: String? = null,
        remedy: String = "The region keeps running natively.",
    ) {
        degraded += UnsupportedConstruct(
            functionName = functionName,
            filePath = filePath,
            sourceOffset = offset,
            found = found,
            remedy = remedy,
            code = code,
            detail = detail,
        )
    }

    /** Marks a name as one only the Android side can read. */
    private fun nativeOnly(name: String) {
        nativeOnlyLocals += name
        bodyDeclarations += name
    }

    private fun reject(
        offset: Int?,
        found: String,
        code: RejectionCode,
        detail: String? = null,
        remedy: String = "Keep this screen native until Dootah supports it.",
    ) {
        reasons += UnsupportedConstruct(
            functionName = functionName,
            filePath = filePath,
            sourceOffset = offset,
            found = found,
            remedy = remedy,
            code = code,
            detail = detail,
        )
    }

    private companion object {
        val NON_ACTION_CHARACTERS = Regex("[^a-z0-9]+")
        val REMEMBER = FqName("androidx.compose.runtime.remember")
        val MUTABLE_STATE_OF = FqName("androidx.compose.runtime.mutableStateOf")
    }
}

private fun FirFunctionCall.isComposable(): Boolean =
    calleeReference.toResolvedCallableSymbol()
        ?.resolvedAnnotationClassIds
        ?.any { it.asSingleFqName() == COMPOSABLE_ANNOTATION } == true

private const val UNNAMED_CALLEE = "unknown"

/**
 * Looks through the implicit `return` a block's last expression carries.
 *
 * Null for a return that yields nothing, which is what an empty lambda is made
 * of and what would otherwise be reported as an unsupported statement.
 */
internal fun FirElement.unwrapReturn(): FirElement? {

    // An empty lambda is one synthetic Unit, usually inside the implicit
    // return. It is a statement that does nothing, rather than a statement
    // Dootah does not understand, and it carries the lambda's own source -- so
    // the "no source means nothing was produced" rule below does not catch it.
    if (this is FirUnitExpression) return null

    if (this !is FirReturnExpression) return this

    return when (val produced = result) {
        is FirUnitExpression -> null
        is FirFunctionCall, is FirWhenExpression -> produced
        is FirLiteralExpression -> if (produced.value == null) null else produced
        else -> if (produced.source == null) null else produced
    }
}

internal fun FirElement.sourceOffset(): Int? = source?.startOffset

/**
 * Whether this branch is the `else`.
 *
 * FIR gives an `else` the synthetic always-true condition rather than no
 * condition at all, so recognising it is a type test rather than a null check.
 */
private fun FirExpression.isElse(): Boolean = this is FirElseIfTrueCondition
