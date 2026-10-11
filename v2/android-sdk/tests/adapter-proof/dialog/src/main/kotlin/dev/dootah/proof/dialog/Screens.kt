package dev.dootah.proof.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** An ordinary interactive hook: local Compose state, installed callbacks, a multi-slot dialog. */
@Composable
fun DeleteDialog(onDismissRequest: () -> Unit, onDelete: (Boolean) -> Unit) {
    var removeEverything by remember { mutableStateOf(false) }

    Dialog(
        title = {
            Label(text = "Remove")
        },
        text = {
            Stack(spacing = 4) {
                Label(text = "Also reset progress?")
                CheckRow(
                    label = "Reset",
                    checked = removeEverything,
                    onCheckedChange = { removeEverything = it },
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Action(onClick = {
                onDelete(removeEverything)
                onDismissRequest()
            }) {
                Label(text = "Remove")
            }
        },
        dismissButton = {
            Action(onClick = onDismissRequest) {
                Label(text = "Cancel")
            }
        },
    )
}
