package dev.dootah.compiler.compat

import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.name.ClassId

/** Optional accessor bridge, resolved once against the HOST API, not its version.
 * FIR removed the direct classId accessor when it split qualifier/object symbols.
 * Keeping this tiny lookup here lets the same adapter binary serve both layouts.
 */
private val directClassId = FirResolvedQualifier::class.java.methods
    .singleOrNull { it.name == "getClassId" && it.parameterCount == 0 }
private val qualifierSymbol = if (directClassId == null) FirResolvedQualifier::class.java.methods
    .single { it.name == "getQualifierSymbol" && it.parameterCount == 0 } else null

internal fun qualifierClassId(qualifier: FirResolvedQualifier): ClassId? =
    if (directClassId != null) directClassId.invoke(qualifier) as ClassId?
    else (qualifierSymbol!!.invoke(qualifier) as FirClassLikeSymbol<*>?)?.classId
