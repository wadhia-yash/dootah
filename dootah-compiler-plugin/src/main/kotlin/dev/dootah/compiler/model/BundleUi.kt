package dev.dootah.compiler.model

import dev.dootah.contract.Dimension
import dev.dootah.contract.LayoutArrangement

/**
 * The UI a bundle can describe.
 *
 * Mirrors the node types the installed renderer understands, so a lowered screen
 * cannot describe something the app could not draw.
 */
internal sealed interface BundleUi {

    /**
     * Alignment and arrangement travel as names from a closed vocabulary, never
     * as the Compose objects: an `Alignment` computes a position rather than
     * holding one, so there is nothing in it to serialise. Null means the layout
     * was written without it, and the app leaves Compose's own default in place
     * rather than sending what it guessed the default was.
     */
    data class ColumnUi(
        val modifiers: List<BundleModifier>,
        val children: List<BundleUi>,
        val horizontalAlignment: String? = null,
        val verticalArrangement: LayoutArrangement? = null,
    ) : BundleUi

    data class RowUi(
        val modifiers: List<BundleModifier>,
        val children: List<BundleUi>,
        val verticalAlignment: String? = null,
        val horizontalArrangement: LayoutArrangement? = null,
    ) : BundleUi

    data class BoxUi(
        val modifiers: List<BundleModifier>,
        val children: List<BundleUi>,
        val contentAlignment: String? = null,
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
     * An instance of a native composable that shipped in the APK.
     *
     * How a screen keeps using components Dootah cannot describe -- an icon, a
     * themed text, an app's own card -- without Dootah having to reimplement
     * them.
     *
     * The bundle carries which adapter to place and what to give it, which is
     * what lets an update add, remove, reorder and repeat components freely. The
     * app decides what an adapter is, and supplies everything a bundle is not
     * allowed to name for itself: the objects behind the handles, the code
     * behind the capabilities, the numbers behind the resource keys.
     */
    data class ComponentUi(
        val adapterId: String,
        val props: Map<String, BundleProp> = emptyMap(),
        val children: Map<String, List<BundleUi>> = emptyMap(),

        /**
         * Slots the component builds rather than draws -- see [BundleEntry].
         *
         * Separate from [children] because they are a different kind of thing at
         * every layer: children are UI the app composes where it is told, and
         * entries are declarations the app performs against a scope only it can
         * make.
         */
        val entries: Map<String, List<BundleEntry>> = emptyMap(),
    ) : BundleUi

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
 * One declaration in a native container's builder.
 *
 * `LazyColumn`'s content is a sequence of these, and what a bundle controls is
 * which of them there are and in what order -- never how they are measured,
 * composed or recycled, which stays with Compose.
 */
internal sealed interface BundleEntry {

    /** `item { ... }`, whose content is ordinary bundle UI. */
    data class Item(val children: List<BundleUi>) : BundleEntry

    /**
     * Entries the app declares, kept exactly as written.
     *
     * `items(post.paragraphs) { Paragraph(it) }` and an app's own extension on
     * the scope both land here. The bundle names the region; the app performs it
     * against the real scope, so the objects it ranges over never leave the APK.
     */
    data class Region(val adapterId: String) : BundleEntry
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

    /**
     * Lengths are [Dimension], not numbers, because a length may be the app's
     * rather than the bundle's -- `MaterialTheme.padding.small` names a value
     * the APK computes. A fraction and a weight stay plain: both are ratios the
     * bundle decides for itself, and neither has an app-owned form.
     */
    data class Padding(
        val start: Dimension,
        val top: Dimension,
        val end: Dimension,
        val bottom: Dimension,
    ) : BundleModifier

    data class FillMaxWidth(val fraction: Double) : BundleModifier

    data class FillMaxHeight(val fraction: Double) : BundleModifier

    data class FillMaxSize(val fraction: Double) : BundleModifier

    data class Size(val width: Dimension, val height: Dimension) : BundleModifier

    data class Width(val value: Dimension) : BundleModifier

    data class Height(val value: Dimension) : BundleModifier

    data class Weight(val value: Double) : BundleModifier

    data class Background(val color: Long) : BundleModifier
}
