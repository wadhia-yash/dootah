package ui

sealed interface PatchNode

data class ColumnNode(
    val children: List<PatchNode>
) : PatchNode

data class TextNode(
    val text: String
) : PatchNode

data class ButtonNode(
    val text: String,
    val action: String
) : PatchNode