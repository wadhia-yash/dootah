package dev.dootah.compiler.compat

import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.symbols.impl.FirFileSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol

// The context moved from declarations to symbols without changing their meaning.
// Resolve the optional file accessor once; the same binary covers both layouts.
private val containingFileGetter = CheckerContext::class.java.methods
    .firstOrNull { it.name == "getContainingFileSymbol" && it.parameterCount == 0 }
    ?: CheckerContext::class.java.getMethod("getContainingFile")

internal fun CheckerContext.fileAnnotations() = when (val file = containingFileGetter.invoke(this)) {
    is FirFileSymbol -> file.fir.annotations
    is FirFile -> file.annotations
    else -> emptyList()
}

internal fun CheckerContext.enclosingClassAnnotations() = (containingDeclarations as List<*>).flatMap { owner ->
    when (owner) {
        is FirRegularClassSymbol -> owner.fir.annotations
        is FirRegularClass -> owner.annotations
        else -> emptyList()
    }
}
