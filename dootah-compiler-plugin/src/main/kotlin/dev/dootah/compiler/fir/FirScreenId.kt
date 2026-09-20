package dev.dootah.compiler.fir

import dev.dootah.compiler.compat.*
import dev.dootah.compiler.identity.screenIdOf
import org.jetbrains.kotlin.descriptors.Visibilities

/**
 * The identity extraction and interception have to agree on.
 *
 * The same rule the app's own pass applies, described in `screenIdOf`: the
 * fully qualified name, and the declaring file too when the function is not
 * visible by that name.
 */
internal fun FirNamedFunction.dootahScreenId(filePath: String?): String = screenIdOf(
    qualifiedName = symbol.callableId.asSingleFqName().asString(),
    visibleByName = status.visibility == Visibilities.Public,
    filePath = filePath,
)
