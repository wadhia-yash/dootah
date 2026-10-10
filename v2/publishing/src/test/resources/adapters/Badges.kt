package dev.dootah.fixture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// An app-owned component with defaults that read the caller's theme.
@Composable
fun CountBadge(
    text: String,
    color: Color = MaterialTheme.colorScheme.secondary,
    textColor: Color = MaterialTheme.colorScheme.onSecondary,
) {
    Text(text = text, color = textColor, modifier = Modifier.background(color).padding(horizontal = 3.dp, vertical = 1.dp))
}

@Composable
fun DownloadsBadge(count: Int) {
    if (count > 0) {
        CountBadge(text = "$count", color = MaterialTheme.colorScheme.tertiary)
    }
}
