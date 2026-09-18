package dev.dootah.compiler.fir

import org.jetbrains.kotlin.fir.declarations.FirNamedFunction

/**
 * The fully qualified function name shared by extraction and interception.
 * Renaming or moving a function changes its identity; ordinary body edits do not.
 */
internal fun FirNamedFunction.dootahScreenId(): String =
    symbol.callableId.asSingleFqName().asString()
