package dev.dootah.proof.forms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalTone = staticCompositionLocalOf { "neutral" }
val Calls = mutableListOf<String>()

@Composable
fun Chip(label: String, count: Int = 0, selected: Boolean = false, tone: String = LocalTone.current) {
    Calls += "Chip($label,$count,$selected,$tone)"
}

@Composable
fun Meter(value: Int, max: Int = 100) {
    Calls += "Meter($value/$max)"
}

object Limits { const val MAX = 10 }

/** A container whose content lambda has a receiver, like RowScope/ColumnScope. */
interface PanelScope { val depth: Int }
private object DefaultPanel : PanelScope { override val depth = 1 }

@Composable
fun Panel(title: String = "panel", content: @Composable PanelScope.() -> Unit) {
    Calls += "Panel($title)"
    DefaultPanel.content()
}

/** Resolvable only inside a PanelScope content lambda. */
@Composable
fun PanelScope.Indented(label: String) {
    Calls += "Indented($depth,$label)"
}
