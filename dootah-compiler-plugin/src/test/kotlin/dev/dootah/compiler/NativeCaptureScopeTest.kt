package dev.dootah.compiler

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A value in the prologue does not put its initializer's nested scopes in the screen's scope. */
class NativeCaptureScopeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `a stored content lambda keeps its nested callback parameter in scope`() {
        assertScoped("""
            val content: @Composable () -> Unit = {
                Entries { type -> Choice(onClick = { consume(type) }) }
            }
            Text("remote sibling")
            content()
        """)
    }

    @Test fun `nested state destructuring and a local function retain their own scope`() {
        assertScoped("""
            val content: @Composable () -> Unit = {
                Entries { value ->
                    val (first, second) = value to value + 1
                    var selected by remember { mutableStateOf(first) }
                    fun choose() { selected = second }
                    Choice(onClick = { choose() })
                }
            }
            Text("remote sibling")
            content()
        """)
    }

    @Test fun `nested extension receivers are retained with their complete region`() {
        assertScoped("""
            val content: @Composable () -> Unit = {
                Entries { value ->
                    with(Scope(value)) {
                        Choice(onClick = { consume(number) })
                    }
                }
            }
            Text("remote sibling")
            content()
        """)
    }

    @Test fun `a local generic function with defaults and a dispatch receiver keeps its declarations`() {
        assertScoped("""
            val content: @Composable () -> Unit = {
                Entries { value ->
                    class Local(val number: Int) {
                        fun <T> forward(input: T, action: (T) -> Unit) { action(input) }
                        fun choose(extra: Int = value) { forward(number + extra) { consume(it) } }
                    }
                    val local = Local(value)
                    Choice(onClick = { local.choose() })
                }
            }
            Text("remote sibling")
            content()
        """)
    }

    @Test fun `conditionally created nested callbacks keep captured parameters`() {
        assertScoped("""
            val content: @Composable () -> Unit = {
                Entries { value ->
                    Choice(onClick = if (value > 0) { { consume(value) } } else { { consume(-value) } })
                }
            }
            Text("remote sibling")
            content()
        """)
    }

    private fun assertScoped(body: String) {
        val result = compileWithDootah(
            workingDirectory = temporary.newFolder(),
            sources = listOf(SourceFile("Capture.kt", """
                package com.example
                import androidx.compose.runtime.*
                import androidx.compose.material3.Text

                class Scope(val number: Int)
                fun consume(value: Int) {}
                @Composable fun Entries(content: @Composable (Int) -> Unit) { content(1) }
                @Composable fun Choice(onClick: () -> Unit) {}

                @Composable fun Screen() {
                    $body
                }
            """.trimIndent())),
        )
        assertTrue("compilation failed: ${result.messages}", result.succeeded)
        assertTrue(result.interceptedScreens().contains("com.example.Screen"))
        val contract = result.installedContractFragment()
        // The complete region owns its nested declarations and remains available.
        assertTrue(contract, contract.contains("!com.example.Entries@"))
        // This smaller region would capture a declaration whose owner is absent.
        org.junit.Assert.assertFalse(contract, contract.contains("!com.example.Choice@"))
    }
}
