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
 * A fully qualified name is enough for neither. Two files in a package may each
 * declare `private fun StatItem(…)`, because they cannot see each other, and a
 * package may declare `fun FormatPage(…)` twice over as long as the parameters
 * differ. Both were found on real applications.
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
        assertTrue(ids.toString(), ids.any { it == "com.example.StatItem()#Left.kt" })
        assertTrue(ids.toString(), ids.any { it == "com.example.StatItem()#Right.kt" })
    }

    /**
     * Two public composables of one name, which Kotlin resolves by their
     * parameters.
     *
     * Seal declares `FormatPage(videoInfo, …)` and `FormatPage(state, …)` in one
     * package. Under a shared name the app intercepted both and recorded one,
     * and either function would have drawn the other's bundle.
     */
    @Test
    fun `two public overloads of one name are two screens`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            mode = "extract",
            sources = listOf(SourceFile("Overloads.kt", """
                package com.example

                import androidx.compose.foundation.layout.Column
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable

                @Composable
                fun Detail(title: String) {
                    Column {
                        Text(title)
                        Text("by title")
                    }
                }

                @Composable
                fun Detail(number: Int, label: String) {
                    Column {
                        Text(label)
                        Text("by number")
                    }
                }
            """.trimIndent())),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        val ids = result.generatedSources()
            .filterKeys { it.startsWith("DootahScreen_") }
            .values
            .mapNotNull { source -> SCREEN_ID.find(source)?.groupValues?.get(1) }
            .filter { id -> id.startsWith("com.example.Detail") }

        assertEquals("both overloads should be described: $ids", 2, ids.size)
        assertEquals("they must not share a name: $ids", 2, ids.toSet().size)
    }

    @Test
    fun `a public screen is named by the declaration it is`() {

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

        // The parameters it declares, because they are what tells one
        // declaration of a name from another.
        assertTrue(generated, generated.contains(""""com.example.Visible()""""))
    }

    private companion object {
        val SCREEN_ID = Regex("""SCREEN_ID: String = "([^"]+)"""")
    }
}
