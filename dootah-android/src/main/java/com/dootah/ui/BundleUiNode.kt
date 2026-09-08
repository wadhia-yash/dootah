package com.dootah.ui

sealed class BundleUiNode {

    data class Column(
        val children: List<BundleUiNode>
    ) : BundleUiNode()

    data class Text(
        val text: String
    ) : BundleUiNode()

    data class Button(
        val text: String,
        val action: String
    ) : BundleUiNode()
}
