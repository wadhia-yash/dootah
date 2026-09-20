package dev.dootah.compiler.lowering

import dev.dootah.compiler.source.*
import dev.dootah.compiler.model.BundleEntry
import dev.dootah.compiler.model.BundleScreen
import dev.dootah.compiler.model.BundleUi
import dev.dootah.contract.FrozenRegionId

/**
 * How much of a screen the bundle actually decides.
 *
 * Partial lowering means almost anything will lower to *something*, so "did it
 * lower" stopped being a useful question. This is the one that replaced it: a
 * screen is worth publishing when an update to it could change what a person
 * sees, and these are the counts that say whether it could.
 *
 * The three kinds of node are three different amounts of control:
 *
 *  - [described] is UI the bundle owns outright -- a layout, a conditional, a
 *    piece of text. An update can change it into anything.
 *  - [placed] is a native component the bundle positions and supplies. An update
 *    can move it, repeat it, drop it, or change what it is given.
 *  - [frozen] is native code kept exactly as written. An update can only choose
 *    whether it appears and where.
 *
 * A screen of nothing but frozen regions is a faithful copy of itself with a
 * download in front of it, which is why the rule below exists.
 */
data class RemoteShape(
    val described: Int,
    val placed: Int,
    val frozen: Int,
    val conditionals: Int,
    val actions: Int,
    val states: Int,
    val imagePainters: Int = 0,
) {

    val nodes: Int get() = described + placed + frozen

    /**
     * Whether publishing this screen could change anything.
     *
     * A screen needs a visible decision the bundle can change.
     *
     * Either the bundle owns a decision of its own -- a layout it arranges, a
     * condition it evaluates, text it writes, an action it performs, state it
     * keeps -- or it holds more than one node, in which case it decides the
     * order and the number of them even if it wrote none of them. A supplied
     * image painter also qualifies: one image can visibly change on its own.
     *
     * The rule is stated as "something changeable", not as a proportion. A
     * screen that is one described node and nine frozen ones is ten per cent
     * remote and entirely worth shipping, because that one node is where the
     * change goes. The corpus bore this out: proportions cluster near zero on
     * exactly the screens that update fine.
     */
    val worthShipping: Boolean
        get() = described > 0 || conditionals > 0 || actions > 0 || states > 0 || nodes > 1 || imagePainters > 0

    /** Why not, in the words a build log should use. */
    fun refusal(): String =
        if (nodes == 0) "it describes nothing at all"
        else "all of it is native code kept as written, in one piece, so an " +
            "update could not change anything a person sees"
}

/** Counts what a lowered screen gives the bundle to decide. */
fun BundleScreen.remoteShape(): RemoteShape {

    var described = 0
    var placed = 0
    var frozen = 0
    var conditionals = 0
    var imagePainters = 0

    fun walk(node: BundleUi) {
        when (node) {

            is BundleUi.ComponentUi -> {
                if (FrozenRegionId.isFrozen(node.adapterId)) frozen++ else placed++
                // One native Image is enough to ship: changing its painter is
                // visible even when no layout or sibling changes. Frozen calls
                // have no remotely supplied props and never qualify this way.
                imagePainters += node.props.values.count { prop ->
                    prop is dev.dootah.compiler.model.BundleProp.Constant &&
                        prop.value is dev.dootah.contract.PropValue.PainterResourceValue
                }
                node.children.values.flatten().forEach(::walk)

                // A builder slot is a list the bundle owns. An `item` it
                // describes is a decision of its own -- it says the entry
                // exists and what is inside it -- and a region kept as written
                // is the same kind of thing as a frozen component: the bundle
                // chooses only whether and where.
                node.entries.values.flatten().forEach { entry ->
                    when (entry) {
                        is BundleEntry.Item -> { described++; entry.children.forEach(::walk) }
                        is BundleEntry.Region -> frozen++
                    }
                }
            }

            is BundleUi.ConditionalUi -> {
                conditionals++
                node.ifTrue.forEach(::walk)
                node.ifFalse.forEach(::walk)
            }

            is BundleUi.FragmentUi -> node.children.forEach(::walk)

            is BundleUi.ColumnUi -> { described++; node.children.forEach(::walk) }
            is BundleUi.RowUi -> { described++; node.children.forEach(::walk) }
            is BundleUi.BoxUi -> { described++; node.children.forEach(::walk) }

            is BundleUi.TextUi -> described++
            is BundleUi.ButtonUi -> described++
        }
    }

    walk(ui)

    return RemoteShape(
        described = described,
        placed = placed,
        frozen = frozen,
        conditionals = conditionals,
        actions = actions.size,
        imagePainters = imagePainters,
        states = prelude.count { statement ->
            statement is dev.dootah.compiler.model.BundleStatement.DeclareState
        },
    )
}
