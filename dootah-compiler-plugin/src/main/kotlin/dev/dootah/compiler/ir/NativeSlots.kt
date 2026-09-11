package dev.dootah.compiler.ir

import dev.dootah.compiler.COMPOSABLE_ANNOTATION
import org.jetbrains.kotlin.ir.IrElement
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
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrBody.nativeSlots(layouts: Set<FqName>): List<NativeSlot> {

    val declaredInBody = mutableSetOf<IrVariable>()

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (element is IrVariable) declaredInBody += element
            element.acceptChildrenVoid(this)
        }
    })

    val slots = mutableListOf<NativeSlot>()

    acceptVoid(object : IrVisitorVoid() {

        override fun visitElement(element: IrElement) {

            if (element is IrCall && element.isSlotCandidate(layouts, declaredInBody)) {
                slots += NativeSlot(id = nativeSlotId(element.startOffset), call = element)
                // Not descending: a component kept native is kept whole, and a
                // component nested inside one is drawn by its parent.
                return
            }

            element.acceptChildrenVoid(this)
        }
    })

    return slots.distinctBy { it.id }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrCall.isSlotCandidate(
    layouts: Set<FqName>,
    declaredInBody: Set<IrVariable>,
): Boolean {

    val callee = symbol.owner

    if (!callee.hasAnnotation(COMPOSABLE_ANNOTATION)) return false
    if ((callee.fqNameWhenAvailable ?: FqName.ROOT) in layouts) return false

    val declaredHere = mutableSetOf<IrVariable>()

    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (element is IrVariable) declaredHere += element
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
 * The slot name for a component kept native, derived from where it is written.
 *
 * A source position rather than a counter, because the same rule has to produce
 * the same name in two separate compilations: the extraction pass that writes
 * the bundle, and the app's own build that registers what each slot draws.
 */
internal fun nativeSlotId(sourceOffset: Int): String = "slot@$sourceOffset"
