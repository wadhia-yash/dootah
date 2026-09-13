package dev.dootah.compiler

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A component given a value read off a top-level object.
 *
 * `FontWeight.Light`, `Icons.Outlined.Info`, an app's own `Theme.spacing` --
 * every app is full of them. Before reading an argument Dootah checks whether it
 * is a resource reference, which means asking what encloses the name it reads;
 * a top-level name is enclosed by nothing, and the root name throws when asked
 * for a short name.
 *
 * Thrown from a frontend checker the throw is not a Dootah error. It aborts the
 * analysis of the whole file, and in an app's own build it fails the build with
 * a stack trace naming a compiler internal. It took out most of a real app's
 * scan before being noticed, because the analysis simply stopped.
 */
class TopLevelObjectArgumentTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a component given a value off a top-level object is analysed, not thrown on`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(
                SourceFile(
                    name = "Tips.kt",
                    contents = """
                        package com.example

                        import androidx.compose.foundation.layout.Column
                        import androidx.compose.material3.Text
                        import androidx.compose.runtime.Composable

                        class Weight {
                            companion object {
                                val Light: Weight = Weight()
                            }
                        }

                        fun describe(weight: Weight): String = "w"

                        @Composable
                        fun Tips() {
                            Column {
                                // The argument is a *call* whose own first
                                // argument reads a top-level name -- the shape of
                                // `style = MaterialTheme.typography.labelLarge
                                // .copy(fontWeight = FontWeight.Light)`, which is
                                // what a real app writes.
                                Badge(label = describe(Weight.Light))
                            }
                        }

                        @Composable
                        fun Badge(label: String) {
                            Text(label)
                        }
                    """.trimIndent(),
                )
            ),
            mode = "extract",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        // The point is that analysis *finished*. Whether this particular screen
        // lowered is a separate question; a thrown checker answers neither.
        assertTrue(
            "the analysis did not run to completion: ${result.messages}",
            result.discoveredScreens().contains("com.example.Tips"),
        )
    }
}
