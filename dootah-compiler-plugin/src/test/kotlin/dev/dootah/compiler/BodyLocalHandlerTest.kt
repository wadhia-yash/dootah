package dev.dootah.compiler

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A handler the screen's own body declares, handed to a component.
 *
 * Adapters and actions are lifted out of the body and into values prepared
 * before it runs, so neither may read anything the body declares. An argument
 * the adapter replaces is exempt -- whatever it was written as never reaches the
 * copy -- and that exemption is what makes most components describable at all.
 *
 * The exemption has a hole, and this is it. When the replacement cannot be
 * built, the original argument stays in the copy, and with it the read. The JVM
 * backend then fails with `Non-mapped local declaration`, long after Dootah has
 * finished and naming nothing that would lead anyone back to it. JetNews'
 * interests screen does exactly this with a destructured `rememberSaveable`.
 */
class BodyLocalHandlerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a handler declared in the body does not reach a lifted adapter`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(
                SourceFile(
                    name = "Screen.kt",
                    contents = """
                        package com.example

                        import androidx.compose.foundation.layout.Column
                        import androidx.compose.material3.Text
                        import androidx.compose.runtime.Composable
                        import androidx.compose.runtime.mutableStateOf
                        import androidx.compose.runtime.remember

                        @Composable
                        fun Tabs(index: Int, onPick: (Int) -> Unit, onOpen: () -> Unit) {}

                        @Composable
                        fun Screen() {
                            val pick: (Int) -> Unit = { }
                            val open: () -> Unit = { }
                            Column {
                                Tabs(index = 0, onPick = pick, onOpen = open)
                                Text("beside it")
                            }
                        }

                        @Composable
                        fun Destructured() {
                            val (index, setIndex) = remember { mutableStateOf(0) }
                            Column {
                                Tabs(index = index, onPick = setIndex, onOpen = { })
                                Text("beside it")
                            }
                        }
                    """.trimIndent(),
                )
            ),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)
    }
}
