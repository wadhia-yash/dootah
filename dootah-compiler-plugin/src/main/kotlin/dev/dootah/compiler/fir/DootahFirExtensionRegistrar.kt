package dev.dootah.compiler.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
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
) : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> DootahCheckersExtension(session, reportDirectory) }
    }
}

private class DootahCheckersExtension(
    session: FirSession,
    reportDirectory: File,
) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {

        override val simpleFunctionCheckers: Set<FirDeclarationChecker<FirNamedFunction>> =
            setOf(BundlableExtractionChecker(reportDirectory))
    }
}
