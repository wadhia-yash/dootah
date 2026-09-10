package dev.dootah.compiler.fir

import dev.dootah.compiler.model.BundleExpression
import dev.dootah.compiler.model.BundleLocal
import dev.dootah.compiler.model.BundleScreen
import dev.dootah.compiler.model.BundleType
import dev.dootah.compiler.model.BundleUi
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.name.FqName

/** The outcome of lowering one screen. */
internal sealed interface LoweringResult {

    data class Lowered(val screen: BundleScreen) : LoweringResult

    /** Nothing is emitted for a rejected screen; the app keeps its native body. */
    data class Rejected(val reasons: List<UnsupportedConstruct>) : LoweringResult
}

/**
 * Turns a resolved `@Bundlable` function body into a [BundleScreen].
 *
 * Every construct is either recognised explicitly or rejected. There is no
 * best-effort branch, because the failure it would cause -- a bundle that runs
 * and renders the wrong thing -- is worse than a build that stops and says what
 * it cannot do.
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

    /** Locals lowered so far, so a reference can be checked against them. */
    private val locals = mutableListOf<BundleLocal>()

    fun lower(screenId: String): LoweringResult {

        rejectUnsupportedSignature()

        val body = function.body
        if (body == null) {
            reject(null, "a function with no body", "Give the function a body.")
            return LoweringResult.Rejected(reasons)
        }

        val ui = lowerBody(body)

        return if (reasons.isNotEmpty() || ui == null) LoweringResult.Rejected(reasons)
        else LoweringResult.Lowered(
            BundleScreen(
                screenId = screenId,
                functionName = functionName,
                locals = locals.toList(),
                ui = ui,
            )
        )
    }

    /**
     * Milestone 1 lowers zero-argument screens only.
     *
     * Stated as its own check so the message names the limit rather than
     * surfacing later as an unresolvable reference to a parameter.
     */
    private fun rejectUnsupportedSignature() {

        if (function.valueParameters.isNotEmpty()) {
            reject(
                function.source?.startOffset,
                "a @Bundlable function with ${function.valueParameters.size} parameter(s)",
                "Dootah currently bundles zero-argument screens only. " +
                    "Move the parameters into the screen, or keep this screen native.",
            )
        }
    }

    /**
     * A screen body is a run of local `val`s followed by exactly one root
     * composable call.
     */
    private fun lowerBody(body: FirBlock): BundleUi? {

        var root: BundleUi? = null

        for (statement in body.statements) {
            when (statement) {

                is FirProperty -> lowerLocal(statement)

                is FirFunctionCall -> {
                    val lowered = lowerUi(statement) ?: continue

                    if (root != null) {
                        reject(
                            statement.source?.startOffset,
                            "more than one top-level composable in the screen body",
                            "Wrap the screen's content in a single Column { }.",
                        )
                        continue
                    }
                    root = lowered
                }

                else -> reject(
                    statement.source?.startOffset,
                    "the statement ${statement::class.simpleName}",
                    "Milestone 1 supports local `val` declarations and one Column { } only.",
                )
            }
        }

        if (root == null && reasons.isEmpty()) {
            reject(
                function.source?.startOffset,
                "a screen body with no composable content",
                "Add a Column { } containing the screen's content.",
            )
        }

        return root
    }

    private fun lowerLocal(property: FirProperty) {

        val name = property.name.asString()

        if (!property.isVal) {
            reject(
                property.source?.startOffset,
                "the `var` declaration `$name`",
                "Milestone 1 supports `val` only. Mutable screen state is not bundled yet.",
            )
            return
        }

        val type = bundleTypeOf(property.returnTypeRef.coneTypeFqName())
        if (type == null) {
            reject(
                property.source?.startOffset,
                "the local `$name` of type ${property.returnTypeRef.coneTypeFqName() ?: "unknown"}",
                "Milestone 1 supports Int, String and Boolean locals.",
            )
            return
        }

        val initializer = property.initializer
        if (initializer == null) {
            reject(
                property.source?.startOffset,
                "the local `$name` with no initializer",
                "Give the local a value.",
            )
            return
        }

        val value = lowerExpression(initializer) ?: return

        locals += BundleLocal(name = name, type = type, value = value)
    }

    // ---- UI -------------------------------------------------------------

    private fun lowerUi(call: FirFunctionCall): BundleUi? =
        when (val callable = call.resolvedCallableName()) {

            SupportedCatalog.COLUMN -> lowerColumn(call)
            SupportedCatalog.TEXT -> lowerText(call)
            SupportedCatalog.BUTTON -> lowerButton(call)

            else -> {
                reject(
                    call.source?.startOffset,
                    "the call `${callable?.shortName()?.asString() ?: "unknown"}()`" +
                        (callable?.let { " ($it)" } ?: ""),
                    "Dootah can bundle " +
                        SupportedCatalog.SUPPORTED_COMPOSABLES.joinToString(", ") {
                            it.shortName().asString()
                        } +
                        ". Keep this screen native, or reach the capability through " +
                        "Dootah's NativeBridge.",
                )
                null
            }
        }

    private fun lowerColumn(call: FirFunctionCall): BundleUi? {

        val content = call.trailingLambdaBody()
        if (content == null) {
            reject(
                call.source?.startOffset,
                "a Column without a content lambda",
                "Write Column { } with its content inside the braces.",
            )
            return null
        }

        val children = content.statements.mapNotNull { statement ->
            if (statement is FirFunctionCall) {
                val child = lowerUi(statement)

                if (child is BundleUi.ColumnUi) {
                    // The bundle DSL's column scope offers Text and Button only,
                    // so a nested layout has no representation to lower into.
                    reject(
                        statement.source?.startOffset,
                        "a layout nested inside a Column",
                        "Milestone 1 bundles one flat Column of Text and Button.",
                    )
                    null
                } else {
                    child
                }
            } else {
                reject(
                    statement.source?.startOffset,
                    "the statement ${statement::class.simpleName} inside a Column",
                    "A Column may contain Text and Button calls only.",
                )
                null
            }
        }

        return BundleUi.ColumnUi(children)
    }

    private fun lowerText(call: FirFunctionCall): BundleUi? {

        val argument = call.singleSupportedArgument("Text", "text") ?: return null
        val text = lowerExpression(argument) ?: return null

        return BundleUi.TextUi(text)
    }

    /**
     * Lowers `Button(onClick = { }) { Text("...") }`.
     *
     * The click handler must be empty. A bundle that dropped the body of a
     * non-empty handler would produce a button that looks live and does nothing,
     * so a handler with statements is rejected instead.
     */
    private fun lowerButton(call: FirFunctionCall): BundleUi? {

        val arguments = call.resolvedArgumentMapping
        if (arguments == null) {
            reject(call.source?.startOffset, "a Button call Dootah could not read", "Simplify the call.")
            return null
        }

        var onClick: FirBlock? = null
        var content: FirBlock? = null
        var sawUnknownArgument = false

        for ((expression, parameter) in arguments) {
            when (parameter.name.asString()) {
                "onClick" -> onClick = expression.lambdaBody()
                "content" -> content = expression.lambdaBody()
                else -> {
                    reject(
                        expression.source?.startOffset,
                        "the Button argument `${parameter.name.asString()}`",
                        "Milestone 1 supports Button(onClick = { }) { Text(\"...\") } only.",
                    )
                    sawUnknownArgument = true
                }
            }
        }

        if (sawUnknownArgument) return null

        if (onClick != null && !onClick.isEffectivelyEmpty()) {
            reject(
                call.source?.startOffset,
                "a Button whose onClick has a body",
                "Milestone 1 bundles buttons with an empty onClick. " +
                    "Keep this screen native until bundled click handling lands.",
            )
            return null
        }

        val label = content
            ?.statements
            ?.singleOrNull()
            ?.let { it as? FirFunctionCall }
            ?.takeIf { it.resolvedCallableName() == SupportedCatalog.TEXT }
            ?.singleSupportedArgument("Text", "text")
            ?.let { lowerExpression(it) }

        if (label == null) {
            reject(
                call.source?.startOffset,
                "a Button whose content is not a single Text",
                "Write Button(onClick = { }) { Text(\"label\") }.",
            )
            return null
        }

        return BundleUi.ButtonUi(label = label, action = actionNameFor(label))
    }

    /**
     * Names the tap after the button's label.
     *
     * Milestone 1 has no click behaviour to dispatch, so the action only has to
     * be stable and readable in a log.
     */
    private fun actionNameFor(label: BundleExpression): String =
        when (label) {
            is BundleExpression.StringConstant ->
                label.value.lowercase().replace(NON_ACTION_CHARACTERS, "-")
            else -> "button"
        }

    // ---- expressions ----------------------------------------------------

    private fun lowerExpression(expression: FirExpression): BundleExpression? =
        when (expression) {

            is FirLiteralExpression -> lowerLiteral(expression)

            is FirPropertyAccessExpression -> lowerLocalReference(expression)

            is FirStringConcatenationCall -> lowerInterpolation(expression)

            is FirFunctionCall -> lowerArithmetic(expression)

            else -> {
                reject(
                    expression.source?.startOffset,
                    "the expression ${expression::class.simpleName}",
                    "Milestone 1 supports literals, local `val` references, " +
                        "Int arithmetic and string templates.",
                )
                null
            }
        }

    private fun lowerLiteral(literal: FirLiteralExpression): BundleExpression? =
        when (val value = literal.value) {
            is Int -> BundleExpression.IntConstant(value)
            is String -> BundleExpression.StringConstant(value)
            is Boolean -> BundleExpression.BooleanConstant(value)
            is Long -> BundleExpression.IntConstant(value.toInt())
            else -> {
                reject(
                    literal.source?.startOffset,
                    "the literal `$value`",
                    "Milestone 1 supports Int, String and Boolean literals.",
                )
                null
            }
        }

    private fun lowerLocalReference(access: FirPropertyAccessExpression): BundleExpression? {

        val name = (access.calleeReference as? FirResolvedNamedReference)?.name?.asString()

        val known = name != null && locals.any { it.name == name }

        if (!known) {
            reject(
                access.source?.startOffset,
                "the reference `${name ?: "unknown"}`",
                "A bundled screen may only read local `val`s declared in the same function.",
            )
            return null
        }

        return BundleExpression.LocalReference(name!!)
    }

    private fun lowerInterpolation(call: FirStringConcatenationCall): BundleExpression? {

        val parts = call.arguments.map { part -> lowerExpression(part) ?: return null }

        return BundleExpression.Interpolation(parts)
    }

    private fun lowerArithmetic(call: FirFunctionCall): BundleExpression? {

        val callable = call.resolvedCallableName()
        val operator = callable?.let(SupportedCatalog::arithmeticFor)

        if (operator == null) {
            reject(
                call.source?.startOffset,
                "the call `${callable?.asString() ?: "unknown"}` in a value",
                "Milestone 1 supports Int arithmetic (+ - * / %) in bundled values.",
            )
            return null
        }

        val left = call.explicitReceiver?.let { lowerExpression(it) } ?: return null
        val right = call.arguments.singleOrNull()?.let { lowerExpression(it) } ?: return null

        return BundleExpression.Arithmetic(operator, left, right)
    }

    // ---- helpers --------------------------------------------------------

    private fun FirFunctionCall.singleSupportedArgument(
        callName: String,
        parameterName: String,
    ): FirExpression? {

        val mapping = resolvedArgumentMapping

        if (mapping == null || mapping.size != 1 ||
            mapping.values.single().name.asString() != parameterName
        ) {
            reject(
                source?.startOffset,
                "a $callName call with arguments Dootah does not support",
                "Milestone 1 supports $callName(<text>) with no other arguments -- " +
                    "no Modifier, no styling.",
            )
            return null
        }

        return mapping.keys.single()
    }

    private fun FirFunctionCall.trailingLambdaBody(): FirBlock? =
        arguments.lastOrNull()?.lambdaBody()

    private fun FirExpression.lambdaBody(): FirBlock? =
        (this as? FirAnonymousFunctionExpression)?.anonymousFunction?.body

    /**
     * Whether a lambda body does nothing.
     *
     * `{ }` is not an empty block in FIR: it carries the implicit `return Unit`
     * every lambda ends with. So emptiness is decided by what a statement can
     * be, not by counting statements.
     *
     * Deliberately a whitelist. Allowing everything except a known list of
     * effects would let an unfamiliar construct pass as "empty", and a button
     * that silently drops its handler is worse than one the build refuses to
     * bundle.
     */
    private fun FirBlock.isEffectivelyEmpty(): Boolean =
        statements.all { statement ->
            statement is FirReturnExpression && statement.result !is FirFunctionCall
        }

    private fun FirFunctionCall.resolvedCallableName(): FqName? =
        calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()

    private fun bundleTypeOf(typeName: FqName?): BundleType? =
        when (typeName?.asString()) {
            "kotlin.Int" -> BundleType.INT
            "kotlin.String" -> BundleType.STRING
            "kotlin.Boolean" -> BundleType.BOOLEAN
            else -> null
        }

    private fun org.jetbrains.kotlin.fir.types.FirTypeRef.coneTypeFqName(): FqName? =
        coneTypeOrNull?.classId?.asSingleFqName()

    private fun reject(offset: Int?, found: String, remedy: String) {
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
    }
}
