package ui

sealed interface BundleNode

data class ColumnNode(
    val children: List<BundleNode>
) : BundleNode

data class TextNode(
    val text: String
) : BundleNode

data class ButtonNode(
    val text: String,
    val action: String
) : BundleNode
