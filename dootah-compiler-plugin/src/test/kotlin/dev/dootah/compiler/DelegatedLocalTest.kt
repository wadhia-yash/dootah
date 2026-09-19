package dev.dootah.compiler

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A screen holding state through a delegated local.
 *
 * `var open by remember { mutableStateOf(false) }` is how most real screens hold
 * state, and it is the one shape where a read is invisible to a scan of the
 * expression doing the reading: the value arrives through an accessor the
 * compiler generated beside it, so the component's own subtree contains a call
 * and no mention of the variable at all.
 *
 * Lifting such a component into an adapter puts that read *before* the local is
 * declared, and the JVM backend fails with `Non-mapped local declaration` --
 * long after Dootah has finished, with nothing in the message naming Dootah.
 */
class DelegatedLocalTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a delegated state at the top of a screen is in scope for what the app registers`() {
        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(SourceFile("Picker.kt", """
                package com.example

                import androidx.compose.runtime.*
                import androidx.compose.material3.Text

                @Composable
                fun Picker(onConfirm: (Long, Long) -> Unit) {}

                @Composable
                fun Screen() {
                    var open by remember { mutableStateOf(false) }
                    Text("beside the picker")
                    Picker(onConfirm = { _, _ -> open = !open })
                }
            """.trimIndent())),
        )

        // `open` is declared at the top of the body, so it moves in front of
        // the interception point and the accessor the picker's handler calls is
        // declared before the handler is built. The app can therefore offer the
        // component instead of having to leave it out -- and, crucially, the
        // JVM backend accepts the result, which is what the old refusal was
        // there to guarantee.
        assertTrue("compilation failed: ${result.messages}", result.succeeded)
        assertTrue(result.interceptedScreens().contains("com.example.Screen"))
        val bytecode = result.compiledClassText("com/example/PickerKt.class")
        assertTrue(bytecode.contains("rememberDootahScreen"))
        assertTrue(bytecode.contains("com.example.Picker(onConfirm)"))
        assertTrue(result.installedContractFragment().contains("com.example.Picker(onConfirm)"))
        assertTrue(bytecode.contains("androidx.compose.material3.Text("))
    }

    /**
     * What the app offers and what a bundle takes are two different questions.
     *
     * The app can build the picker above, and does. A bundle still may not
     * place it, because placing it means supplying the handler -- and the
     * handler writes `open`, which the bundle would then be holding a second,
     * separate copy of. Extraction keeps the picker exactly as written instead,
     * and leaves `open` to the app.
     */
    @Test
    fun `a bundle keeps a component whose handler writes a value the app holds`() {
        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            mode = "extract",
            sources = listOf(SourceFile("Picker.kt", """
                package com.example

                import androidx.compose.runtime.*
                import androidx.compose.material3.Text

                @Composable
                fun Picker(onConfirm: (Long, Long) -> Unit) {}

                @Composable
                fun Screen() {
                    var open by remember { mutableStateOf(false) }
                    Text("beside the picker")
                    Picker(onConfirm = { _, _ -> open = !open })
                }
            """.trimIndent())),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        val generated = result.generatedScreen()

        assertTrue(generated, generated.contains("!com.example.Picker@"))
        assertFalse(generated, generated.contains("com.example.Picker(onConfirm)"))

        // The bundle does not keep a copy of the value the app's own handler
        // writes. It still owns the text beside it, which is the part an update
        // to this screen could change.
        assertFalse(generated, generated.contains("state.init"))
        assertTrue(generated, generated.contains("beside the picker"))
    }

    @Test
    fun `a conditional picker reading a delegated state and a parameter is buildable`() {
        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(SourceFile("ConditionalPicker.kt", """
                package com.example

                import androidx.compose.runtime.*
                import androidx.compose.material3.Text

                class Navigator {
                    fun navigate(start: Long, end: Long) {}
                }

                @Composable
                fun ModernDateRangePicker(onDismissRequest: () -> Unit, onConfirm: (Long, Long) -> Unit) {}

                @Composable
                fun Screen(navigator: Navigator) {
                    var showDateRangePicker by remember { mutableStateOf(false) }
                    Text("beside the picker")
                    if (showDateRangePicker) {
                        ModernDateRangePicker(
                            onDismissRequest = { showDateRangePicker = false },
                            onConfirm = { start, end ->
                                showDateRangePicker = false
                                navigator.navigate(start, end)
                            },
                        )
                    }
                }
            """.trimIndent())),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)
        assertTrue(result.interceptedScreens().contains("com.example.Screen"))
        val bytecode = result.compiledClassText("com/example/ConditionalPickerKt.class")
        assertTrue(bytecode.contains("rememberDootahScreen"))
        assertTrue(bytecode.contains("com.example.ModernDateRangePicker(onConfirm|onDismissRequest)"))
        assertTrue(result.installedContractFragment().contains("com.example.ModernDateRangePicker(onConfirm|onDismissRequest)"))
        assertTrue(bytecode.contains("androidx.compose.material3.Text("))
    }

    @Test
    fun `a frozen region can own its delegate and callback`() {
        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(SourceFile("Frozen.kt", """
                package com.example

                import androidx.compose.runtime.*
                import androidx.compose.foundation.layout.Column
                import androidx.compose.material3.Text

                @Composable
                fun Picker(onConfirm: (Long, Long) -> Unit) {}

                @Composable
                fun Screen() {
                    Column {
                        var open by remember { mutableStateOf(false) }
                        Picker(onConfirm = { _, _ -> open = !open })
                    }
                    Text("still updatable")
                }
            """.trimIndent())),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)
        val bytecode = result.compiledClassText("com/example/FrozenKt.class")
        assertTrue(bytecode.contains("rememberDootahScreen"))
        assertTrue(bytecode.contains("!androidx.compose.foundation.layout.Column@"))
        assertTrue(result.installedContractFragment().contains("!androidx.compose.foundation.layout.Column@"))
    }

    @Test
    fun `a component reading a delegated local is left where it is`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(
                SourceFile(
                    name = "Panel.kt",
                    contents = """
                        package com.example

                        import androidx.compose.foundation.layout.Column
                        import androidx.compose.material3.Button
                        import androidx.compose.material3.Text
                        import androidx.compose.runtime.Composable
                        import androidx.compose.runtime.getValue
                        import androidx.compose.runtime.mutableStateOf
                        import androidx.compose.runtime.remember
                        import androidx.compose.runtime.setValue

                        @Composable
                        fun Panel() {
                            var open by remember { mutableStateOf(false) }
                            Column {
                                Badge(label = if (open) "open" else "shut")
                                Button(onClick = { open = true }) { Text("open") }
                            }
                        }

                        @Composable
                        fun Badge(label: String) {
                            Text(label)
                        }
                    """.trimIndent(),
                )
            ),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)
    }
}
