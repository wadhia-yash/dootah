package dev.dootah.compiler.ir

import dev.dootah.compiler.compat.*
import dev.dootah.contract.ScreenEligibility
import dev.dootah.contract.ScreenFilter
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI

/**
 * A Compose function Dootah will rewrite, together with the file declaring it.
 *
 * The file is carried because resolving a symbol is scoped to one: the supported
 * API resolves names as they are visible from a given file rather than from the
 * module as a whole. It is also where a `@DootahNative` file annotation lives.
 */
internal data class DiscoveredScreen(
    val file: IrFile,
    val function: IrSimpleFunction,
)

/**
 * Every Compose function in [module] that Dootah may take over, in a stable
 * order.
 *
 * This runs inside the app's own compilation, and what it decides here is
 * permanent: the APK ships with interception for exactly this set, and no bundle
 * published afterwards can add to it. A bundle that names a screen missing from
 * this set simply does not render -- the app draws its native body -- so the set
 * has to be decided by something that does not move when the source does, which
 * is why the rule is structural and lives in the contract module.
 *
 * Nested containers are searched. Local functions are not, and the eligibility
 * rule excludes them for that reason: the frontend can see them and this cannot,
 * so accepting them on one side only would put screens in a bundle that the app
 * has no way to render.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrModuleFragment.discoverScreens(
    filter: ScreenFilter,
): List<DiscoveredScreen> =
    files
        .sortedBy { it.fileEntry.name }
        .flatMap { file ->
            file.eligibleFunctions(file, filter)
                .map { DiscoveredScreen(file = file, function = it) }
        }

// Reading `declarations` is only unsafe while IR is still being built. This
// runs from IrGenerationExtension.generate, after the module is complete.
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrDeclarationContainer.eligibleFunctions(
    file: IrFile,
    filter: ScreenFilter,
): List<IrSimpleFunction> =
    declarations.flatMap { declaration ->
        when (declaration) {

            is IrSimpleFunction ->
                if (declaration.isDiscovered(file, filter)) listOf(declaration)
                else emptyList()

            is IrDeclarationContainer -> declaration.eligibleFunctions(file, filter)

            else -> emptyList()
        }
    }

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrSimpleFunction.isDiscovered(
    file: IrFile,
    filter: ScreenFilter,
): Boolean {

    return ScreenEligibility.accepts(composableShape(file), filter)
}
