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
 * Phase 0 scope: establish and enforce the ordering the transform depends on.
 * The body rewrite lands on top of this once the ordering is proven, which is
 * why the guard is a separate, independently testable step rather than a check
 * buried inside the rewrite.
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

        reportDirectory?.let { directory ->
            writeOrderingReport(
                reportDirectory = directory,
                ordering = ordering,
                bundlableFunctionNames = bundlable.map { it.reportName() },
            )
        }

        when (ordering) {

            ComposeOrdering.AFTER_COMPOSE ->
                // Fail the build rather than transform. Inserting composable
                // calls after Compose has lowered produces code that compiles
                // and then misbehaves at run time, which is far worse than a
                // build failure naming the fix.
                messageCollector.report(CompilerMessageSeverity.ERROR, wrongOrderMessage())

            ComposeOrdering.BEFORE_COMPOSE ->
                messageCollector.report(
                    CompilerMessageSeverity.INFO,
                    "Dootah: ${bundlable.size} @Bundlable function(s), running before Compose",
                )

            ComposeOrdering.INCONCLUSIVE ->
                messageCollector.report(
                    CompilerMessageSeverity.INFO,
                    "Dootah: no @Bundlable functions in this module",
                )
        }
    }
}

private fun org.jetbrains.kotlin.ir.declarations.IrSimpleFunction.reportName(): String =
    fqNameWhenAvailable?.asString() ?: name.asString()
