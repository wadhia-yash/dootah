package dev.dootah.compiler

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lowering turns a developer's Compose source into bundle Kotlin. These tests
 * pin the translation itself: what each supported construct becomes, what is
 * kept native instead, and that anything else is refused rather than
 * approximated.
 */
class ScreenLoweringTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // ---- layout ---------------------------------------------------------

    @Test
    fun `lowers Text and keeps children in order`() {

        val generated = lower(
            screen(
                """
                Text("first")
                Text("second")
                """
            )
        )

        assertTrue(generated, generated.contains("""TextNode("first")"""))
        assertTrue(
            generated,
            generated.indexOf("""TextNode("first")""") <
                generated.indexOf("""TextNode("second")"""),
        )
    }

    @Test
    fun `lowers nested Row and Box inside a Column`() {

        val generated = lower(
            screen(
                """
                Row {
                    Text("left")
                    Box {
                        Text("inner")
                    }
                }
                """
            )
        )

        assertTrue(generated, generated.contains("RowNode("))
        assertTrue(generated, generated.contains("BoxNode("))
        assertTrue(generated, generated.contains("""TextNode("inner")"""))
    }

    // ---- modifiers ------------------------------------------------------

    @Test
    fun `keeps modifier order`() {

        val generated = lower(
            screen(
                """Text("x", modifier = Modifier.padding(8.dp).fillMaxWidth())"""
            )
        )

        val padding = generated.indexOf("BundleModifier.Padding")
        val fill = generated.indexOf("BundleModifier.FillMaxWidth")

        assertTrue(generated, padding in 1..<fill)
    }

    @Test
    fun `reads named padding sides`() {

        val generated = lower(
            screen("""Text("x", modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))""")
        )

        assertTrue(generated, generated.contains("BundleModifier.Padding(0.0, 16.0, 0.0, 4.0)"))
    }

    @Test
    fun `splices in the screen's own modifier parameter`() {

        val generated = lower(
            screen(
                body = """Text("x")""",
                parameters = "modifier: Modifier = Modifier",
                columnModifier = "modifier = modifier.padding(4.dp)",
            )
        )

        val inherited = generated.indexOf("BundleModifier.Inherited")
        val padding = generated.indexOf("BundleModifier.Padding")

        assertTrue(generated, inherited in 1..<padding)
    }

    @Test
    fun `refuses a computed size`() {

        val rejection = reject(
            screen(
                body = """Text("x", modifier = Modifier.padding(spacing.dp))""",
                prelude = "val spacing = 8",
            )
        )

        // Both reasons are reported, and the one naming what the developer
        // wrote comes first.
        assertTrue(rejection, rejection.contains("Write sizes as literals"))
    }

    // ---- values and logic ----------------------------------------------

    @Test
    fun `lowers locals and Int arithmetic`() {

        val generated = lower(
            screen(
                body = """Text("Total: " + total)""",
                prelude = """
                    val price = 999
                    val discount = 100
                    val total = price - discount
                """,
            )
        )

        assertTrue(generated, generated.contains("val total: Int = (price - discount)"))
    }

    @Test
    fun `lowers comparisons and Boolean logic`() {

        val generated = lower(
            screen(
                body = """Text("x")""",
                prelude = """
                    val quantity = 4
                    val member = true
                    val discounted = quantity >= 3 && member
                """,
            )
        )

        assertTrue(generated, generated.contains("((quantity >= 3) && member)"))
    }

    @Test
    fun `lowers an if expression`() {

        val generated = lower(
            screen(
                body = """Text("x")""",
                prelude = """
                    val premium = true
                    val price = if (premium) 799 else 999
                """,
            )
        )

        assertTrue(generated, generated.contains("(if (premium) 799 else 999)"))
    }

    @Test
    fun `lowers a when expression over a subject`() {

        val generated = lower(
            screen(
                body = """Text("x")""",
                prelude = """
                    val tier = 2
                    val label = when (tier) {
                        1 -> "bronze"
                        2 -> "silver"
                        else -> "gold"
                    }
                """,
            )
        )

        assertTrue(generated, generated.contains("""(tier == 1)"""))
        assertTrue(generated, generated.contains(""""silver""""))
    }

    @Test
    fun `lowers if else around UI`() {

        val generated = lower(
            screen(
                body = """
                if (premium) {
                    Text("Premium")
                } else {
                    Text("Standard")
                }
                """,
                prelude = "val premium = true",
            )
        )

        assertTrue(generated, generated.contains("if (premium) {"))
        assertTrue(generated, generated.contains("""add(TextNode("Premium"))"""))
        assertTrue(generated, generated.contains("""add(TextNode("Standard"))"""))
    }

    @Test
    fun `lowers string interpolation to concatenation`() {

        val generated = lower(
            screen(
                body = """Text("Price: Rs ${'$'}price")""",
                prelude = "val price = 899",
            )
        )

        assertTrue(generated, generated.contains(""""Price: Rs " + price"""))
    }

    @Test
    fun `anchors a template that starts with a number`() {

        val generated = lower(
            screen(
                body = """Text("${'$'}price rupees")""",
                prelude = "val price = 899",
            )
        )

        assertTrue(generated, generated.contains(""""" + price + " rupees""""))
    }

    @Test
    fun `lowers a single-expression function called by the screen`() {

        val generated = lower(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import dev.dootah.Bundlable

                    private fun total(unit: Int, quantity: Int): Int = unit * quantity

                    @Bundlable
                    @Composable
                    fun Screen() {
                        val amount = total(899, 2)
                        Column {
                            Text("Total: " + amount)
                        }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(generated, generated.contains("private fun total(unit: Int, quantity: Int): Int"))
        assertTrue(generated, generated.contains("val amount: Int = total(899, 2)"))
    }

    // ---- state and actions ----------------------------------------------

    @Test
    fun `lowers a var into screen state`() {

        val generated = lower(
            screen(
                body = """Text("Quantity: " + quantity)""",
                prelude = "var quantity = 1",
            )
        )

        assertTrue(generated, generated.contains("""state.initInt("quantity", 1)"""))
        assertTrue(generated, generated.contains("""state.int("quantity")"""))
    }

    @Test
    fun `lowers remembered mutable state into screen state`() {

        val generated = lower(
            screen(
                body = """Text("Quantity: " + quantity)""",
                prelude = "var quantity by remember { mutableStateOf(1) }",
                extraImports = """
                    import androidx.compose.runtime.getValue
                    import androidx.compose.runtime.mutableStateOf
                    import androidx.compose.runtime.remember
                    import androidx.compose.runtime.setValue
                """,
            )
        )

        assertTrue(generated, generated.contains("""state.initInt("quantity", 1)"""))
    }

    @Test
    fun `lowers a button that changes state`() {

        val generated = lower(
            screen(
                body = """
                Button(onClick = { quantity = quantity + 1 }) { Text("Add") }
                Text("Quantity: " + quantity)
                """,
                prelude = "var quantity = 1",
            )
        )

        assertTrue(generated, generated.contains("""ButtonNode("Add", "add")"""))
        assertTrue(
            generated,
            generated.contains("""state.setInt("quantity", (state.int("quantity") + 1))"""),
        )
    }

    @Test
    fun `lowers a button that invokes a screen callback`() {

        val generated = lower(
            screen(
                body = """Button(onClick = onSave) { Text("Save") }""",
                parameters = "onSave: () -> Unit",
            )
        )

        assertTrue(generated, generated.contains("""Command.InvokeCallback("onSave")"""))
    }

    @Test
    fun `gives two buttons with the same label distinct actions`() {

        val generated = lower(
            screen(
                body = """
                Button(onClick = { count = count + 1 }) { Text("Tap") }
                Button(onClick = { count = count - 1 }) { Text("Tap") }
                """,
                prelude = "var count = 0",
            )
        )

        assertTrue(generated, generated.contains(""""tap" ->"""))
        assertTrue(generated, generated.contains(""""tap-2" ->"""))
    }

    // ---- parameters -----------------------------------------------------

    @Test
    fun `reads screen parameters from the caller's arguments`() {

        val generated = lower(
            screen(
                body = """Text(name + ": " + price)""",
                parameters = "name: String, price: Int, premium: Boolean",
            )
        )

        assertTrue(generated, generated.contains("""val name: String = arguments.string("name")"""))
        assertTrue(generated, generated.contains("""val price: Int = arguments.int("price")"""))
        assertTrue(
            generated,
            generated.contains("""val premium: Boolean = arguments.boolean("premium")"""),
        )
    }

    @Test
    fun `reads a nullable parameter as nullable`() {

        val generated = lower(
            screen(
                body = """Text("Note: " + note)""",
                parameters = "note: String?",
            )
        )

        assertTrue(
            generated,
            generated.contains("""val note: String? = arguments.stringOrNull("note")"""),
        )
    }

    // ---- native slots ----------------------------------------------------

    @Test
    fun `keeps a styled Text native instead of dropping its styling`() {

        val generated = lower(
            screen("""Text("Title", color = Color(0xFF2196F3L))""", extraImports = COLOR_IMPORT)
        )

        assertTrue(generated, generated.contains("NativeSlotNode("))
        assertTrue(generated, !generated.contains("""TextNode("Title")"""))
    }

    @Test
    fun `keeps an unknown component native`() {

        val generated = lower(
            screen("""Icon("save")""", extraImports = "import androidx.compose.material3.Icon")
        )

        assertTrue(generated, generated.contains("NativeSlotNode("))
    }

    @Test
    fun `lets a native component read a screen parameter`() {

        val generated = lower(
            screen(
                body = """Icon(label)""",
                parameters = "label: String",
                extraImports = "import androidx.compose.material3.Icon",
            )
        )

        assertTrue(generated, generated.contains("NativeSlotNode("))
    }

    @Test
    fun `refuses a native component that reads a bundled value`() {

        val rejection = reject(
            screen(
                body = """Icon(label)""",
                prelude = """val label = "save"""",
                extraImports = "import androidx.compose.material3.Icon",
            )
        )

        assertTrue(rejection, rejection.contains("which Dootah keeps native but which reads"))
        assertTrue(rejection, rejection.contains("`label`"))
    }

    // ---- root shape ------------------------------------------------------

    /**
     * The shape a real screen takes when it lays out differently in portrait
     * and landscape: one `if` at the top choosing between whole layouts.
     */
    @Test
    fun `lowers a screen whose root is an if between two layouts`() {

        val generated = lower(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.foundation.layout.Row
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import androidx.compose.ui.Modifier
                    import dev.dootah.Bundlable

                    @Bundlable
                    @Composable
                    fun Screen(vertical: Boolean, modifier: Modifier = Modifier) {
                        if (vertical) {
                            Column(modifier = modifier) { Text("stacked") }
                        } else {
                            Row(modifier = modifier) { Text("stacked") }
                        }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(generated, generated.contains("return if (vertical) {"))
        assertTrue(generated, generated.contains("ColumnNode("))
        assertTrue(generated, generated.contains("RowNode("))
    }

    @Test
    fun `a root if with no else draws nothing on the other branch`() {

        val generated = lower(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import dev.dootah.Bundlable

                    @Bundlable
                    @Composable
                    fun Screen(vertical: Boolean) {
                        if (vertical) {
                            Column { Text("stacked") }
                        }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(generated, generated.contains("if (vertical)"))
        assertTrue(generated, generated.contains("ColumnNode("))

        // The branch that draws nothing still has to be a value `render` can
        // return, so it is an empty fragment rather than a missing else.
        assertTrue(generated, generated.contains("FragmentNode("))
    }

    /**
     * A screen body does not have to be one layout.
     *
     * `ToolBoxContent` in Cahier is three siblings, and the Column or Row the
     * caller wrapped the call in is what lays them out. Wrapping them in a
     * layout here would move the screen the first time a bundle drew it, so they
     * are emitted as a fragment that adds nothing.
     */
    @Test
    fun `lowers a body of several components into a fragment`() {

        val generated = lower(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Box
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import dev.dootah.Bundlable

                    @Bundlable
                    @Composable
                    fun Screen(title: String) {
                        Box { Text(title) }
                        Text("second")
                        Box { Text("third") }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(generated, generated.contains("FragmentNode("))

        // In source order, and with no layout wrapped around them.
        val first = generated.indexOf("""TextNode(title)""")
        val second = generated.indexOf("""TextNode("second")""")
        val third = generated.indexOf("""TextNode("third")""")

        assertTrue(generated, first in 1..<second && second < third)
        assertTrue(generated, !generated.contains("ColumnNode("))
    }

    // ---- refusals -------------------------------------------------------

    @Test
    fun `refuses a value of a type it cannot carry`() {

        val rejection = reject(
            screen(
                body = """Text("x")""",
                prelude = "val ratio = 0.5",
            )
        )

        assertTrue(rejection, rejection.contains("not an Int, String or Boolean"))
    }

    @Test
    fun `keeps a component reading a native-only parameter native`() {

        // Nothing is lost: the Text still renders, with the value its caller
        // passed. It simply is not part of what a bundle can change.
        val generated = lower(
            screen(
                body = """Text("Ratio: " + ratio)""",
                parameters = "ratio: Double",
            )
        )

        assertTrue(generated, generated.contains("NativeSlotNode("))
    }

    @Test
    fun `refuses computing with a parameter it cannot carry`() {

        val rejection = reject(
            screen(
                body = """
                if (ratio > 0.5) {
                    Text("high")
                }
                """,
                parameters = "ratio: Double",
            )
        )

        assertTrue(rejection, rejection.contains("`ratio`"))
        assertTrue(rejection, rejection.contains("can still be used inside a component"))
    }

    @Test
    fun `refuses a layout argument it does not bundle`() {

        val rejection = reject(
            screen(
                body = """Text("x")""",
                columnModifier = "verticalArrangement = 2",
            )
        )

        assertTrue(rejection, rejection.contains("Alignment and arrangement are not bundled"))
    }

    @Test
    fun `keeps a button wired to app code native`() {

        // The button keeps working exactly as written. What it does is app code
        // that cannot cross into a bundle, so the button is not updatable -- and
        // the build reports that it was kept native.
        val generated = lower(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.Button
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import dev.dootah.Bundlable

                    fun checkout() {}

                    @Bundlable
                    @Composable
                    fun Screen() {
                        Column {
                            Button(onClick = { checkout() }) { Text("Buy") }
                        }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(generated, generated.contains("NativeSlotNode("))
    }

    @Test
    fun `refuses a click handler that calls something other than a callback`() {

        val rejection = reject(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.Button
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import dev.dootah.Bundlable

                    fun checkout() {}

                    @Bundlable
                    @Composable
                    fun Screen() {
                        var count = 0
                        Column {
                            Text("Count: " + count)
                            Button(onClick = { count = count + 1; checkout() }) {
                                Text("Buy")
                            }
                        }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(rejection, rejection.contains("only through the screen's own callback"))
    }

    @Test
    fun `refuses a composable that only shares a name with a supported one`() {

        val rejection = reject(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.runtime.Composable
                    import dev.dootah.Bundlable

                    // Not Compose's Text. Resolving by name alone would bundle
                    // this as though it were, which is why the plugin matches on
                    // the resolved symbol.
                    fun Text(text: String) {}

                    @Bundlable
                    @Composable
                    fun Screen() {
                        Column {
                            Text("looks familiar")
                        }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(rejection, rejection.contains("A layout may contain components"))
    }

    // ---- fixtures -------------------------------------------------------

    private fun lower(source: SourceFile): String {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "extract",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        return requireNotNull(result.generatedSources().entries.firstOrNull {
            it.key.startsWith("DootahScreen_")
        }) {
            "nothing was generated. The screen was refused:\n${result.rejectionReport()}"
        }.value
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
        prelude: String = "",
        parameters: String = "",
        columnModifier: String = "",
        extraImports: String = "",
    ): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Box
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.Row
            import androidx.compose.foundation.layout.fillMaxWidth
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Button
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp
            import dev.dootah.Bundlable
            ${extraImports.trimIndent()}

            @Bundlable
            @Composable
            fun Screen($parameters) {
                ${prelude.trimIndent().replace("\n", "\n    ")}
                Column(${columnModifier}) {
                    ${body.trimIndent().replace("\n", "\n        ")}
                }
            }
        """.trimIndent(),
    )

    private companion object {
        const val COLOR_IMPORT = "import androidx.compose.ui.graphics.Color"
    }
}
