package dev.dootah.compiler.fir

import dev.dootah.compiler.compat.*
import dev.dootah.contract.ScreenFilter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import java.io.File

/**
 * Registers Dootah's frontend extensions.
 *
 * Frontend rather than backend on purpose: FIR still carries the shape the
 * developer wrote -- `if`/`else` as a conditional, string templates as
 * concatenations -- while IR has already desugared both. Extraction has to emit
 * equivalent Kotlin, so it reads the representation closest to the source that
 * is still fully resolved.
 */
internal class DootahFirExtensionRegistrar(
    private val reportDirectory: File,
    private val generatedDirectory: File?,
    private val filter: ScreenFilter,
) : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession ->
            DootahCheckersExtension(
                session = session,
                reportDirectory = reportDirectory,
                generatedDirectory = generatedDirectory,
                filter = filter,
            )
        }
    }
}

private class DootahCheckersExtension(
    session: FirSession,
    reportDirectory: File,
    generatedDirectory: File?,
    filter: ScreenFilter,
) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {

        override val functionCheckers: Set<FirDeclarationChecker<org.jetbrains.kotlin.fir.declarations.FirFunction>> =
            setOf(
                ScreenExtractionChecker(
                    reportDirectory = reportDirectory,
                    generatedDirectory = generatedDirectory,
                    filter = filter,
                )
            )
    }
}
