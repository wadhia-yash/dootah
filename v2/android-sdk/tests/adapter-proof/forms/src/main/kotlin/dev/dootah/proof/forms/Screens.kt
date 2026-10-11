package dev.dootah.proof.forms

import androidx.compose.runtime.Composable

@Composable
fun FilterChip(label: String, count: Int, selected: Boolean) {
    when {
        selected -> Chip(label, count = count, selected = selected)
        else -> Chip(label = "$label ($count)")
    }
}

@Composable
fun Progress(done: Int) = Meter(value = done, max = Limits.MAX)

@Composable
fun Inbox(count: Int, flagged: Boolean) {
    Panel(title = "Inbox") {
        Chip(label = "Unread", count = count)
        if (flagged) Indented(label = "flagged")
        Meter(value = count)
    }
}
