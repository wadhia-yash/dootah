package dev.dootah.compiler.ir

import dev.dootah.compiler.COMPOSABLE_ANNOTATION
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName
import java.util.IdentityHashMap

/** A component that stays in the APK, and where the bundle refers to it. */
internal data class NativeSlot(
    val id: String,
    val call: IrCall,
)

/**
 * Finds the components of a screen that Dootah keeps native.
 *
 * The app registers one of these for every component it could plausibly be
 * asked to draw, and the bundle refers to the ones it actually used. Registering
 * a superset is deliberate: the two decisions are made by two separate
 * compilations, and an unused registration costs a little code, while a missing
 * one would be a hole in a rendered screen.
 *
 * Layouts are excluded because the extraction pass never turns one into a slot:
 * a layout holds the remote children, and a native copy of it would swallow the
 * part of the screen that is supposed to be updatable.
 *
 * A component is only a slot if it reads nothing declared inside the body. The
 * slot runs on the Android side, where a value the bundle computed does not
 * exist, so closing over one could not work -- and the extraction pass refuses
 * the same case rather than emitting a slot the app cannot supply.
 *
 * "Declared inside the body" includes the receiver a layout hands its content:
 * a registered slot is lifted out into its own lambda, so a component declared
 * on `ColumnScope`, or one whose modifier calls `weight`, has no receiver left
 * to read and cannot be built at all.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrBody.nativeSlots(layouts: Set<FqName>): List<NativeSlot> {

    val names = nativeSlotNames(this).names

    val declaredInBody = mutableSetOf<IrValueDeclaration>()

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (element is IrVariable) declaredInBody += element
            // A lambda's own parameters, including the receiver a layout gives
            // its content. A component that reads one cannot be hoisted out of
            // the lambda that introduced it.
            if (element is IrFunction) declaredInBody += element.parameters
            element.acceptChildrenVoid(this)
        }
    })

    val slots = mutableListOf<NativeSlot>()

    acceptVoid(object : IrVisitorVoid() {

        override fun visitElement(element: IrElement) {

            if (element is IrCall && element.isSlotCandidate(layouts, declaredInBody)) {
                names[element]?.let { id -> slots += NativeSlot(id = id, call = element) }
                // Not descending: a component kept native is kept whole, and a
                // component nested inside one is drawn by its parent.
                return
            }

            element.acceptChildrenVoid(this)
        }
    })

    return slots.distinctBy { it.id }
}

/**
 * Names every call in a screen body, the same way the extraction pass does.
 *
 * See [nativeSlotName] for why the name is what it is. The walk covers the whole
 * body, including inside components that will become slots, so that adding or
 * removing a slot does not renumber the others.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun nativeSlotNames(body: IrBody): SlotNaming {

    val counts = mutableMapOf<String, Int>()
    val composableShapes = mutableSetOf<String>()
    val names = IdentityHashMap<IrCall, String>()

    body.acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {

            if (element is IrCall) {
                val shape = nativeSlotShape(
                    callee = element.symbol.owner.name.asString(),
                    argumentNames = element.suppliedArgumentNames(),
                )
                val ordinal = counts.getOrElse(shape) { 0 }
                counts[shape] = ordinal + 1
                names[element] = nativeSlotName(shape, ordinal)

                if (element.symbol.owner.hasAnnotation(COMPOSABLE_ANNOTATION)) {
                    composableShapes += shape
                }
            }

            element.acceptChildrenVoid(this)
        }
    })

    return SlotNaming(names, counts.filterKeys { it in composableShapes })
}

/**
 * What every call in a screen body would be called, and how many of each there
 * are.
 *
 * The counts cover components only. A call's ordinal is its position among the
 * calls sharing its shape, so the count is what tells the app whether that
 * numbering still means the same thing -- and the two passes walk slightly
 * different trees for everything that is not a component, which would otherwise
 * make the tables disagree for no reason.
 */
internal class SlotNaming(
    val names: Map<IrCall, String>,
    val shapeCounts: Map<String, Int>,
)

/**
 * How many components of each shape this body has, as the app registers it.
 *
 * `shape=count`, comma-separated. Neither character occurs in a shape, which is
 * a callee name and its argument names joined by `(`, `|`, `)`.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrBody.componentShapeSpec(): String =
    nativeSlotNames(this).shapeCounts
        .entries
        .sortedBy { entry -> entry.key }
        .joinToString(",") { (shape, count) -> "$shape=$count" }

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrCall.isSlotCandidate(
    layouts: Set<FqName>,
    declaredInBody: Set<IrValueDeclaration>,
): Boolean {

    val callee = symbol.owner

    if (!callee.hasAnnotation(COMPOSABLE_ANNOTATION)) return false
    if ((callee.fqNameWhenAvailable ?: FqName.ROOT) in layouts) return false

    val declaredHere = mutableSetOf<IrValueDeclaration>()

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (element is IrVariable) declaredHere += element
            if (element is IrFunction) declaredHere += element.parameters
            element.acceptChildrenVoid(this)
        }
    })

    val outerLocals = declaredInBody - declaredHere

    var readsOuterLocal = false

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (element is IrGetValue && element.symbol.owner in outerLocals) {
                readsOuterLocal = true
            }
            element.acceptChildrenVoid(this)
        }
    })

    return !readsOuterLocal
}

/**
 * The arguments a call actually supplies, named and ordered so two passes agree.
 *
 * `arguments` is indexed over every parameter, receivers included, so a
 * parameter's index has to be taken before the receivers are filtered out. Doing
 * it the other way round reads the wrong argument slot for any callee with a
 * receiver -- the extraction pass builds the name from the resolved argument
 * mapping, which has no receivers in it, so the two passes would disagree and
 * the app would have no component under the name the bundle asks for.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrCall.suppliedArgumentNames(): List<String> =
    symbol.owner.parameters
        .withIndex()
        .filter { (index, parameter) ->
            parameter.kind == IrParameterKind.Regular && arguments.getOrNull(index) != null
        }
        .map { (_, parameter) -> parameter.name.asString() }

/**
 * What a component is, as a name that survives editing the screen around it.
 *
 * This name has to mean the same thing in two compilations of two *different*
 * versions of the source -- the one the installed APK was built from, and the
 * edited one a bundle is published from. That is the whole point of an update,
 * and it rules out anything positional.
 *
 * A source offset fails immediately: inserting a line anywhere above renames
 * every component below it, and on a device that looks like components silently
 * vanishing from a shipped screen. Counting per callee name fails more subtly --
 * adding one ordinary `Text` renumbers every styled `Text` -- and `Text` is the
 * most commonly added component there is.
 *
 * So a component is named by its call shape: what it is called and which
 * arguments it was given. Changing an argument's *value* keeps the name, which
 * is what should happen, because the APK's copy is the one that will draw either
 * way. Adding or removing an argument changes it, and the app then has no such
 * component -- reported rather than drawn as a gap.
 */
internal fun nativeSlotShape(callee: String, argumentNames: List<String>): String =
    "$callee(${argumentNames.sorted().joinToString(ARGUMENT_SEPARATOR)})"

/**
 * Separates argument names inside a shape.
 *
 * Not a comma. The compiler hands the app its slot names as one comma-separated
 * constant, so a comma inside a name splits it into pieces and the app builds
 * its slot table from fragments -- every lookup then misses, and every native
 * component silently disappears. Found exactly that way, on a device.
 */
private const val ARGUMENT_SEPARATOR = "|"

internal fun nativeSlotName(shape: String, ordinal: Int): String = "$shape#$ordinal"
