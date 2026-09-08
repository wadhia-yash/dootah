package com.dootah.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun BundleRenderer(
    node: BundleUiNode,
    onAction: (String) -> Unit
) {

    when (node) {

        is BundleUiNode.Column -> {

            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                node.children.forEach { child ->
                    BundleRenderer(
                        node = child,
                        onAction = onAction
                    )
                }
            }
        }

        is BundleUiNode.Text -> {

            Text(
                text = node.text
            )
        }

        is BundleUiNode.Button -> {

            Button(
                onClick = {
                    onAction(node.action)
                }
            ) {
                Text(node.text)
            }
        }
    }
}
