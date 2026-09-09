package dev.dootah.compiler.identity

import dev.dootah.compiler.BUNDLABLE_ANNOTATION
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getAnnotationArgumentValue

/**
 * The identity the installed app and a published bundle use to agree on which
 * implementation is which.
 *
 * An explicit `@Bundlable("...")` id always wins. Otherwise the fully qualified
 * function name is used: stable across recompiles and across machines, unlike
 * anything the compiler generates, but tied to the function's name and package,
 * which is why the explicit form exists.
 */
internal fun IrSimpleFunction.dootahScreenId(): String {

    val explicitId = getAnnotationArgumentValue<String>(BUNDLABLE_ANNOTATION, ID_ARGUMENT)

    return if (!explicitId.isNullOrBlank()) explicitId
    else fqNameWhenAvailable?.asString() ?: name.asString()
}

private const val ID_ARGUMENT = "id"
