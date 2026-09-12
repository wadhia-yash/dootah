package dev.dootah.compiler.model

/**
 * The UI a bundle can describe.
 *
 * Mirrors the node types the installed renderer understands, so a lowered screen
 * cannot describe something the app could not draw.
 */
internal sealed interface BundleUi {

    data class ColumnUi(
        val modifiers: List<BundleModifier>,
        val children: List<BundleUi>,
    ) : BundleUi

    data class RowUi(
        val modifiers: List<BundleModifier>,
        val children: List<BundleUi>,
    ) : BundleUi

    data class BoxUi(
        val modifiers: List<BundleModifier>,
        val children: List<BundleUi>,
    ) : BundleUi

    data class TextUi(
        val text: BundleExpression,
        val modifiers: List<BundleModifier>,
    ) : BundleUi

    /** A button and the action name the app sends back when it is tapped. */
    data class ButtonUi(
        val label: BundleExpression,
        val action: String,
        val modifiers: List<BundleModifier>,
    ) : BundleUi

    /**
     * A composable that stays in the APK, rendered where the bundle says.
     *
     * How a screen keeps using components Dootah cannot describe -- an icon, a
     * themed text, an app's own card -- without Dootah having to reimplement
     * them. The bundle carries the slot's identity and nothing else.
     */
    data class NativeSlotUi(val slot: String) : BundleUi

    /**
     * Several components in a row, with no layout around them.
     *
     * A screen body is not required to be one layout. `ToolBoxContent` in Cahier
     * is three siblings, laid out by whichever Column or Row the caller put
     * around the call -- so wrapping them in a Column here would change how the
     * screen looks the first time a bundle rendered it. A fragment draws its
     * children where the screen itself sits and adds nothing of its own.
     */
    data class FragmentUi(val children: List<BundleUi>) : BundleUi

    /**
     * `if` / `else` around UI, and what a `when` over UI lowers to.
     *
     * Branches are lists because a branch may contribute no children or several,
     * which a single node could not express.
     */
    data class ConditionalUi(
        val condition: BundleExpression,
        val ifTrue: List<BundleUi>,
        val ifFalse: List<BundleUi>,
    ) : BundleUi
}

/**
 * A layout instruction, in the order it was written.
 *
 * Order is preserved end to end because Compose modifiers are order-sensitive:
 * padding before a background paints differently from padding after it.
 */
internal sealed interface BundleModifier {

    /** The `Modifier` the screen's caller passed in. */
    data object Inherited : BundleModifier

    data class Padding(
        val start: Double,
        val top: Double,
        val end: Double,
        val bottom: Double,
    ) : BundleModifier

    data class FillMaxWidth(val fraction: Double) : BundleModifier

    data class FillMaxHeight(val fraction: Double) : BundleModifier

    data class FillMaxSize(val fraction: Double) : BundleModifier

    data class Size(val width: Double, val height: Double) : BundleModifier

    data class Width(val value: Double) : BundleModifier

    data class Height(val value: Double) : BundleModifier

    data class Weight(val value: Double) : BundleModifier

    data class Background(val color: Long) : BundleModifier
}
