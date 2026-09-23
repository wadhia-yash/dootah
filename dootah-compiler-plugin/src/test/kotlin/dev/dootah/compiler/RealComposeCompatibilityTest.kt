package dev.dootah.compiler

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The same real Compose compiler pipeline runs on every candidate compiler. */
class RealComposeCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `interception runs before real Compose and generated calls are lowered`() {
        println("DOOTAH_ORDERING=" + if (supportsExplicitPluginOrdering()) "explicit" else "class-path")
        val result = compileWithDootah(temporary.root, listOf(SourceFile("Screen.kt", """
            package com.example
            import androidx.compose.runtime.*
            import androidx.compose.material3.*
            @Composable fun Screen(label: String, onClick: () -> Unit) {
                var expanded by remember { mutableStateOf(false) }
                Button(onClick = { expanded = !expanded; onClick() }) { Text(label) }
                if (expanded) Text("expanded")
            }
            @Composable fun Screen(count: Int) { Text("count") }
            class Owner(val label: String) {
                @Composable fun Content() { Text(label) }
            }
        """.trimIndent())), realCompose = true,
            dootahFirst = !supportsExplicitPluginOrdering())
        assertTrue(result.messages.joinToString("\n"), result.succeeded)
        val report = result.orderingReport().orEmpty()
        assertTrue(report, report.contains("ordering=BEFORE_COMPOSE"))
        assertTrue(report, report.contains("interceptedCount=3"))
        val bytecode = result.compiledClassText("com/example/ScreenKt.class")
        assertTrue(bytecode, bytecode.contains("androidx/compose/runtime/Composer"))
        assertTrue(bytecode, bytecode.contains("rememberDootahScreen"))
    }
}
