package dev.dootah.compiler.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import java.io.File

/**
 * Lowers every `@Bundlable` function during the extraction pass.
 *
 * A checker is the extension point that hands a plugin a fully resolved body
 * together with the session that resolved it, which is exactly what lowering
 * needs. It runs only in the extraction pass, so nothing here affects the host
 * app's own compilation or the APK it produces.
 */
internal class BundlableExtractionChecker(
    private val reportDirectory: File,
    private val generatedDirectory: File?,
) : FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {

        if (!declaration.isBundlable()) return

        val body = declaration.body ?: return
        val screenId = declaration.dootahScreenId()

        // An observation of the resolved body, kept separate from the lowering.
        // It records what the compiler saw rather than what could be bundled,
        // which is the information a developer needs when a screen is rejected.
        writeExtractionReport(
            reportDirectory = reportDirectory,
            screenId = screenId,
            functionName = declaration.symbol.callableId.asSingleFqName().asString(),
            body = body.inspectScreenBody(),
        )

        val lowering = ScreenLowering(
            function = declaration,
            filePath = context.containingFilePath ?: "unknown",
        )

        when (val result = lowering.lower(screenId)) {

            is LoweringResult.Lowered -> {
                // Nothing to generate into during a validation-only run.
                generatedDirectory?.let { directory ->
                    writeGeneratedBundle(
                        generatedDirectory = directory,
                        reportDirectory = reportDirectory,
                        screen = result.screen,
                    )
                }
            }

            is LoweringResult.Rejected -> writeUnsupportedReport(
                reportDirectory = reportDirectory,
                screenId = screenId,
                reasons = result.reasons,
            )
        }
    }
}
