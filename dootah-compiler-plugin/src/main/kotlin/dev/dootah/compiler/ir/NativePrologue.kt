package dev.dootah.compiler.ir

import dev.dootah.compiler.compat.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

/**
 * A screen body split at the point where Dootah takes it over.
 *
 * [prologue] runs before the choice between the remote and the native
 * implementation; [body] runs only when the native one is chosen.
 */
internal class SplitBody(
    val prologue: List<IrStatement>,
    val body: List<IrStatement>,
)

/**
 * Splits a screen body into the declarations Dootah keeps in front of its own
 * code and the statements that stay behind it.
 *
 * Everything Dootah hands the runtime -- an adapter, an action, a region kept
 * exactly as written -- is an argument to the call that decides whether a remote
 * implementation exists, and that call has to come before the body it may
 * replace. So anything lifted out of the body is evaluated in front of it, and
 * until now that meant a lifted thing could read the screen's parameters and
 * nothing else. A screen that opens with `rememberLazyListState()` or
 * `LocalFoo.current` -- which is most screens -- had every native region below
 * it refused for reading a name that would not exist yet, and with no region
 * left to keep, the whole screen stayed native.
 *
 * Moving those declarations in front of the interception point is what removes
 * that. They are the app's own code either way; running them before the choice
 * rather than after it costs nothing, and it puts them in scope for both the
 * native fallback *and* the regions the remote implementation draws.
 *
 * ### Which declarations move
 *
 * The declarations at the top of the body, up to the first statement that
 * assigns to one of them.
 *
 * That condition is what makes moving them safe. A declaration can only depend
 * on the parameters and on the declarations before it, and both keep their
 * order -- so the only way moving one could change what it computes is for
 * something in between to have written to a local it reads. The first statement
 * that writes to any of them ends the prologue, and everything after it stays
 * where it was.
 *
 * The extraction pass applies the same rule to the same body, described in
 * `ScreenLowering`. The two need not agree exactly: a declaration this pass
 * moves and extraction does not is one more name extraction refuses to read,
 * which costs coverage and nothing else.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrBlockBody.splitAtPrologue(): SplitBody {

    val declared = mutableSetOf<IrValueDeclaration>()
    val writers = mutableSetOf<IrSimpleFunction>()

    statements.forEach { statement ->
        when (statement) {
            is IrVariable -> declared += statement
            is IrLocalDelegatedProperty -> {
                statement.delegate?.let { delegate -> declared += delegate }
                // `var open by remember { … }` is written through a generated
                // accessor rather than through a plain assignment, so looking
                // only for one would miss every delegated `var` there is.
                statement.setter?.let { writers += it }
            }
            else -> Unit
        }
    }

    val prologue = mutableListOf<IrStatement>()
    var open = true

    statements.forEach { statement ->
        when {
            !open -> Unit
            statement is IrVariable || statement is IrLocalDelegatedProperty -> prologue += statement
            statement.writesAny(declared, writers) -> open = false
            else -> Unit
        }
    }

    val hoisted = prologue.toSet()

    return SplitBody(
        prologue = prologue.toList(),
        body = statements.filterNot { statement -> statement in hoisted },
    )
}

/**
 * Declarations made visible at the interception point by moving these roots.
 *
 * This is deliberately not [declaredHere]: moving `val content = { ... }`
 * makes `content` visible, not the parameters, locals, receivers or functions
 * inside its initializer. Those declarations still belong to their nested
 * scopes. Treating them as prologue declarations lets a lifted region capture
 * a parameter whose owning lambda was left behind, producing `No mapping for
 * symbol` during JVM code generation. Copying cannot repair that reference:
 * its declaration is outside the copied region.
 *
 * A delegated property's storage and accessors are declarations in the same
 * scope as the property. Their bodies and parameters, like every initializer's
 * nested declarations, stay private to their respective owners.
 */
internal fun List<IrStatement>.declaredScope(): BodyScope {

    val values = mutableSetOf<IrValueDeclaration>()
    val functions = mutableSetOf<IrSimpleFunction>()

    forEach { statement ->
        when (statement) {
            is IrValueDeclaration -> values += statement
            is IrLocalDelegatedProperty -> {
                statement.delegate?.let { values += it }
                statement.getter?.let { functions += it }
                statement.setter?.let { functions += it }
            }
            else -> Unit
        }
    }

    return BodyScope(values = values, functions = functions)
}

/** This scope with everything [other] declares taken out of it. */
internal fun BodyScope.without(other: BodyScope): BodyScope = BodyScope(
    values = values - other.values,
    functions = functions - other.functions,
)

/**
 * Whether evaluating [this] writes to one of the body's own locals.
 *
 * A handler is not evaluating. `LaunchedEffect(key) { done = true }` and
 * `onClick = { open = !open }` both contain an assignment and neither performs
 * one where they are written -- one runs in a coroutine and the other on a tap,
 * long after the statement below them has had its value. Counting those ended
 * the prologue at the first effect or the first button in a screen, which on a
 * real app is the second or third line.
 *
 * Composable content is different: it runs as part of the composition this
 * statement is in, so an assignment inside one does happen here.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrElement.writesAny(
    values: Set<IrValueDeclaration>,
    writers: Set<IrSimpleFunction>,
): Boolean {

    var writes = false

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {

            if (element is IrFunctionExpression && !element.type.isComposableContent()) return

            if (element is IrSetValue && element.symbol.owner in values) writes = true
            if (element is IrCall && element.symbol.owner in writers) writes = true

            element.acceptChildrenVoid(this)
        }
    })

    return writes
}
