package dev.dootah.compiler.fir

import dev.dootah.compiler.BUNDLABLE_ANNOTATION
import dev.dootah.compiler.COMPOSABLE_ANNOTATION
import dev.dootah.compiler.DOOTAH_NATIVE_ANNOTATION
import dev.dootah.compiler.PREVIEW_ANNOTATION_NAME
import dev.dootah.contract.ComposableShape
import dev.dootah.contract.ComposeFunctionTypes
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.FqName

/**
 * Reads the facts the shared eligibility rule needs, from FIR.
 *
 * The whole point of the exercise is that this file decides nothing. It answers
 * questions; [dev.dootah.contract.ScreenEligibility] decides. Its counterpart on
 * the IR side answers the same questions about the same function in a different
 * compilation, and the tests pin that the two agree.
 */
internal fun FirNamedFunction.composableShape(context: CheckerContext): ComposableShape =
    ComposableShape(
        fqName = symbol.callableId.asSingleFqName().asString(),
        isComposable = hasAnnotationNamed(COMPOSABLE_ANNOTATION),
        returnsUnit = returnTypeRef.coneTypeSafe<ConeKotlinType>()
            ?.classId?.asSingleFqName() == UNIT,
        hasBody = body != null,
        isLocal = isLocal,
        isInline = status.isInline,
        isSuspend = status.isSuspend,
        isPreview = annotations.any { it.simpleName() == PREVIEW_ANNOTATION_NAME },
        hasReceiver = receiverParameter != null,
        takesComposableContent = valueParameters.any { it.isComposableContentParameter() },
        isSuppressed = isSuppressedBy(context),
        isForced = hasAnnotationNamed(BUNDLABLE_ANNOTATION),
    )

/**
 * Whether the developer asked for this to stay native, here or further out.
 *
 * A file annotation covers a screen file, a class annotation covers a screen
 * object -- both are how someone actually writes "none of this", and honouring
 * only the per-function form would make the annotation almost useless.
 */
private fun FirNamedFunction.isSuppressedBy(context: CheckerContext): Boolean {

    if (hasAnnotationNamed(DOOTAH_NATIVE_ANNOTATION)) return true

    val enclosing = context.containingDeclarations
        .filterIsInstance<FirRegularClassSymbol>()
        .flatMap { it.fir.annotations }

    val file = context.containingFileSymbol?.fir?.annotations.orEmpty()

    return (enclosing + file).any { annotation ->
        annotation.fqName() == DOOTAH_NATIVE_ANNOTATION
    }
}

/**
 * Whether this parameter is the content its caller draws inside it.
 *
 * Reads the type rather than the annotation, because a composable lambda is
 * spelled two different ways depending on whether the compiler is looking at
 * source or at something already compiled into a library.
 */
private fun FirValueParameter.isComposableContentParameter(): Boolean {

    val type = returnTypeRef.coneTypeSafe<ConeKotlinType>() ?: return false
    val name = type.classId?.asSingleFqName()?.asString() ?: return false

    if (ComposeFunctionTypes.isComposableFunction(name)) return true

    return ComposeFunctionTypes.isFunction(name) && type.customAnnotations.any { annotation ->
        annotation.annotationTypeRef.coneTypeSafe<ConeKotlinType>()
            ?.classId?.asSingleFqName() == COMPOSABLE_ANNOTATION
    }
}

private fun FirNamedFunction.hasAnnotationNamed(name: FqName): Boolean =
    annotations.any { annotation -> annotation.fqName() == name }

private fun FirAnnotation.fqName(): FqName? = resolvedType.classId?.asSingleFqName()

private fun FirAnnotation.simpleName(): String? = fqName()?.shortName()?.asString()

private val UNIT = FqName("kotlin.Unit")
