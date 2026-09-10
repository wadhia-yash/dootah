package dev.dootah.compiler.ir

import dev.dootah.compiler.BUNDLABLE_ANNOTATION
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.hasAnnotation

/**
 * A `@Bundlable` function together with the file that declares it.
 *
 * The file is carried because resolving a symbol is scoped to one: the
 * supported API resolves names as they are visible from a given file rather
 * than from the module as a whole.
 */
internal data class BundlableFunction(
    val file: IrFile,
    val function: IrSimpleFunction,
)

/**
 * Every `@Bundlable` function in [module], in a stable order.
 *
 * Nested containers are searched even though Milestone 1 only supports
 * top-level functions: finding a `@Bundlable` function in an unsupported
 * position is what lets Dootah report it, and a search that skipped those
 * positions would silently ignore the annotation instead.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrModuleFragment.bundlableFunctions(): List<BundlableFunction> =
    files
        .sortedBy { it.fileEntry.name }
        .flatMap { file ->
            file.collectBundlable().map { BundlableFunction(file = file, function = it) }
        }

// Reading `declarations` is only unsafe while IR is still being built. This
// runs from IrGenerationExtension.generate, after the module is complete.
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrDeclarationContainer.collectBundlable(): List<IrSimpleFunction> =
    declarations.flatMap { declaration ->
        when (declaration) {
            is IrSimpleFunction ->
                if (declaration.hasAnnotation(BUNDLABLE_ANNOTATION)) listOf(declaration)
                else emptyList()

            is IrDeclarationContainer -> declaration.collectBundlable()

            else -> emptyList()
        }
    }
