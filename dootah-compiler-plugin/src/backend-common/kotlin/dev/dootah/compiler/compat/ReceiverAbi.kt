package dev.dootah.compiler.compat

import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

// FIR changed the accessor's declared return type, and later represented the
// receiver as a separate symbol. Normalize both public API layouts here.
private val boundSymbolGetter = FirThisReference::class.java.getMethod("getBoundSymbol")
internal fun boundThisSymbol(reference: FirThisReference): FirBasedSymbol<*>? =
    boundSymbolGetter.invoke(reference) as FirBasedSymbol<*>?

private val receiverSymbolClass = try {
    Class.forName("org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol", false,
        FirBasedSymbol::class.java.classLoader)
} catch (_: ClassNotFoundException) { null }
private val receiverDeclaration = receiverSymbolClass?.getMethod("getContainingDeclarationSymbol")

internal fun receiverOwner(symbol: FirBasedSymbol<*>): FirBasedSymbol<*>? =
    if (receiverSymbolClass?.isInstance(symbol) == true)
        receiverDeclaration!!.invoke(symbol) as FirBasedSymbol<*> else null
