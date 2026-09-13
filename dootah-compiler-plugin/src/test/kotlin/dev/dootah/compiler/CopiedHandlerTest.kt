package dev.dootah.compiler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A handler is copied out of the screen and kept in the APK, so the bundle can
 * ask for it by name. What is copied is the lambda's *statements*, into a new
 * function -- and every lambda body ends in a return to the lambda it came from.
 *
 * Left pointing at the original, that return is a return out of a function the
 * copy is not inside. The Kotlin backend emits it as a placeholder for the
 * inliner to resolve, the inliner never sees it, and the placeholder reaches
 * `dexBuilderDebug`, which fails with a class name no human wrote:
 * `Method name '<anonymous>' in class '${'$'}${'$'}${'$'}${'$'}${'$'}NON_LOCAL_RETURN${'$'}${'$'}${'$'}${'$'}${'$'}'`.
 */
class CopiedHandlerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `an empty handler copies to something the backend can emit`() {
        assertEmittable(handler = "{ _, _ -> }")
    }

    @Test
    fun `a handler that returns early copies to something the backend can emit`() {
        assertEmittable(handler = "{ a, _ -> if (a == 0) return@Surface; }")
    }

    private fun assertEmittable(handler: String) {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(
                SourceFile(
                    name = "Panel.kt",
                    contents = """
                        package com.example

                        import androidx.compose.foundation.layout.Column
                        import androidx.compose.material3.Text
                        import androidx.compose.runtime.Composable

                        @Composable
                        fun Panel() {
                            Column {
                                Surface(onErase = $handler, label = "x")
                            }
                        }

                        @Composable
                        fun Surface(onErase: (Int, Int) -> Unit, label: String) {
                            Text(label)
                        }
                    """.trimIndent(),
                )
            ),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        // The placeholder survives into the class file, so the class file is
        // where it has to be looked for: the compilation itself succeeds, and
        // the failure only appears when the app is packaged.
        assertFalse(
            "the copied handler still returns to the lambda it came from",
            result.compiledClassText("com/example/PanelKt.class")
                .contains("NON_LOCAL_RETURN"),
        )
    }
}
