package dev.dootah.compiler.fir

import dev.dootah.compiler.BUNDLABLE_ANNOTATION
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType

/**
 * The screen identity, computed from FIR.
 *
 * Must agree exactly with the identity the IR interception writes into the app,
 * because the two are compared across the network. The rule is stated once here
 * and once for IR against the same inputs -- an explicit id, otherwise the fully
 * qualified name -- and the tests pin that they agree.
 *
 * `@Bundlable` is no longer how a screen is found; it survives as the way to
 * pin an identity that must not follow the function's name, and as a way to
 * force-include something a build script excluded.
 */
internal fun FirNamedFunction.dootahScreenId(): String {

    explicitBundlableId()?.let { return it }

    return symbol.callableId.asSingleFqName().asString()
}

/** The id given to `@Bundlable("...")`, when one was given. */
private fun FirNamedFunction.explicitBundlableId(): String? {

    val bundlable = annotations.firstOrNull { annotation ->
        annotation.resolvedType.classId?.asSingleFqName() == BUNDLABLE_ANNOTATION
    } ?: return null

    val declared = (bundlable.argumentMapping.mapping[Name.identifier(ID_ARGUMENT)]
        as? FirLiteralExpression)
        ?.value as? String

    return declared?.takeIf { it.isNotBlank() }
}

private const val ID_ARGUMENT = "id"
