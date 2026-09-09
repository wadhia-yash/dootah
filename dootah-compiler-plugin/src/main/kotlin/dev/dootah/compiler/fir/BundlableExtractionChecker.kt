package dev.dootah.compiler.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import java.io.File

/**
 * Observes every `@Bundlable` function during the extraction pass.
 *
 * A checker is the extension point that hands a plugin a fully resolved body
 * together with the session that resolved it, which is exactly what extraction
 * needs. It runs only in the extraction pass, so nothing here affects the host
 * app's own compilation.
 */
internal class BundlableExtractionChecker(
    private val reportDirectory: File,
) : FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!declaration.isBundlable()) return

        val body = declaration.body ?: return

        writeExtractionReport(
            reportDirectory = reportDirectory,
            screenId = declaration.dootahScreenId(),
            functionName = declaration.symbol.callableId.asSingleFqName().asString(),
            body = body.inspectScreenBody(),
        )
    }
}
