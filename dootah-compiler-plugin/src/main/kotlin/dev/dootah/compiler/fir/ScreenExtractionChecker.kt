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
/**
 * Lowers one screen, giving up a described value when that is what it costs to
 * keep a region rather than the whole screen.
 *
 * A screen is lowered once. If it came out rejected -- nothing publishable, the
 * app keeps its native body -- and something in it was refused for reading a
 * value the bundle had claimed, the claim is the cheaper thing to drop: lowering
 * runs again with that value left to the app, and the region that needed it can
 * then be kept exactly as written.
 *
 * Only on rejection, which is what keeps this from taking anything away. A
 * screen that lowered is already deciding more than the alternative would, so
 * there is nothing to trade. The loop ends when the run stops asking for names
 * it has not already been given, and there are only so many to give.
 */
internal fun lowerScreen(
    function: FirNamedFunction,
    filePath: String,
    screenId: String,
): LoweringResult {

    var keptNative = emptySet<String>()

    // Why the first run could not describe the screen, carried forward.
    //
    // Once a value is left to the app, everything that reads it is native too,
    // and the reason the *last* run reports is that read rather than the thing
    // that started it. A developer who wrote `checkout()` in a click handler
    // needs to be told about `checkout()`, not about the counter beside it.
    var firstRefusal = emptyList<UnsupportedConstruct>()

    while (true) {

        val lowering = ScreenLowering(function, filePath, keptNative)
        val result = lowering.lower(screenId)

        if (result !is LoweringResult.Rejected) return result.withEarlierRefusal(firstRefusal)

        if (keptNative.isEmpty()) firstRefusal = result.reasons

        val wanted = lowering.demotionCandidates - keptNative
        if (wanted.isEmpty()) return result

        keptNative = keptNative + wanted
    }
}

/** Adds the reasons an earlier run gave to what this one kept native. */
private fun LoweringResult.withEarlierRefusal(
    earlier: List<UnsupportedConstruct>,
): LoweringResult = when {
    earlier.isEmpty() -> this
    this is LoweringResult.Lowered -> copy(degraded = earlier + degraded)
    else -> this
}

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
        val filePath = context.containingFilePath
        val screenId = declaration.dootahScreenId(filePath)

        // An observation of the resolved body, kept separate from the lowering.
        // It records what the compiler saw rather than what could be bundled,
        // which is the information a developer needs when a screen is rejected.
        writeExtractionReport(
            reportDirectory = reportDirectory,
            screenId = screenId,
            functionName = shape.fqName,
            body = body.inspectScreenBody(),
        )

        when (
            val result = lowerScreen(
                function = declaration,
                filePath = filePath ?: "unknown",
                screenId = screenId,
            )
        ) {

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
