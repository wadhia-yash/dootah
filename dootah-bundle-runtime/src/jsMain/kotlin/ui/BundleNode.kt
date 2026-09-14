package ui

/**
 * The UI a bundle can describe.
 *
 * Mirrors the node types the installed renderer understands. Growing this set is
 * a breaking runtime change, which is why it is small and why each addition is
 * driven by a screen that needed it rather than by Compose's catalogue.
 */
sealed interface BundleNode

/**
 * A column, and whichever of alignment and arrangement it was written with.
 *
 * An `Alignment` computes a position rather than holding one, so there is
 * nothing in it a bundle could serialise. It names one of the values the
 * installed app already has, and the app resolves it. Null means the layout was
 * written without it: the app then leaves Compose's own default alone rather
 * than being told what this bundle believed that default to be.
 */
data class ColumnNode(
    val modifiers: List<BundleModifier> = emptyList(),
    val horizontalAlignment: String? = null,
    val verticalArrangement: ArrangementNode? = null,
    val children: List<BundleNode> = emptyList(),
) : BundleNode

data class RowNode(
    val modifiers: List<BundleModifier> = emptyList(),
    val verticalAlignment: String? = null,
    val horizontalArrangement: ArrangementNode? = null,
    val children: List<BundleNode> = emptyList(),
) : BundleNode

data class BoxNode(
    val modifiers: List<BundleModifier> = emptyList(),
    val contentAlignment: String? = null,
    val children: List<BundleNode> = emptyList(),
) : BundleNode

/**
 * One arrangement: a name, and for `spacedBy` alone the gap in dp.
 *
 * The JS mirror of the contract's `LayoutArrangement`. Two declarations rather
 * than a shared one for the same reason the node models are duplicated: the two
 * sides are versioned independently and the only contract between them is the
 * JSON.
 */
data class ArrangementNode(
    val token: String,
    val spacing: DimensionNode? = null,
)

/**
 * A length: a number this bundle chose, or a name the installed app resolves.
 *
 * The anchored case is how a bundle uses the app's own spacing scale --
 * `MaterialTheme.padding.small` -- without carrying its value. The number stays
 * in the APK, so a screen described here keeps following the app's spacing when
 * a later release retunes it, instead of freezing whatever it was on the day
 * the bundle was built.
 */
data class DimensionNode(
    val value: Double? = null,
    val anchor: String? = null,
)

data class TextNode(
    val text: String,
    val modifiers: List<BundleModifier> = emptyList(),
) : BundleNode

data class ButtonNode(
    val text: String,
    val action: String,
    val modifiers: List<BundleModifier> = emptyList(),
) : BundleNode

/**
 * Several nodes drawn in place, with no layout around them.
 *
 * A screen is not required to be one layout. Where it is several components in a
 * row, whatever the caller wrapped the call in is what lays them out, so a
 * fragment adds nothing of its own -- wrapping them in a Column instead would
 * change how the screen looks the moment a bundle first drew it.
 */
data class FragmentNode(
    val children: List<BundleNode> = emptyList(),
) : BundleNode

/**
 * An instance of a native composable that shipped in the APK.
 *
 * The bundle decides which adapter to place, how many, where, in what order, and
 * what to give each one -- none of which the installed app has to have
 * anticipated, because an adapter is a reusable entry point rather than a copy
 * of one call. A bundle needs a new APK only when it names an adapter, an
 * action, a value or a resource that binary genuinely does not contain.
 *
 * [children] is keyed by the parameter that takes the content, because a
 * component may have more than one content slot and the bundle has to say which
 * is which.
 */
data class ComponentNode(
    val adapter: String,
    val props: Map<String, PropNode> = emptyMap(),
    val children: Map<String, List<BundleNode>> = emptyMap(),
) : BundleNode
