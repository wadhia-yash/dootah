package dev.dootah.compiler.identity

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

/**
 * The fully qualified function name shared by the installed app and its bundle.
 * Stable across recompiles and body edits, but tied to the function name and package.
 */
internal fun IrSimpleFunction.dootahScreenId(): String =
    fqNameWhenAvailable?.asString() ?: name.asString()
