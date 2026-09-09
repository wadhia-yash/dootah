package dev.dootah.compiler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Dootah's IR transform is only correct when it runs before the Compose
 * compiler's lowering. These tests pin both halves of that: the order the plugin
 * expects, and the failure it must produce when the order is wrong.
 */
class ComposeOrderingGuardTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reports running before compose for an ordinary bundlable function`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.root,
            sources = listOf(bundlableScreen()),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        assertTrue(
            "unexpected ordering report: ${result.orderingReport()}",
            result.orderingReport()!!.contains("ordering=BEFORE_COMPOSE"),
        )
    }

    @Test
    fun `discovers the bundlable function by its fully qualified name`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.root,
            sources = listOf(bundlableScreen()),
        )

        val report = result.orderingReport()!!

        assertTrue(report, report.contains("bundlableCount=1"))
        assertTrue(report, report.contains("bundlable=com.example.OfferScreen"))
    }

    @Test
    fun `reports nothing to do when no function is bundlable`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.root,
            sources = listOf(
                SourceFile(
                    name = "Plain.kt",
                    contents = """
                        package com.example

                        import androidx.compose.runtime.Composable

                        @Composable
                        fun PlainScreen() {}
                    """.trimIndent(),
                )
            ),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        assertTrue(
            result.orderingReport()!!,
            result.orderingReport()!!.contains("ordering=INCONCLUSIVE"),
        )
    }

    /**
     * A composable that has already been lowered carries the composer Compose
     * threads through it. The guard must refuse to transform such a module
     * rather than insert calls Compose will never lower.
     */
    @Test
    fun `fails with an actionable message when compose already lowered`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.root,
            sources = listOf(alreadyLoweredScreen()),
        )

        assertFalse(
            "compilation should have failed: ${result.messages}",
            result.succeeded,
        )

        assertTrue(
            result.orderingReport()!!,
            result.orderingReport()!!.contains("ordering=AFTER_COMPOSE"),
        )

        val orderingErrors = result.messagesContaining("Dootah must run before the Compose compiler")

        assertTrue(
            "expected the ordering error, got: ${result.messages}",
            orderingErrors.isNotEmpty(),
        )

        assertTrue(
            "the error must name the fix, got: $orderingErrors",
            orderingErrors.single().contains("""id("dev.dootah")"""),
        )
    }

    private fun bundlableScreen() = SourceFile(
        name = "OfferScreen.kt",
        contents = """
            package com.example

            import androidx.compose.runtime.Composable
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun OfferScreen() {}
        """.trimIndent(),
    )

    /**
     * Stands in for a module Compose has already lowered.
     *
     * The composer parameter is what the guard keys on, and a lowered composable
     * is exactly a composable that has acquired one.
     */
    private fun alreadyLoweredScreen() = SourceFile(
        name = "LoweredScreen.kt",
        contents = """
            package com.example

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.Composer
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun OfferScreen(composer: Composer?, changed: Int) {}
        """.trimIndent(),
    )
}
