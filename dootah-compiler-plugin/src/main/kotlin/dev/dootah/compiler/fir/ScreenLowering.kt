package dev.dootah.compiler.fir

import dev.dootah.compiler.COMPOSABLE_ANNOTATION
import dev.dootah.compiler.model.ArithmeticOperator
import dev.dootah.compiler.model.BundleAction
import dev.dootah.compiler.model.BundleCommandModel
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
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.name.FqName
import dev.dootah.compiler.ir.nativeSlotName
import dev.dootah.compiler.ir.nativeSlotShape
import java.util.IdentityHashMap

/** The outcome of lowering one screen. */
internal sealed interface LoweringResult {

    data class Lowered(val screen: BundleScreen) : LoweringResult

    /** Nothing is emitted for a rejected screen; the app keeps its native body. */
    data class Rejected(val reasons: List<UnsupportedConstruct>) : LoweringResult
}

/**
 * Turns a resolved `@Bundlable` function body into a [BundleScreen].
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
    private val callbacks = signature.callbackNames()

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

    /** Components kept native, so the build can say what will not update. */
    private val nativeSlots = linkedSetOf<String>()

    /**
     * What each call in the body would be called if it became a native slot.
     *
     * Computed once, over the whole body, so that a component's name does not
     * depend on which of its neighbours turned out to be slots.
     */
    private var slotNames: Map<FirFunctionCall, String> = emptyMap()

    private val prelude = mutableListOf<BundleStatement>()
    private val actions = mutableListOf<BundleAction>()
    private val functions = LinkedHashMap<String, BundleFunction>()

    private val modifiers = ModifierLowering(
        rejector = { offset, found, remedy -> reject(offset, found, remedy) },
        modifierParameterName = modifierParameter,
    )

    fun lower(screenId: String): LoweringResult {

        signature.filterIsInstance<ScreenParameter.Value>().forEach { parameter ->
            values[parameter.name] = parameter.type
        }

        val body = function.body
        if (body == null) {
            reject(null, "a function with no body", "Give the function a body.")
            return LoweringResult.Rejected(reasons)
        }

        slotNames = body.nativeSlotNames()

        val ui = lowerBody(body)

        return if (reasons.isNotEmpty() || ui == null) LoweringResult.Rejected(reasons)
        else LoweringResult.Lowered(
            BundleScreen(
                screenId = screenId,
                functionName = functionName,
                parameters = signature.valueParameters(),
                callbacks = callbacks,
                prelude = prelude.toList(),
                ui = ui,
                actions = actions.toList(),
                functions = functions.values.toList(),
                nativeComponents = nativeSlots.toList(),
            )
        )
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

                else -> roots += lowerUiStatement(unwrapped)
            }
        }

        if (roots.isEmpty()) {
            if (reasons.isEmpty()) {
                reject(
                    function.source?.startOffset,
                    "a screen body with no composable content",
                    "Add a Column { } containing the screen's content.",
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
            reject(
                property.source?.startOffset,
                "the local `$name`, which is not an Int, String or Boolean",
                "Dootah bundles Int, String and Boolean locals. Keep other values " +
                    "native, and use them inside a component that stays native.",
            )
            return
        }

        val initializer = property.initializer ?: property.delegate?.rememberedInitialValue()

        if (initializer == null) {
            reject(
                property.source?.startOffset,
                "the local `$name` with no value Dootah could read",
                "Give the local a literal or an expression over other bundled values.",
            )
            return
        }

        val value = lowerExpression(initializer) ?: return

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
                    "A layout may contain components, and `if` / `when` choosing " +
                        "between them.",
                )
                emptyList()
            }
        }

    private fun lowerUi(call: FirFunctionCall): BundleUi? =
        when (call.resolvedCallableName()) {

            SupportedCatalog.COLUMN -> lowerContainer(call) { mods, kids ->
                BundleUi.ColumnUi(mods, kids)
            }

            SupportedCatalog.ROW -> lowerContainer(call) { mods, kids ->
                BundleUi.RowUi(mods, kids)
            }

            SupportedCatalog.BOX -> lowerContainer(call) { mods, kids ->
                BundleUi.BoxUi(mods, kids)
            }

            // A component Dootah understands, unless it is styled or wired in a
            // way it does not -- in which case the whole component stays native
            // rather than being drawn without its styling or its behaviour.
            SupportedCatalog.TEXT -> lowerComponent(call) { lowerText(call) }

            SupportedCatalog.BUTTON -> lowerComponent(call) { lowerButton(call) }

            else -> lowerNativeSlot(call)
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
        build: (List<BundleModifier>, List<BundleUi>) -> BundleUi,
    ): BundleUi? {

        val name = call.resolvedCallableName()?.shortName()?.asString() ?: "layout"
        val mapping = call.resolvedArgumentMapping

        if (mapping == null) {
            reject(call.sourceOffset(), "a $name call Dootah could not read", "Simplify the call.")
            return null
        }

        var modifierList = emptyList<BundleModifier>()
        var content: FirBlock? = null
        var rejected = false

        for ((expression, parameter) in mapping) {
            when (parameter.name.asString()) {

                "modifier" -> modifierList = modifiers.lower(expression)
                    ?: run { rejected = true; emptyList() }

                "content" -> content = expression.lambdaBody()

                else -> {
                    reject(
                        expression.source?.startOffset,
                        "the $name argument `${parameter.name.asString()}`",
                        "Dootah bundles $name(modifier) { }. Alignment and " +
                            "arrangement are not bundled yet.",
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
                "Write $name { } with its content inside the braces.",
            )
            return null
        }

        val children = content.statements.flatMap { lowerUiStatement(it) }

        return build(modifierList, children)
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

        (expression as? FirPropertyAccessExpression)?.resolvedName()?.let { name ->
            if (name in callbacks) {
                return listOf(BundleStatement.Perform(BundleCommandModel.InvokeCallback(name)))
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
    private fun lowerNativeSlot(call: FirFunctionCall): BundleUi? {

        val callable = call.resolvedCallableName()
        val shortName = callable?.shortName()?.asString() ?: "unknown"

        if (!call.isComposableCall()) {
            reject(
                call.sourceOffset(),
                "the call `$shortName()` inside a layout",
                "A layout may contain components. Move other work out of the " +
                    "screen body, or keep this screen native.",
            )
            return null
        }

        if (call.readsAnOuterReceiver()) {
            reject(
                call.sourceOffset(),
                "`$shortName()`, which reads the scope of the layout around it",
                "A component Dootah keeps native is lifted out into its own " +
                    "lambda, so it cannot read a `ColumnScope` or `RowScope` " +
                    "from around it -- `weight` is the usual reason. Give it a " +
                    "size instead, or keep this screen native.",
            )
            return null
        }

        val captured = call.declarationsReadFromBody()

        if (captured.isNotEmpty()) {
            reject(
                call.sourceOffset(),
                "`$shortName()`, which Dootah keeps native but which reads " +
                    captured.joinToString(", ") { "`$it`" },
                "A component Dootah keeps native cannot read a value the bundle " +
                    "computes. Pass it in as a screen parameter, or build the " +
                    "component out of Column, Row, Box, Text and Button.",
            )
            return null
        }

        val name = slotNames[call]

        if (name == null) {
            reject(call.sourceOffset(), "`$shortName()`, which Dootah could not name", "Simplify the call.")
            return null
        }

        // Recorded under the name the app registers it by, so a developer
        // reading the build output can match it against what a device reports.
        nativeSlots += name

        return BundleUi.NativeSlotUi(name)
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
                    if (element is FirThisReceiverExpression) {
                        val bound = element.calleeReference.boundSymbol
                        if (bound == null || bound !in introducedHere) readsOuter = true
                    }
                    element.acceptChildren(this)
                }
            }
        )

        return readsOuter
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
                        "A bundled click handler may assign to the screen's own " +
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
                "A bundled click handler may assign to a `var` declared in the " +
                    "same screen.",
            )
            return null
        }

        val value = lowerExpression(assignment.rValue) ?: return null

        return BundleStatement.Assign(name = target, type = type, value = value)
    }

    private fun lowerCallStatement(call: FirFunctionCall): BundleStatement? {

        val callback = call.invokedCallbackName()

        if (callback != null) {
            return BundleStatement.Perform(BundleCommandModel.InvokeCallback(callback))
        }

        reject(
            call.sourceOffset(),
            "the call `${call.resolvedCallableName()?.shortName()?.asString() ?: "unknown"}()` " +
                "in a click handler",
            "Remote code reaches the app only through the screen's own callback " +
                "parameters. Add a `() -> Unit` parameter and call that.",
        )

        return null
    }

    /** The screen callback this call invokes, if that is what it is. */
    private fun FirFunctionCall.invokedCallbackName(): String? {

        val receiverName = (explicitReceiver as? FirPropertyAccessExpression)?.resolvedName()
        if (receiverName != null && receiverName in callbacks) return receiverName

        val directName = (calleeReference.toResolvedCallableSymbol()?.callableId
            ?.callableName?.asString())

        return directName?.takeIf { it in callbacks }
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
                    "Dootah bundles literals, the screen's own values, arithmetic, " +
                        "comparisons, Boolean logic, string templates and `if` / `when`.",
                )
                null
            }
        }

    private fun lowerLiteral(literal: FirLiteralExpression): BundleExpression? =
        when (val value = literal.value) {
            is Int -> BundleExpression.IntConstant(value)
            is Long -> BundleExpression.IntConstant(value.toInt())
            is String -> BundleExpression.StringConstant(value)
            is Boolean -> BundleExpression.BooleanConstant(value)
            else -> {
                reject(
                    literal.sourceOffset(),
                    "the literal `$value`",
                    "Dootah bundles Int, String and Boolean literals.",
                )
                null
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
                "Dootah carries String, Int and Boolean values to a bundle. " +
                    "`${parameter.name}` can still be used inside a component " +
                    "that stays native.",
            )
            return null
        }

        reject(
            access.sourceOffset(),
            "the reference `${name ?: "unknown"}`",
            "A bundled screen may read its own parameters and the values it " +
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
                reject(call.sourceOffset(), "the operator `${call.operation.operator}`")
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
                reject(comparison.sourceOffset(), "the operator `${comparison.operation.operator}`")
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
                    "Every branch of a value must produce one, and a `when` used " +
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
                "Dootah bundles arithmetic, comparisons and calls to simple " +
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
                "A bundled function returns an Int, String or Boolean.",
            )
            return false
        }

        val parameters = declaration.screenParameters()
        val unsupported = parameters.firstOrNull { it !is ScreenParameter.Value }

        if (unsupported != null) {
            reject(
                declaration.source?.startOffset,
                "the parameter `${unsupported.name}` of `$name`",
                "A bundled function takes Int, String and Boolean parameters.",
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
                "A bundled function's body is a single expression.",
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

        val mark = reasons.size

        direct()?.let { return it }

        val discarded = reasons.subList(mark, reasons.size).toList()
        while (reasons.size > mark) reasons.removeAt(reasons.lastIndex)

        lowerNativeSlot(call)?.let { return it }

        reasons.addAll(mark, discarded)

        return null
    }

    /** Runs a lowering that may legitimately fail, discarding what it reported. */
    private fun <T> speculate(attempt: () -> T?): T? {

        val mark = reasons.size
        val result = attempt()

        if (result == null) {
            while (reasons.size > mark) reasons.removeAt(reasons.lastIndex)
        }

        return result
    }

    private fun FirFunctionCall.isComposableCall(): Boolean =
        calleeReference.toResolvedCallableSymbol()
            ?.resolvedAnnotationClassIds
            ?.any { it.asSingleFqName() == COMPOSABLE_ANNOTATION } == true

    private fun FirExpression.lambdaBody(): FirBlock? =
        (this as? FirAnonymousFunctionExpression)?.anonymousFunction?.body

    private fun reject(
        offset: Int?,
        found: String,
        remedy: String = "Keep this screen native until Dootah supports it.",
    ) {
        reasons += UnsupportedConstruct(
            functionName = functionName,
            filePath = filePath,
            sourceOffset = offset,
            found = found,
            remedy = remedy,
        )
    }

    private companion object {
        val NON_ACTION_CHARACTERS = Regex("[^a-z0-9]+")
        val REMEMBER = FqName("androidx.compose.runtime.remember")
        val MUTABLE_STATE_OF = FqName("androidx.compose.runtime.mutableStateOf")
    }
}

/**
 * Names every call in a screen body: what it is called, and which one it is.
 *
 * Must agree with the walk the app's own build does, because the two run over
 * two different versions of the source -- the one the APK was built from, and
 * the edited one a bundle is published from. Counting per callee name, over the
 * whole body, is what makes a name survive an edit somewhere else in the file.
 */
private fun FirElement.nativeSlotNames(): Map<FirFunctionCall, String> {

    val counts = mutableMapOf<String, Int>()
    val names = IdentityHashMap<FirFunctionCall, String>()

    accept(object : org.jetbrains.kotlin.fir.visitors.FirVisitorVoid() {

        override fun visitElement(element: FirElement) {

            if (element is FirFunctionCall) {

                val callee = element.calleeReference
                    .toResolvedCallableSymbol()
                    ?.callableId
                    ?.callableName
                    ?.asString()
                    ?: UNNAMED_CALLEE

                val shape = nativeSlotShape(
                    callee = callee,
                    argumentNames = element.resolvedArgumentMapping
                        ?.values
                        ?.map { parameter -> parameter.name.asString() }
                        .orEmpty(),
                )

                val ordinal = counts.getOrElse(shape) { 0 }
                counts[shape] = ordinal + 1
                names[element] = nativeSlotName(shape, ordinal)
            }

            element.acceptChildren(this)
        }
    })

    return names
}

private const val UNNAMED_CALLEE = "unknown"

/**
 * Looks through the implicit `return` a block's last expression carries.
 *
 * Null for a return that yields nothing, which is what an empty lambda is made
 * of and what would otherwise be reported as an unsupported statement.
 */
internal fun FirElement.unwrapReturn(): FirElement? {

    if (this !is FirReturnExpression) return this

    return when (val produced = result) {
        is FirFunctionCall, is FirWhenExpression -> produced
        is FirLiteralExpression -> if (produced.value == null) null else produced
        else -> if (produced.source == null) null else produced
    }
}

private fun FirElement.sourceOffset(): Int? = source?.startOffset

/**
 * Whether this branch is the `else`.
 *
 * FIR gives an `else` the synthetic always-true condition rather than no
 * condition at all, so recognising it is a type test rather than a null check.
 */
private fun FirExpression.isElse(): Boolean = this is FirElseIfTrueCondition
