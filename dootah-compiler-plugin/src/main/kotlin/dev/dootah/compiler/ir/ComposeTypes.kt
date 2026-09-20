package dev.dootah.compiler.ir

import dev.dootah.compiler.compat.*
import dev.dootah.compiler.COMPOSABLE_ANNOTATION
import dev.dootah.contract.BuilderScopes
import dev.dootah.contract.ComposeFunctionTypes
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.name.FqName

/**
 * What the Compose frontend leaves behind about a function-typed value.
 *
 * Composability is a property of the *type*, never of the lambda written for it.
 * A trailing `{ Text("open") }` arrives in IR as an ordinary local function
 * carrying no annotation of its own; the only surviving record that it is
 * composable is the type of the parameter it was passed to, and in Kotlin 2.3
 * that type is `ComposableFunction0<Unit>` rather than an annotated
 * `kotlin.Function0<Unit>`.
 *
 * Asking the lambda instead is how an icon button's content came to be
 * registered as an *action*, which put a composable `Text` call inside a plain
 * handler and failed the Compose backend with `Expected a $composer parameter`.
 * Every classification below therefore reads the type, and both passes over a
 * screen read it from here.
 */
internal fun IrType.isComposableFunctionType(): Boolean {

    val name = classFqName?.asString() ?: return false

    if (ComposeFunctionTypes.isComposableFunction(name)) return true

    return ComposeFunctionTypes.isFunction(name) && annotations.any { annotation ->
        annotation.type.classFqName == COMPOSABLE_ANNOTATION
    }
}

/**
 * Whether this parameter takes composable content rather than a value.
 *
 * Any arity, because the extra parameters are scopes a layout hands its
 * children -- `Button` takes `@Composable RowScope.() -> Unit`, and requiring
 * arity zero classified that as a *handler*, which copied the button's label
 * into a lambda with no composer to draw it with.
 */
internal fun IrType.isComposableContent(): Boolean =
    isComposableFunctionType() && returnsUnit()

/**
 * Whether this parameter is a builder slot rather than content or a handler.
 *
 * The mirror of the extraction pass's test, and it has to stay the mirror: a
 * type this pass treats as a builder and that one treats as a callback would
 * give the app an adapter that hands a Compose scope to a bundle's action.
 */
internal fun IrType.isDescribableBuilder(): Boolean {

    if (isComposableFunctionType()) return false
    if (!returnsUnit()) return false
    if (!hasReceiverScope()) return false

    val receiver = contentScopes().firstOrNull()?.classFqName?.asString() ?: return false

    return BuilderScopes.isDescribable(receiver)
}

/**
 * The scopes this content is handed, if any.
 *
 * A lambda standing in for content has to take them to be the type the
 * component asked for, even though the bundle's children cannot read one: a
 * component whose call reads its surrounding scope is refused by both passes.
 */
internal fun IrType.contentScopes(): List<IrType> =
    (this as? IrSimpleType)?.arguments.orEmpty().dropLast(1).mapNotNull { it.typeOrNull }

/** Whether the first of [contentScopes] is a receiver rather than a parameter. */
internal fun IrType.hasReceiverScope(): Boolean = annotations.any { annotation ->
    annotation.type.classFqName == EXTENSION_FUNCTION_TYPE
}

/**
 * How many arguments this takes, if it is a plain `(…) -> Unit` handler.
 *
 * Composable function types are excluded on purpose: content is drawn by the
 * bundle, and calling it as though it were an action is exactly the mistake
 * this file exists to prevent.
 */
internal fun IrType.unitFunctionArity(): Int? {

    if (isComposableFunctionType()) return null

    val arity = functionArity() ?: return null

    return arity.takeIf { returnsUnit() }
}

internal fun IrType.functionArity(): Int? {

    val name = classFqName?.asString() ?: return null

    return ComposeFunctionTypes.arityOf(name)
}

private fun IrType.returnsUnit(): Boolean =
    (this as? IrSimpleType)?.arguments?.lastOrNull()?.typeOrNull?.classFqName == UNIT

private val UNIT = FqName("kotlin.Unit")
private val EXTENSION_FUNCTION_TYPE = FqName("kotlin.ExtensionFunctionType")
