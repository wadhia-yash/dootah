package dev.dootah.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Which Compose functions Dootah takes over is now decided by the compiler
 * rather than by an annotation, and it is decided twice: once in the app's own
 * build, which is what the installed APK can render, and once in the extraction
 * pass, which is what a published bundle describes.
 *
 * Those two compilations run at different times over different versions of the
 * source. When they disagree, the failure is silent in both directions -- a
 * screen the bundle describes that the APK never intercepted simply never
 * updates, and a screen the APK intercepted that the bundle does not describe
 * pays for interception forever and gets nothing.
 *
 * With an annotation on every screen, agreement was trivial: both passes looked
 * for the same annotation. Automatic discovery is where it becomes possible to
 * get wrong, which is what these tests exist for.
 */
class ScreenDiscoveryAgreementTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `both passes discover the same functions`() {

        val discovered = extract(app()).discoveredScreens()
        val intercepted = intercept(app()).interceptedScreens()

        assertEquals(intercepted, discovered)
    }

    @Test
    fun `both passes pass over the same functions`() {

        val intercepted = intercept(app()).interceptedScreens()

        // Every reason a function is skipped, and both passes have to reach
        // the same verdict about each of them independently.
        listOf(
            "com.example.Wrapper",
            "com.example.PriceLabel",
            "com.example.RowItem",
            "com.example.AuditedScreen",
            "com.example.AuditedObject.Screen",
            "com.example.PreviewScreen",
        ).forEach { skipped ->
            assertTrue(
                "$skipped must not be intercepted, got: $intercepted",
                skipped !in intercepted,
            )
        }

        assertEquals(setOf("com.example.Toolbox", "com.example.Detail"), intercepted)
    }

    /**
     * A whole file kept native.
     *
     * The annotation has to be honoured from the file as well as from the
     * function, because "none of this" is how someone actually writes it -- and
     * the two passes read a file annotation through entirely different APIs.
     */
    @Test
    fun `a file kept native is skipped by both passes`() {

        val source = SourceFile(
            name = "Payments.kt",
            contents = """
                @file:dev.dootah.DootahNative

                package com.example.payments

                import androidx.compose.runtime.Composable

                @Composable
                fun PaymentSheet() {}
            """.trimIndent(),
        )

        assertTrue(extract(source).discoveredScreens().isEmpty())
        assertTrue(intercept(source).interceptedScreens().isEmpty())
    }

    /**
     * The build script's own opt-out, which reaches both compilations as one
     * encoded string so that they cannot read it differently.
     */
    @Test
    fun `an excluded package is skipped by both passes`() {

        val filter = "-com.example.debug"

        assertEquals(
            setOf("com.example.Kept"),
            extract(kept(), excluded(), filter = filter).discoveredScreens(),
        )
        assertEquals(
            setOf("com.example.Kept"),
            intercept(kept(), excluded(), filter = filter).interceptedScreens(),
        )
    }

    /** `@Bundlable` survives as a way to force something back into scope. */
    @Test
    fun `an explicitly marked function overrides the filter`() {

        val source = SourceFile(
            name = "Debug.kt",
            contents = """
                package com.example.debug

                import androidx.compose.runtime.Composable
                import dev.dootah.Bundlable

                @Bundlable
                @Composable
                fun DebugPanel() {}
            """.trimIndent(),
        )

        val filter = "-com.example.debug"

        assertEquals(
            setOf("com.example.debug.DebugPanel"),
            extract(source, filter = filter).discoveredScreens(),
        )
        assertEquals(
            setOf("com.example.debug.DebugPanel"),
            intercept(source, filter = filter).interceptedScreens(),
        )
    }

    // ---- fixtures -------------------------------------------------------

    private fun extract(vararg sources: SourceFile, filter: String = ""): CompilationResult =
        run(sources.toList(), mode = "extract", filter = filter)

    private fun intercept(vararg sources: SourceFile, filter: String = ""): CompilationResult =
        run(sources.toList(), mode = "intercept", filter = filter)

    private fun run(
        sources: List<SourceFile>,
        mode: String,
        filter: String,
    ): CompilationResult {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = sources,
            mode = mode,
            screenFilter = filter,
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        return result
    }

    /** One file holding one of everything discovery has to decide about. */
    private fun app() = SourceFile(
        name = "App.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.ColumnScope
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import dev.dootah.DootahNative

            annotation class Preview

            @Composable
            fun Toolbox() {
                Column { Text("tools") }
            }

            @Composable
            fun Detail() {
                Column { Text("detail") }
            }

            // Content belongs to the caller.
            @Composable
            fun Wrapper(content: @Composable () -> Unit) {
                content()
            }

            // Produces a value rather than placing one.
            @Composable
            fun PriceLabel(): String = "0"

            // Drawn into a scope the caller owns.
            @Composable
            fun ColumnScope.RowItem() {
                Text("item")
            }

            @DootahNative
            @Composable
            fun AuditedScreen() {
                Text("audited")
            }

            @DootahNative
            object AuditedObject {
                @Composable
                fun Screen() {
                    Text("audited")
                }
            }

            @Preview
            @Composable
            fun PreviewScreen() {
                Text("preview")
            }
        """.trimIndent(),
    )

    private fun kept() = SourceFile(
        name = "Kept.kt",
        contents = """
            package com.example

            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable

            @Composable
            fun Kept() {
                Text("kept")
            }
        """.trimIndent(),
    )

    private fun excluded() = SourceFile(
        name = "Excluded.kt",
        contents = """
            package com.example.debug

            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable

            @Composable
            fun DebugPanel() {
                Text("debug")
            }
        """.trimIndent(),
    )
}
