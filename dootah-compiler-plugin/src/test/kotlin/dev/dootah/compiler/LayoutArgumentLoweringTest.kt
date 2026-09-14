package dev.dootah.compiler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Alignment and arrangement, from Compose source to generated bundle source.
 *
 * These were the second-largest measured blocker in the corpus, and the shape of
 * the fix is what these tests pin: the values Compose declares are named, the
 * names come from a closed vocabulary, and everything else degrades rather than
 * being approximated or dropped.
 *
 * The negative half matters more than the positive half. An alignment that is
 * silently ignored still compiles, still ships, and draws a layout nobody wrote
 * -- so each of these checks not merely that something was refused, but that the
 * refusal cost only what it had to.
 */
class LayoutArgumentLoweringTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // ---- supported alignment and arrangement -----------------------------

    @Test
    fun `lowers a Row's vertical alignment as a name`() {

        val generated = lower(
            screen(
                """
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("x")
                }
                """
            )
        )

        assertTrue(generated, generated.contains("""verticalAlignment = "CenterVertically""""))
    }

    @Test
    fun `lowers a Column's alignment and arrangement together`() {

        val generated = lower(
            screen(
                """
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("x")
                }
                """
            )
        )

        assertTrue(generated, generated.contains("""horizontalAlignment = "End""""))
        assertTrue(
            generated,
            generated.contains("""verticalArrangement = ArrangementNode("SpaceBetween")"""),
        )
    }

    @Test
    fun `lowers a Box's content alignment`() {

        val generated = lower(
            screen(
                """
                Box(contentAlignment = Alignment.Center) {
                    Text("x")
                }
                """
            )
        )

        assertTrue(generated, generated.contains("""contentAlignment = "Center""""))
    }

    /**
     * Every name in the vocabulary, lowered for real rather than asserted about.
     *
     * The golden test holds the six layers to one list; this is the other half
     * of that guarantee -- that the frontend actually reads each name off real
     * Compose source, which is where a name that resolves through a different
     * companion or a different fqName would show up.
     */
    @Test
    fun `lowers every alignment in the vocabulary`() {

        val rows = dev.dootah.contract.Alignments.VERTICAL.joinToString("\n") { token ->
            """Row(verticalAlignment = Alignment.$token) { Text("$token") }"""
        }

        val generated = lower(screen(rows))

        dev.dootah.contract.Alignments.VERTICAL.forEach { token ->
            assertTrue(
                "$token did not lower:\n$generated",
                generated.contains("""verticalAlignment = "$token""""),
            )
        }
    }

    @Test
    fun `lowers every arrangement in the vocabulary`() {

        val named = dev.dootah.contract.Arrangements.HORIZONTAL
            .filterNot { it == dev.dootah.contract.Arrangements.SPACED_BY }

        val generated = lower(
            screen(named.joinToString("\n") { token ->
                """Row(horizontalArrangement = Arrangement.$token) { Text("$token") }"""
            })
        )

        named.forEach { token ->
            assertTrue(
                "$token did not lower:\n$generated",
                generated.contains("""horizontalArrangement = ArrangementNode("$token")"""),
            )
        }
    }

    // ---- dp on this path --------------------------------------------------

    @Test
    fun `lowers a spacedBy gap written as a dp literal`() {

        val generated = lower(
            screen(
                """
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("x")
                }
                """
            )
        )

        assertTrue(
            generated,
            generated.contains("""horizontalArrangement = ArrangementNode("spacedBy", 8.0)"""),
        )
    }

    @Test
    fun `reads a spacedBy gap written on any numeric type`() {

        val generated = lower(
            screen(
                """
                Column(verticalArrangement = Arrangement.spacedBy(12.5f.dp)) {
                    Text("x")
                }
                """
            )
        )

        assertTrue(
            generated,
            generated.contains("""verticalArrangement = ArrangementNode("spacedBy", 12.5)"""),
        )
    }

    // ---- a layout written without them -----------------------------------

    /**
     * An unstated alignment stays unstated.
     *
     * The bundle must not fill in what it believes Compose's default to be: the
     * app renders with its own Compose, on the other side of a version boundary
     * this bundle does not control, and the default is that Compose's to choose.
     */
    @Test
    fun `says nothing about alignment a layout did not state`() {

        val generated = lower(screen("""Row { Text("x") }"""))

        assertFalse(generated, generated.contains("verticalAlignment"))
        assertFalse(generated, generated.contains("horizontalArrangement"))
    }

    // ---- values outside the vocabulary ------------------------------------

    /**
     * A computed gap is refused, for the reason a computed padding is: it is an
     * expression the remote side would have to evaluate.
     */
    @Test
    fun `refuses a spacedBy gap that is not a literal`() {

        val report = keptNativeOrRefused(
            screen(
                """
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Text("x")
                }
                """,
                parameters = "gap: androidx.compose.ui.unit.Dp",
            )
        )

        assertTrue(report, report.contains("spacedBy"))
    }

    /**
     * The overload carrying an alignment is refused rather than half-read.
     *
     * Dropping the second argument would compile, ship, and draw a layout that
     * is subtly not the one in the source -- the failure this design exists to
     * make impossible.
     */
    @Test
    fun `refuses the spacedBy overload that also takes an alignment`() {

        val report = keptNativeOrRefused(
            screen(
                """
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    Text("x")
                }
                """
            )
        )

        assertTrue(report, report.contains("verticalArrangement"))
    }

    @Test
    fun `refuses an alignment held in a local`() {

        val report = keptNativeOrRefused(
            screen(
                """
                Row(verticalAlignment = chosen) {
                    Text("x")
                }
                """,
                prelude = "val chosen = Alignment.CenterVertically",
            )
        )

        assertTrue(report, report.contains("verticalAlignment"))
    }

    /** `Arrangement.Absolute.Right` is a real arrangement and not in the set. */
    @Test
    fun `refuses an arrangement outside the vocabulary`() {

        val report = keptNativeOrRefused(
            screen(
                """
                Row(horizontalArrangement = Arrangement.Absolute.Right) {
                    Text("x")
                }
                """
            )
        )

        assertTrue(report, report.contains("horizontalArrangement"))
    }

    /**
     * A refused alignment costs the layout, not the screen.
     *
     * This is the property that makes widening the vocabulary safe to get wrong:
     * an unreadable value walks the degradation ladder like any other, so the
     * rest of the screen stays updatable and the build still succeeds.
     */
    @Test
    fun `a refused alignment degrades rather than failing the build`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(
                screen(
                    """
                    Text("above")
                    Row(horizontalArrangement = Arrangement.Absolute.Right) {
                        Text("inside")
                    }
                    """
                )
            ),
            mode = "extract",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        val generated = result.generatedSources().values.joinToString("\n")

        assertTrue(
            "the surviving part of the screen was lost:\n$generated",
            generated.contains("""TextNode("above")"""),
        )
    }

    // ---- harness ---------------------------------------------------------

    private fun lower(source: SourceFile): String {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "extract",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        return requireNotNull(
            result.generatedSources().entries.firstOrNull { it.key.startsWith("DootahScreen_") }
        ) {
            "nothing was generated. The screen was refused:\n${result.rejectionReport()}"
        }.value
    }

    /**
     * What the build said it could not bundle, however it degraded.
     *
     * A layout Dootah cannot read may end up refused outright or kept native
     * inside a screen that otherwise lowered, depending on what surrounds it.
     * Both are the same answer to the question these tests ask -- the value was
     * not accepted -- and pinning which rung of the ladder it lands on would be
     * pinning the ladder rather than the vocabulary.
     */
    private fun keptNativeOrRefused(source: SourceFile): String {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "extract",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        val report = result.rejectionReport().orEmpty() + result.degradationReport().orEmpty()

        assertTrue(
            "the value was accepted, and should not have been:\n" +
                result.generatedSources().values.joinToString("\n"),
            report.isNotBlank(),
        )

        return report
    }

    private fun screen(
        body: String,
        prelude: String = "",
        parameters: String = "",
    ): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Box
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.Row
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun Screen($parameters) {
                ${prelude.trimIndent().replace("\n", "\n    ")}
                Column {
                    ${body.trimIndent().replace("\n", "\n        ")}
                }
            }
        """.trimIndent(),
    )
}
