package dev.dootah.compiler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lowering turns a developer's Compose source into bundle Kotlin. These tests
 * pin the translation itself: what each supported construct becomes, and that
 * anything else is refused rather than approximated.
 */
class ScreenLoweringTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // ---- UI -------------------------------------------------------------

    @Test
    fun `lowers Text to a bundle Text call`() {

        val generated = lower(screen("""Text("Weekend Offer")"""))

        assertTrue(generated, generated.contains("""Text("Weekend Offer")"""))
    }

    @Test
    fun `lowers Column to the bundle DSL column`() {

        val generated = lower(screen("""Text("first")${'\n'}        Text("second")"""))

        assertTrue(generated, generated.contains("return Column {"))
        // Children keep the order they were written in.
        assertTrue(
            generated,
            generated.indexOf("""Text("first")""") < generated.indexOf("""Text("second")"""),
        )
    }

    /**
     * The installed renderer needs an action name to send back when a button is
     * tapped, so lowering has to supply one even though Milestone 1 has no click
     * behaviour to dispatch.
     */
    @Test
    fun `lowers Button to a labelled action`() {

        val generated = lower(
            screen("""Button(onClick = { }) { Text("Buy now") }"""),
        )

        assertTrue(generated, generated.contains("""Button(text = "Buy now", action = "buy-now")"""))
    }

    // ---- values ---------------------------------------------------------

    @Test
    fun `lowers locals and Int arithmetic`() {

        val generated = lower(
            screen(
                body = """Text("done")""",
                locals = """
                    val price = 999
                    val discount = 100
                    val finalPrice = price - discount
                """.trimIndent(),
            )
        )

        assertTrue(generated, generated.contains("val price: Int = 999"))
        assertTrue(generated, generated.contains("val discount: Int = 100"))
        assertTrue(generated, generated.contains("val finalPrice: Int = (price - discount)"))
    }

    /**
     * The arithmetic has to stay arithmetic. Folding it to a constant would move
     * the calculation out of the bundle, and changing the calculation is half of
     * what an update is for.
     */
    @Test
    fun `keeps arithmetic as an expression rather than folding it`() {

        val generated = lower(
            screen(
                body = """Text("x")""",
                locals = "val total = 999 - 100",
            )
        )

        assertTrue(generated, generated.contains("(999 - 100)"))
        assertFalse("the calculation was folded away", generated.contains("= 899"))
    }

    /**
     * Emitted as concatenation rather than a Kotlin template, so no literal part
     * needs its dollar signs re-escaped on the way out.
     */
    @Test
    fun `lowers string interpolation to concatenation`() {

        val generated = lower(
            screen(
                body = """Text("Price: Rs ${'$'}finalPrice")""",
                locals = "val finalPrice = 899",
            )
        )

        assertTrue(generated, generated.contains("""Text("Price: Rs " + finalPrice)"""))
    }

    /**
     * A template beginning with a non-string needs an empty literal in front, or
     * the generated concatenation would be `Int.plus(String)` and would not
     * compile.
     */
    @Test
    fun `anchors a template that starts with a number`() {

        val generated = lower(
            screen(
                body = """Text("${'$'}count items")""",
                locals = "val count = 3",
            )
        )

        assertTrue(generated, generated.contains(""""" + count + " items""""))
    }

    @Test
    fun `escapes a dollar sign in generated string literals`() {

        val generated = lower(screen("""Text("100% off, US${'$'}0")"""))

        assertTrue(generated, generated.contains("""US\${'$'}0"""))
    }

    // ---- rejection ------------------------------------------------------

    /**
     * A locally declared `Text` is a different function from Compose's. Matching
     * on the name at the call site could not tell them apart, and bundling the
     * local one as if it were Compose's would render something the developer
     * never wrote.
     */
    @Test
    fun `refuses a composable that only shares a name with a supported one`() {

        val rejection = reject(
            SourceFile(
                name = "Shadowed.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.runtime.Composable
                    import dev.dootah.Bundlable

                    @Composable
                    fun Text(text: String) {}

                    @Bundlable
                    @Composable
                    fun ShadowedScreen() {
                        Column { Text("not compose") }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(rejection, rejection.contains("com.example.Text"))
        assertTrue(rejection, rejection.contains("remedy="))
    }

    @Test
    fun `refuses an unsupported composable and names what it can bundle`() {

        val rejection = reject(
            SourceFile(
                name = "Lazy.kt",
                contents = """
                    package com.example

                    import androidx.compose.runtime.Composable
                    import dev.dootah.Bundlable

                    @Composable
                    fun LazyColumn(content: () -> Unit) {}

                    @Bundlable
                    @Composable
                    fun ListScreen() {
                        LazyColumn { }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(rejection, rejection.contains("LazyColumn"))
        assertTrue(rejection, rejection.contains("Column"))
        assertTrue(rejection, rejection.contains("NativeBridge"))
    }

    /**
     * Dropping a click handler would leave a button that looks live and does
     * nothing, which is worse than refusing to bundle the screen.
     */
    @Test
    fun `refuses a Button whose onClick does something`() {

        val rejection = reject(
            screen("""Button(onClick = { record() }) { Text("Buy") }""", extra = "fun record() {}"),
        )

        assertTrue(rejection, rejection.contains("onClick has a body"))
    }

    @Test
    fun `refuses a Text with a modifier`() {

        val rejection = reject(
            SourceFile(
                name = "Styled.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import androidx.compose.ui.Modifier
                    import dev.dootah.Bundlable

                    @Bundlable
                    @Composable
                    fun StyledScreen() {
                        Column { Text("hello", Modifier) }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(rejection, rejection.contains("no Modifier, no styling"))
    }

    @Test
    fun `refuses a var because bundled state is not supported yet`() {

        val rejection = reject(
            screen(body = """Text("x")""", locals = "var count = 0"),
        )

        assertTrue(rejection, rejection.contains("`var` declaration `count`"))
    }

    @Test
    fun `refuses a screen that takes parameters`() {

        val rejection = reject(
            SourceFile(
                name = "Parameterised.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import dev.dootah.Bundlable

                    @Bundlable
                    @Composable
                    fun DetailScreen(title: String) {
                        Column { Text(title) }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(rejection, rejection.contains("parameter"))
        assertTrue(rejection, rejection.contains("zero-argument"))
    }

    /** The bundle DSL's column scope offers Text and Button only. */
    @Test
    fun `refuses a nested layout`() {

        val rejection = reject(
            screen("""Column { Text("inner") }"""),
        )

        assertTrue(rejection, rejection.contains("nested inside a Column"))
    }

    // ---- fixtures -------------------------------------------------------

    private fun lower(source: SourceFile): String {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "extract",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        return result.generatedScreen()
    }

    private fun reject(source: SourceFile): String {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "extract",
        )

        return requireNotNull(result.rejectionReport()) {
            "expected the screen to be refused, but it lowered to:\n" +
                result.generatedSources().values.joinToString("\n")
        }
    }

    private fun screen(
        body: String,
        locals: String = "",
        extra: String = "",
    ) = SourceFile(
        name = "TestScreen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Button
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import dev.dootah.Bundlable

            $extra

            @Bundlable
            @Composable
            fun TestScreen() {
                $locals
                Column {
                    $body
                }
            }
        """.trimIndent(),
    )
}
