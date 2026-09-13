package dev.dootah.compiler.ir

import dev.dootah.compiler.BUNDLABLE_ANNOTATION
import dev.dootah.compiler.COMPOSABLE_ANNOTATION
import dev.dootah.compiler.DOOTAH_NATIVE_ANNOTATION
import dev.dootah.compiler.PREVIEW_ANNOTATION_NAME
import dev.dootah.contract.ComposableShape
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation

/**
 * Reads the facts the shared eligibility rule needs, from IR.
 *
 * The counterpart of the FIR reader, and deliberately written to look like it.
 * These two run in different compilations over different versions of the same
 * app -- the APK is built once and the bundle is rebuilt after every edit -- so
 * any question they answer differently becomes a screen that exists on one side
 * of the wire and not the other.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrSimpleFunction.composableShape(file: IrFile): ComposableShape =
    ComposableShape(
        fqName = fqNameWhenAvailable?.asString() ?: name.asString(),
        isComposable = hasAnnotation(COMPOSABLE_ANNOTATION),
        returnsUnit = returnType.isUnit(),
        hasBody = body != null,
        isLocal = visibility == DescriptorVisibilities.LOCAL,
        isInline = isInline,
        isSuspend = isSuspend,
        isPreview = annotations.any {
            it.type.classFqName?.shortName()?.asString() == PREVIEW_ANNOTATION_NAME
        },
        hasReceiver = parameters.any { it.kind == IrParameterKind.ExtensionReceiver },
        takesComposableContent = parameters
            .filter { it.kind == IrParameterKind.Regular }
            .any { it.type.isComposableFunctionType() },
        isSuppressed = isSuppressedIn(file),
        isForced = hasAnnotation(BUNDLABLE_ANNOTATION),
    )

/** The function, any class it is declared in, or the file, marked `@DootahNative`. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrSimpleFunction.isSuppressedIn(file: IrFile): Boolean {

    if (hasAnnotation(DOOTAH_NATIVE_ANNOTATION)) return true
    if (file.hasAnnotation(DOOTAH_NATIVE_ANNOTATION)) return true

    var enclosing = parent

    while (enclosing is IrClass) {
        if (enclosing.hasAnnotation(DOOTAH_NATIVE_ANNOTATION)) return true
        enclosing = enclosing.parent
    }

    return false
}
