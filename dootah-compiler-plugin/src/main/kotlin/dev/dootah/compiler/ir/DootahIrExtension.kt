package dev.dootah.compiler.ir

import dev.dootah.compiler.DootahDiscovery
import dev.dootah.contract.ScreenFilter
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import java.io.File

/**
 * Dootah's transform on the host app's own compilation.
 *
 * Establishes the ordering the rewrite depends on, then rewrites. The ordering
 * check is a separate step rather than a condition inside the rewrite so that a
 * wrong plugin order is reported as itself instead of as a rewrite failure.
 */
internal class DootahIrExtension(
    private val messageCollector: MessageCollector,
    private val reportDirectory: File?,
    private val discovery: DootahDiscovery,
    private val filter: ScreenFilter,
) : IrGenerationExtension {

    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        val screens = moduleFragment.discoverScreens(discovery, filter)
        val ordering = composeOrderingOf(screens)

        val interceptedScreenIds = when (ordering) {

            ComposeOrdering.AFTER_COMPOSE -> {
                // Fail rather than transform. Inserting composable calls after
                // Compose has lowered produces code that compiles and then
                // misbehaves at run time, which is worse than a build failure
                // naming the fix.
                messageCollector.report(CompilerMessageSeverity.ERROR, wrongOrderMessage())
                emptyList()
            }

            ComposeOrdering.INCONCLUSIVE -> emptyList()

            ComposeOrdering.BEFORE_COMPOSE -> intercept(screens, pluginContext)
        }

        reportDirectory?.let { directory ->
            writeInterceptionReport(
                reportDirectory = directory,
                ordering = ordering,
                discoveredFunctionNames = screens.map { it.reportName() },
                interceptedScreenIds = interceptedScreenIds,
            )
        }
    }

    private fun intercept(
        screens: List<DiscoveredScreen>,
        pluginContext: IrPluginContext,
    ): List<String> = screens
        .groupBy { it.file }
        .flatMap { (file, inFile) ->

            val symbols = DootahRuntimeSymbols.resolve(pluginContext, file)

            if (symbols == null) {
                messageCollector.report(CompilerMessageSeverity.ERROR, missingRuntimeMessage())
                return emptyList()
            }

            val transformer = InterceptionTransformer(pluginContext, symbols)

            inFile.mapNotNull { screen -> transformer.transform(screen.function) }
        }
}

private fun DiscoveredScreen.reportName(): String =
    function.fqNameWhenAvailable?.asString() ?: function.name.asString()
