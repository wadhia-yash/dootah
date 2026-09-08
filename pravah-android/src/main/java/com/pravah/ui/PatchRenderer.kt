package com.pravah.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PatchRenderer(
    node: PatchUiNode,
    onAction: (String) -> Unit
) {

    when (node) {

        is PatchUiNode.Column -> {

            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                node.children.forEach { child ->
                    PatchRenderer(
                        node = child,
                        onAction = onAction
                    )
                }
            }
        }

        is PatchUiNode.Text -> {

            Text(
                text = node.text
            )
        }

        is PatchUiNode.Button -> {

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