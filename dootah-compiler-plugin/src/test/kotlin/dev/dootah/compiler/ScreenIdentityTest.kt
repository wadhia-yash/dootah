package dev.dootah.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Two screens must never share an identity.
 *
 * A bundle is matched to a screen by name. If two screens answer to the same
 * name, the app registers one screen's components under the other's name and a
 * bundle describing one is offered to both -- and the only thing standing
 * between that and drawing the wrong screen is the two happening to need
 * different components.
 *
 * A fully qualified name is enough for a function that is visible by it. A
 * `private` one is not: two files in a package may each declare
 * `private fun StatItem(…)` and Kotlin is perfectly happy, because they cannot
 * see each other. Found on a real application, which had exactly that.
 */
class ScreenIdentityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun screen(name: String, label: String) = SourceFile(name, """
        package com.example

        import androidx.compose.foundation.layout.Column
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable

        @Composable
        private fun StatItem() {
            Column {
                Text("$label")
                Text("$label again")
            }
        }

        @Composable
        fun Host$label() {
            StatItem()
        }
    """.trimIndent())

    @Test
    fun `two private screens with one qualified name are two screens`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            mode = "extract",
            sources = listOf(screen("Left.kt", "Left"), screen("Right.kt", "Right")),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        val ids = result.generatedSources()
            .filterKeys { it.startsWith("DootahScreen_") }
            .values
            .mapNotNull { source -> SCREEN_ID.find(source)?.groupValues?.get(1) }
            .filter { id -> id.startsWith("com.example.StatItem") }

        assertEquals("both screens should be described: $ids", 2, ids.size)
        assertEquals("they must not share a name: $ids", 2, ids.toSet().size)

        // Qualified by the file that declares it, which is the thing that tells
        // them apart, and by a character no Kotlin name can contain.
        assertTrue(ids.toString(), ids.any { it == "com.example.StatItem#Left.kt" })
        assertTrue(ids.toString(), ids.any { it == "com.example.StatItem#Right.kt" })
    }

    @Test
    fun `a public screen is named by its qualified name alone`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            mode = "extract",
            sources = listOf(SourceFile("Public.kt", """
                package com.example

                import androidx.compose.foundation.layout.Column
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable

                @Composable
                fun Visible() {
                    Column {
                        Text("one")
                        Text("two")
                    }
                }
            """.trimIndent())),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        val generated = result.generatedScreen()

        assertTrue(generated, generated.contains(""""com.example.Visible""""))
    }

    private companion object {
        val SCREEN_ID = Regex("""SCREEN_ID: String = "([^"]+)"""")
    }
}
