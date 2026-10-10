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
