package dev.dootah.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Extraction reads the developer's own source semantically. What it must get
 * right is the difference between a name as written and the declaration that
 * name resolves to -- everything downstream depends on knowing which `Text` a
 * call meant.
 */
class ScreenExtractionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `resolves composable calls to their declaring symbols`() {

        val report = extract(offerScreen(), screenId = "com.example.OfferScreen")

        assertTrue(report, report.contains("call=androidx.compose.foundation.layout.Column"))
        assertTrue(report, report.contains("call=androidx.compose.material3.Text"))
        assertTrue(report, report.contains("call=androidx.compose.material3.Button"))
    }

    /**
     * A locally declared `Text` is a different function from Compose's, and
     * extraction has to tell them apart. Matching on the name written at the
     * call site could not.
     */
    @Test
    fun `distinguishes a shadowing declaration from the compose one`() {

        val report = extract(
            SourceFile(
                name = "Shadowed.kt",
                contents = """
                    package com.example

                    import androidx.compose.runtime.Composable

                    @Composable
                    fun Text(text: String) {}

                    @Composable
                    fun ShadowedScreen() {
                        Text("not compose")
                    }
                """.trimIndent(),
            ),
            screenId = "com.example.ShadowedScreen",
        )

        assertTrue(report, report.contains("call=com.example.Text"))
        assertTrue(
            "the shadowing call must not be attributed to Compose",
            !report.contains("call=androidx.compose.material3.Text"),
        )
    }

    @Test
    fun `sees arithmetic as a resolved operator call`() {

        val report = extract(offerScreen(), screenId = "com.example.OfferScreen")

        assertTrue(report, report.contains("call=kotlin.Int.minus"))
    }

    /**
     * The template must survive as a template. If the frontend had folded it to
     * a single constant, the lowering could not rebuild it from its parts.
     */
    @Test
    fun `keeps string interpolation as interpolation`() {

        val report = extract(offerScreen(), screenId = "com.example.OfferScreen")

        assertTrue(report, report.contains("hasStringInterpolation=true"))
        assertTrue(report, report.contains("literal=Price: Rs "))
    }

    @Test
    fun `detects an if else branch`() {

        val report = extract(
            SourceFile(
                name = "Branching.kt",
                contents = """
                    package com.example

                    import androidx.compose.runtime.Composable
                    import androidx.compose.material3.Text

                    @Composable
                    fun BranchingScreen() {
                        val premium = true
                        if (premium) Text("member") else Text("guest")
                    }
                """.trimIndent(),
            ),
            screenId = "com.example.BranchingScreen",
        )

        assertTrue(report, report.contains("hasConditional=true"))
    }

    /**
     * The installed app and the published bundle compare these strings across
     * the network, and they are computed by two different compiler frontends.
     * If they ever disagree, every patch silently stops matching.
     */
    @Test
    fun `the extracted id matches the id written into the app`() {

        val extracted = extract(offerScreen(), screenId = "com.example.OfferScreen")

        val intercepted = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder("intercept"),
            sources = listOf(offerScreen()),
            mode = "intercept",
        ).orderingReport()!!

        val extractedId = extracted.lineSequence()
            .first { it.startsWith("screen=") }
            .removePrefix("screen=")

        val interceptedId = intercepted.lineSequence()
            .first { it.startsWith("intercepted=") }
            .removePrefix("intercepted=")

        assertEquals(interceptedId, extractedId)
    }

    private fun extract(source: SourceFile, screenId: String): String {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(screenId.filter { it.isLetterOrDigit() }),
            sources = listOf(source),
            mode = "extract",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        return requireNotNull(result.extractionReport(screenId)) {
            "extraction produced no report for '$screenId'; messages: ${result.messages}"
        }
    }

    private fun offerScreen() = SourceFile(
        name = "OfferScreen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Button
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable

            @Composable
            fun OfferScreen() {
                val price = 999
                val discount = 0
                val finalPrice = price - discount

                Column {
                    Text("Old")
                    Text("Price: Rs ${'$'}finalPrice")

                    Button(onClick = { }) {
                        Text("Buy")
                    }
                }
            }
        """.trimIndent(),
    )
}
