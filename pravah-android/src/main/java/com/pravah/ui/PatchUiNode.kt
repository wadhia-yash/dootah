package com.pravah.ui

sealed class PatchUiNode {

    data class Column(
        val children: List<PatchUiNode>
    ) : PatchUiNode()

    data class Text(
        val text: String
    ) : PatchUiNode()

    data class Button(
        val text: String,
        val action: String
    ) : PatchUiNode()
}