package dev.dootah.compiler.fir

import dev.dootah.contract.Eligibility
import dev.dootah.contract.ScreenEligibility
import dev.dootah.contract.ScreenFilter
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import java.io.File

/**
 * Lowers every Compose function Dootah may take over, during the extraction pass.
 *
 * A checker is the extension point that hands a plugin a fully resolved body
 * together with the session that resolved it, which is exactly what lowering
 * needs. It runs only in the extraction pass, so nothing here affects the host
 * app's own compilation or the APK it produces.
 *
 * Which functions those are is decided by the shared eligibility rule rather
 * than by an annotation. The app's own compilation asks the same rule the same
 * question about the same declarations, and the two have to reach the same
 * answer: whatever it accepts here is what a published bundle will describe, and
 * whatever it accepts there is what the installed APK can render.
 */
internal class ScreenExtractionChecker(
    private val reportDirectory: File,
    private val generatedDirectory: File?,
    private val filter: ScreenFilter,
) : FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {

        val shape = declaration.composableShape(context)

        // Nothing is recorded for a function that is not Compose at all. The
        // denominator worth reporting is "this app's composables", and counting
        // every helper and extension against it would make the figure useless.
        if (!shape.isComposable) return

        when (val eligibility = ScreenEligibility.of(shape, filter)) {

            is Eligibility.Ineligible -> {
                writeDiscoveryRecord(
                    reportDirectory = reportDirectory,
                    fqName = shape.fqName,
                    outcome = DiscoveryOutcome.INELIGIBLE,
                    reason = eligibility.reason,
                )
                return
            }

            Eligibility.Eligible -> Unit
        }

        val body = declaration.body ?: return
        val screenId = declaration.dootahScreenId()

        // An observation of the resolved body, kept separate from the lowering.
        // It records what the compiler saw rather than what could be bundled,
        // which is the information a developer needs when a screen is rejected.
        writeExtractionReport(
            reportDirectory = reportDirectory,
            screenId = screenId,
            functionName = shape.fqName,
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

                writeDiscoveryRecord(
                    reportDirectory = reportDirectory,
                    fqName = shape.fqName,
                    outcome = DiscoveryOutcome.LOWERED,
                    adapters = result.screen.adapters.size,
                    shape = result.screen.remoteShape(),
                )

                // What stayed native is recorded beside what did not. These are
                // not errors and do not refuse anything; they are the answer to
                // "why did my edit here not take effect", which is the question
                // partial lowering makes it possible to ask.
                if (result.degraded.isNotEmpty()) {
                    writeDegradationReport(
                        reportDirectory = reportDirectory,
                        screenId = screenId,
                        regions = result.degraded,
                    )
                }
            }

            is LoweringResult.NotWorthShipping -> {
                writeDiscoveryRecord(
                    reportDirectory = reportDirectory,
                    fqName = shape.fqName,
                    outcome = DiscoveryOutcome.NOT_WORTH_SHIPPING,
                    adapters = result.screen.adapters.size,
                    shape = result.shape,
                )
            }

            is LoweringResult.Rejected -> {
                writeUnsupportedReport(
                    reportDirectory = reportDirectory,
                    screenId = screenId,
                    reasons = result.reasons,
                )

                writeDiscoveryRecord(
                    reportDirectory = reportDirectory,
                    fqName = shape.fqName,
                    outcome = DiscoveryOutcome.REJECTED,
                )
            }
        }
    }
}
