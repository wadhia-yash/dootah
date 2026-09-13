package ui

/**
 * The UI a bundle can describe.
 *
 * Mirrors the node types the installed renderer understands. Growing this set is
 * a breaking runtime change, which is why it is small and why each addition is
 * driven by a screen that needed it rather than by Compose's catalogue.
 */
sealed interface BundleNode

data class ColumnNode(
    val modifiers: List<BundleModifier> = emptyList(),
    val children: List<BundleNode> = emptyList(),
) : BundleNode

data class RowNode(
    val modifiers: List<BundleModifier> = emptyList(),
    val children: List<BundleNode> = emptyList(),
) : BundleNode

data class BoxNode(
    val modifiers: List<BundleModifier> = emptyList(),
    val children: List<BundleNode> = emptyList(),
) : BundleNode

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
