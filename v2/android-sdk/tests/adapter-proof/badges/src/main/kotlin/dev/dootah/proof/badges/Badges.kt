package dev.dootah.proof.badges

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** Stands in for an app-owned component such as a badge: defaults read the composition. */
val LocalPalette = compositionLocalOf { "secondary" }
val Rendered = mutableListOf<String>()

@Composable
fun paletteDefault(): String = LocalPalette.current

@Composable
fun Badge(text: String, color: String = paletteDefault(), weight: Int = 400, shape: String = "rect") {
    Rendered += "Badge($text,$color,$weight,$shape)"
}

@Composable
internal fun DownloadsBadge(count: Int) {
    if (count > 0) {
        Badge(text = "$count", shape = FrozenShape)
    }
}

@Composable
internal fun LabelBadge(label: String, count: Int) {
    if (count > 0) Badge(label, color = "tertiary")
    Badge(label, weight = 700)
    Badge(text = label, shape = FrozenShape, weight = 700)
}

internal const val FrozenShape = "pill"
