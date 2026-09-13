package dev.dootah.compiler

import org.junit.Assert.assertTrue
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
