package dev.dootah.proof.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** What the installed native components drew, in order, and the callbacks they were given. */
object Drawn {
    val calls = mutableListOf<String>()
    /** Dialog instances entering ("+") and leaving ("-") composition. */
    val lifecycle = mutableListOf<String>()
    val clicks = LinkedHashMap<String, () -> Unit>()
    var toggle: ((Boolean) -> Unit)? = null
    var dismiss: (() -> Unit)? = null
    fun clear() { calls.clear(); lifecycle.clear(); clicks.clear(); toggle = null; dismiss = null }
}

/** Several named content slots and a callback, like a Material dialog; default arguments stay Kotlin's. */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    title: @Composable () -> Unit = {},
    text: @Composable () -> Unit = {},
    dismissButton: @Composable () -> Unit = {},
    elevation: Int = 3,
) {
    Drawn.calls += "Dialog($elevation)"
    Drawn.dismiss = onDismissRequest
    DisposableEffect(Unit) { Drawn.lifecycle += "+"; onDispose { Drawn.lifecycle += "-" } }
    title(); text(); confirmButton(); dismissButton()
}

@Composable
fun Stack(spacing: Int = 0, content: @Composable () -> Unit) {
    Drawn.calls += "Stack($spacing)"
    content()
}

@Composable
fun Label(text: String) { Drawn.calls += "Label($text)" }

@Composable
fun Action(onClick: () -> Unit, content: @Composable () -> Unit) {
    var label = ""
    Drawn.calls += "Action"
    content()
    label = Drawn.calls.last()
    Drawn.clicks[label] = onClick
}

@Composable
fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Drawn.calls += "CheckRow($label,$checked)"
    Drawn.toggle = onCheckedChange
}
