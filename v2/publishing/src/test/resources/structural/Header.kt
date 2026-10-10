package dev.dootah.fixture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal val HeaderPadding = 16.dp

@Composable
fun Header(title: String) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, top = 14.dp),
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = HeaderPadding),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
