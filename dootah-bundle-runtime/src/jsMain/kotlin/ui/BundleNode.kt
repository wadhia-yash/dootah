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
 * A hole in the remote tree filled by a composable already inside the APK.
 *
 * The bundle names a slot and nothing more. What it draws, and every value it
 * closes over, stay on the Android side, so a native component can appear inside
 * a remotely described screen without its arguments crossing the boundary.
 */
data class NativeSlotNode(
    val slot: String,
) : BundleNode
