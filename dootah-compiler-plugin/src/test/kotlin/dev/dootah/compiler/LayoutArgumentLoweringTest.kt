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
            generated.contains("""horizontalArrangement = ArrangementNode("spacedBy", DimensionNode(8.0))"""),
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
            generated.contains("""verticalArrangement = ArrangementNode("spacedBy", DimensionNode(12.5))"""),
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
    // ---- lengths the app owns --------------------------------------------

    /**
     * The shape this milestone was built for, from a real app.
     *
     * `MaterialTheme.padding.small` is the app's spacing scale. Reading its
     * value at build time and shipping `8.dp` would work today and be wrong the
     * first time anyone retunes the scale, so what travels is the name.
     */
    @Test
    fun `lowers a spacedBy gap the app owns as a name`() {

        val generated = lower(
            spacingScreen(
                """
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                    Text("x")
                }
                """
            )
        )

        assertTrue(
            generated,
            generated.contains(
                """ArrangementNode("spacedBy", DimensionNode(anchor = """" +
                    """androidx.compose.material3.MaterialTheme.padding.small"))"""
            ),
        )
    }

    @Test
    fun `lowers a padding the app owns as a name`() {

        val generated = lower(
            spacingScreen(
                """Text("x", modifier = Modifier.padding(MaterialTheme.padding.medium))"""
            )
        )

        assertTrue(
            generated,
            generated.contains(
                """DimensionNode(anchor = "androidx.compose.material3.MaterialTheme.padding.medium")"""
            ),
        )
    }

    /** A literal and a named length side by side in one padding. */
    @Test
    fun `mixes a literal and an app-owned length in one modifier`() {

        val generated = lower(
            spacingScreen(
                """Text("x", modifier = Modifier.padding(top = 4.dp, bottom = MaterialTheme.padding.small))"""
            )
        )

        assertTrue(generated, generated.contains("DimensionNode(4.0)"))
        assertTrue(
            generated,
            generated.contains(
                """DimensionNode(anchor = "androidx.compose.material3.MaterialTheme.padding.small")"""
            ),
        )
    }

    /**
     * A screen that names one records it, so the app is asked for it.
     *
     * The other half of what makes this safe: the bundle declares the value it
     * needs, and a build whose app no longer reads that property is a refused
     * publish rather than a collapsed layout on a device.
     */
    @Test
    fun `records an app-owned length as something the app must supply`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(
                spacingScreen(
                    """
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                        Text("x")
                    }
                    """
                )
            ),
            mode = "extract",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        val requirements = result.requirementsFragment()

        assertTrue(
            requirements,
            requirements.contains("androidx.compose.material3.MaterialTheme.padding.small"),
        )
    }

    /** A number is still a number: nothing is anchored that need not be. */
    @Test
    fun `leaves a literal length as a number`() {

        val generated = lower(spacingScreen("""Text("x", modifier = Modifier.padding(8.dp))"""))

        assertTrue(generated, generated.contains("DimensionNode(8.0)"))
        assertFalse(generated, generated.contains("anchor ="))
    }

    /**
     * A computed length is still refused.
     *
     * The line is not difficulty, it is whether both compilations can arrive at
     * the same name. Arithmetic has no name, so this stays where it was.
     */
    @Test
    fun `refuses a length that is computed rather than named`() {

        val report = keptNativeOrRefused(
            spacingScreen(
                """Text("x", modifier = Modifier.padding(MaterialTheme.padding.small + 4.dp))"""
            )
        )

        assertTrue(report, report.contains("dp"))
    }

    /** A local holding a length has no name the other compilation could use. */
    @Test
    fun `refuses a length held in a local`() {

        val report = keptNativeOrRefused(
            spacingScreen(
                """Text("x", modifier = Modifier.padding(gap))""",
                prelude = "val gap = MaterialTheme.padding.small",
            )
        )

        assertTrue(report, report.contains("dp"))
    }

    /**
     * A screen whose app owns a spacing scale, shaped like the real ones.
     *
     * A `Padding` class hung off `MaterialTheme` by an extension property, which
     * is how every app in the corpus that has a spacing scale writes one.
     */
    private fun spacingScreen(
        body: String,
        prelude: String = "",
    ): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.Row
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp
            import dev.dootah.Bundlable

            class Padding {
                val small = 8.dp
                val medium = 16.dp
            }

            val MaterialTheme.padding: Padding get() = Padding()

            @Bundlable
            @Composable
            fun Screen() {
                ${prelude.trimIndent().replace("\n", "\n    ")}
                Column {
                    ${body.trimIndent().replace("\n", "\n        ")}
                }
            }
        """.trimIndent(),
    )

}
