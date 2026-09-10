package dev.dootah.compiler.ir

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
) : IrGenerationExtension {

    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        val bundlable = moduleFragment.bundlableFunctions()
        val ordering = composeOrderingOf(bundlable)

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

            ComposeOrdering.BEFORE_COMPOSE -> intercept(bundlable, pluginContext)
        }

        reportDirectory?.let { directory ->
            writeInterceptionReport(
                reportDirectory = directory,
                ordering = ordering,
                bundlableFunctionNames = bundlable.map { it.reportName() },
                interceptedScreenIds = interceptedScreenIds,
            )
        }
    }

    private fun intercept(
        bundlable: List<BundlableFunction>,
        pluginContext: IrPluginContext,
    ): List<String> = bundlable
        .groupBy { it.file }
        .flatMap { (file, screens) ->

            val symbols = DootahRuntimeSymbols.resolve(pluginContext, file)

            if (symbols == null) {
                messageCollector.report(CompilerMessageSeverity.ERROR, missingRuntimeMessage())
                return emptyList()
            }

            val transformer = InterceptionTransformer(pluginContext, symbols)

            screens.mapNotNull { screen -> transformer.transform(screen.function) }
        }
}

private fun BundlableFunction.reportName(): String =
    function.fqNameWhenAvailable?.asString() ?: function.name.asString()
