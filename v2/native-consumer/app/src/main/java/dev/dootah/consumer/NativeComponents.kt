package dev.dootah.consumer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// An app-owned component, reached by an update only through its build-generated native adapter.
@Composable fun StatusChip(label: String, count: Int = 0, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.tertiary) {
    Text("$label: $count", color = color)
}
@Composable fun InboxBadge(count: Int) {
    if (count > 0) {
        StatusChip(label = "Inbox", count = count)
    }
}
