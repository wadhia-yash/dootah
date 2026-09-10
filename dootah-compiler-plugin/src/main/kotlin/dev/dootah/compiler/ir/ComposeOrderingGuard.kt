package dev.dootah.compiler.ir

import dev.dootah.compiler.COMPOSER_CLASS
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.classFqName

/**
 * The synthetic parameter name the Compose compiler uses when it lowers a
 * composable function.
 *
 * Kept as a secondary signal only. The parameter's type is the primary one
 * because a name is a convention the Compose compiler could change, whereas a
 * lowered composable must take a `Composer` to be callable at all.
 */
private const val COMPOSER_PARAMETER_NAME = "\$composer"

/**
 * Where Dootah's IR transform sits relative to the Compose compiler's lowering.
 *
 * Dootah inserts ordinary composable calls and expects Compose to lower them
 * afterwards. If Compose has already run, that lowering will never happen and
 * the inserted code would be miscompiled rather than rejected -- so this is
 * checked, not assumed.
 */
internal enum class ComposeOrdering {

    /** Compose has not lowered yet. The only order Dootah can transform in. */
    BEFORE_COMPOSE,

    /** Compose already lowered. Transforming now would produce broken code. */
    AFTER_COMPOSE,

    /**
     * Nothing in this module reveals the order.
     *
     * Reported rather than guessed. With no `@Bundlable` function present there
     * is also nothing to transform, so this is not an error.
     */
    INCONCLUSIVE,
}

/**
 * Decides the ordering from the shape of the functions Dootah is about to
 * transform.
 *
 * Deliberately based on the `@Bundlable` functions themselves rather than on any
 * composable in the module: those are the declarations that will be rewritten,
 * so their state is the one that matters.
 */
internal fun composeOrderingOf(
    bundlableFunctions: List<BundlableFunction>,
): ComposeOrdering {

    if (bundlableFunctions.isEmpty()) return ComposeOrdering.INCONCLUSIVE

    val anyAlreadyLowered = bundlableFunctions.any { it.function.carriesComposerParameter() }

    return if (anyAlreadyLowered) ComposeOrdering.AFTER_COMPOSE
    else ComposeOrdering.BEFORE_COMPOSE
}

/**
 * True once Compose has given [this] the composer it needs to run.
 *
 * A composable function has no `Composer` parameter as written; Compose's
 * lowering adds one. Its presence therefore means the lowering already happened.
 */
private fun IrSimpleFunction.carriesComposerParameter(): Boolean =
    parameters.any { parameter ->
        parameter.type.classFqName == COMPOSER_CLASS ||
            parameter.name.asString() == COMPOSER_PARAMETER_NAME
    }

/** The build failure text used when the plugin order is wrong. */
internal fun wrongOrderMessage(): String =
    "Dootah must run before the Compose compiler, but Compose has already " +
        "lowered this module's composable functions.\n" +
        "Declare Dootah first in the app's plugins block:\n" +
        "    plugins {\n" +
        "        id(\"dev.dootah\")\n" +
        "        id(\"org.jetbrains.kotlin.plugin.compose\")\n" +
        "    }"
