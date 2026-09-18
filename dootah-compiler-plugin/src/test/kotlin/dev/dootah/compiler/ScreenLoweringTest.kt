package dev.dootah.compiler

import org.junit.Assert.assertEquals
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

    @Test
    fun `one native image painter is worth publishing without a layout change`() {
        val generated = lower(SourceFile("Screen.kt", """
            package com.example
            import androidx.compose.runtime.Composable
            import androidx.compose.material3.Icon
            import androidx.compose.ui.res.painterResource
            object R { object drawable { val new_photo: Int = 1 } }
            @Composable
            fun Screen() {
                Icon(painter = painterResource(R.drawable.new_photo), contentDescription = "Photo")
            }
        """.trimIndent()))
        assertTrue(generated, generated.contains("PainterResourceProp(\"drawable:new_photo\")"))
    }

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

        assertTrue(
            generated,
            generated.contains(
                "BundleModifier.Padding(DimensionNode(0.0), DimensionNode(16.0), " +
                    "DimensionNode(0.0), DimensionNode(4.0))"
            ),
        )
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
        assertTrue(rejection, rejection.contains("Write a size as a literal"))
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

                    private fun total(unit: Int, quantity: Int): Int = unit * quantity

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

    /**
     * The widening milestone 1 needed: a callback the app already declares,
     * invoked with a value the bundle worked out for itself.
     *
     * Nothing about the app's memory becomes reachable. The bundle sends a
     * string it computed; the app calls its own parameter with it.
     */
    @Test
    fun `lowers a button that invokes a screen callback with a value`() {

        val generated = lower(
            screen(
                body = """Button(onClick = { navigateToPost("2") }) { Text("Read now") }""",
                parameters = "navigateToPost: (String) -> Unit",
            )
        )

        assertTrue(
            generated,
            generated.contains(
                """Command.InvokeCallback("navigateToPost", listOf(CallbackArgument.Text("2")))"""
            ),
        )
    }

    /** A value the screen was given travels just as a literal one does. */
    @Test
    fun `lowers a callback invoked with one of the screen's own values`() {

        val generated = lower(
            screen(
                body = """Button(onClick = { onPick(label) }) { Text("Pick") }""",
                parameters = "label: String, onPick: (String) -> Unit",
            )
        )

        assertTrue(
            generated,
            generated.contains("""Command.InvokeCallback("onPick", listOf("""),
        )
        assertTrue(generated, generated.contains("""arguments.string("label")"""))
    }

    /**
     * A `Long` goes as text, the same way a screen argument does on the way in.
     *
     * Every number inside a bundle is a JavaScript double. An id past 2^53 sent
     * as one arrives with its low digits gone, which is worse than not arriving.
     */
    @Test
    fun `sends a Long callback value as text`() {

        val generated = lower(
            screen(
                body = """Button(onClick = { onOpen(id) }) { Text("Open") }""",
                parameters = "id: Long, onOpen: (Long) -> Unit",
            )
        )

        assertTrue(generated, generated.contains("""CallbackArgument.Text("""))
        assertTrue(generated, generated.contains(""").toString())"""))
    }

    /**
     * A callback taking something a bundle cannot make stays out of reach.
     *
     * `Post` is the app's own type. Widening to `(String) -> Unit` must not
     * widen to this, or a bundle would be naming a call it has no value for --
     * so the parameter stays a native-only value and the call is refused.
     */
    @Test
    fun `refuses a callback taking a value Dootah cannot carry`() {

        val (generated, degraded) = keptNative(
            screen(
                body = """Button(onClick = { onPick(post) }) { Text("Pick") }""",
                parameters = "post: Post, onPick: (Post) -> Unit",
                declarations = "data class Post(val id: String)",
            )
        )

        assertTrue(degraded, degraded.contains("UNSUPPORTED_CALL_IN_HANDLER"))
        assertTrue(generated, !generated.contains("""Command.InvokeCallback("onPick""""))
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

    // ---- native components -----------------------------------------------

    @Test
    fun `keeps a styled Text native instead of dropping its styling`() {

        val generated = lower(
            screen("""Text("Title", color = Color(0xFF2196F3L))""", extraImports = COLOR_IMPORT)
        )

        assertTrue(generated, generated.contains("ComponentNode("))
        assertTrue(generated, generated.contains("ColorProp("))
        assertTrue(generated, !generated.contains("""TextNode("Title")"""))
    }

    @Test
    fun `keeps an unknown component native`() {

        val generated = lower(
            screen("""Icon("save")""", extraImports = "import androidx.compose.material3.Icon")
        )

        assertTrue(
            generated,
            generated.contains("""adapter = "androidx.compose.material3.Icon("""),
        )
    }

    /**
     * A component is named by the declaration it calls, never by where the call
     * was written or by which arguments it happened to supply.
     *
     * That is what lets a published bundle add, remove, reorder and repeat
     * components: there is no position to lose and nothing frozen into the name.
     */
    /**
     * A real tool button: an icon on a themed background, inside a button.
     *
     * The whole of a toolbox's shape in one call -- a component nested inside
     * another component's content, drawn from resources, wearing a modifier
     * whose colour is chosen by a value the bundle computes and whose shape is
     * a token.
     */
    @Test
    fun `carries an icon on a themed background inside a button`() {

        val lowered = lower(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.background
                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.foundation.layout.size
                    import androidx.compose.foundation.shape.CircleShape
                    import androidx.compose.material3.Icon
                    import androidx.compose.material3.IconButton
                    import androidx.compose.material3.MaterialTheme
                    import androidx.compose.runtime.Composable
                    import androidx.compose.ui.Modifier
                    import androidx.compose.ui.graphics.Color
                    import androidx.compose.ui.res.painterResource
                    import androidx.compose.ui.res.stringResource
                    import androidx.compose.ui.unit.dp

                    // The generated resource table, in the shape every Android
                    // build generates one. A resource identifier is a number
                    // this build chose, so the bundle carries the name instead
                    // and the app looks it up in its own table.
                    object R {
                        object drawable { val brush_24px: Int = 1 }
                        object string { val brush: Int = 2 }
                    }

                    @Composable
                    fun Screen(onPick: () -> Unit, erasing: Boolean) {
                        Column {
                            IconButton(onClick = onPick, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.brush_24px),
                                    contentDescription = stringResource(R.string.brush),
                                    modifier = Modifier.background(
                                        color = if (erasing) Color.Transparent
                                            else MaterialTheme.colorScheme.inversePrimary,
                                        shape = CircleShape,
                                    ),
                                )
                            }
                        }
                    }
                """.trimIndent(),
            )
        )

        assertTrue("the button was not carried: $lowered", lowered.contains("IconButton"))
        assertTrue("the icon was not carried: $lowered", lowered.contains("Icon"))
        assertTrue("the background was not carried: $lowered", lowered.contains("background"))
        assertTrue("the shape token was not carried: $lowered", lowered.contains("ShapeProp"))
        assertTrue("the theme colour was not carried: $lowered", lowered.contains("inversePrimary"))
        assertTrue("the drawable was not named: $lowered", lowered.contains("drawable:brush_24px"))
        assertTrue("the string was not named: $lowered", lowered.contains("string:brush"))
    }

    /**
     * A native component given a size, which is how almost every one is written.
     *
     * `Modifier.size(48.dp)` failed on both halves of itself. The frontend
     * resolves a bare `Modifier` to the *type*, so the walk up the chain never
     * found its end and ran off into a qualifier it could not read; and an
     * integer literal arrives boxed as a `Long` whatever it was written as, so
     * the list of numeric types `dp` accepted matched none of them.
     *
     * Together they refused every icon button in a real toolbox, which is a
     * whole screen kept native for the sake of its padding.
     */
    @Test
    fun `carries a size given to a native component`() {

        val lowered = lower(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.foundation.layout.size
                    import androidx.compose.material3.Icon
                    import androidx.compose.material3.IconButton
                    import androidx.compose.runtime.Composable
                    import androidx.compose.ui.Modifier
                    import androidx.compose.ui.unit.dp

                    @Composable
                    fun Screen(onPick: () -> Unit) {
                        Column {
                            IconButton(onClick = onPick, modifier = Modifier.size(48.dp)) {
                                Icon("tool")
                            }
                        }
                    }
                """.trimIndent(),
            )
        )

        assertTrue("the icon button was not carried: $lowered", lowered.contains("IconButton"))
        assertTrue("the size was not carried: $lowered", lowered.contains("48"))
        assertTrue("the size was not carried as a modifier: $lowered", lowered.contains("ModifierProp"))
    }

    @Test
    fun `names a component by its declaration, not by its call site`() {

        val generated = lower(
            screen(
                body = """
                    Icon("save")
                    Icon("open", tint = Color(0xFF00FF00L))
                """.trimIndent(),
                extraImports = "import androidx.compose.material3.Icon\n$COLOR_IMPORT",
            )
        )

        val adapters = Regex("""adapter = "([^"]+)"""")
            .findAll(generated)
            .map { match -> match.groupValues[1] }
            .toList()

        // Both calls name the same adapter, although they supply different
        // arguments -- and neither name mentions the arguments supplied.
        assertEquals(2, adapters.size)
        assertEquals(1, adapters.toSet().size)
        assertEquals("androidx.compose.material3.Icon(modifier|name|tint)", adapters.first())
    }

    @Test
    fun `gives a native component a screen parameter as its argument`() {

        val generated = lower(
            screen(
                body = """Icon(label)""",
                parameters = "label: String",
                extraImports = "import androidx.compose.material3.Icon",
            )
        )

        assertTrue(generated, generated.contains("ComponentNode("))
        // The parameter is read once into a local, and the component's argument
        // refers to it -- so a bundle can change what the component is given.
        assertTrue(generated, generated.contains("""val label: String = arguments.string("label")"""))
        assertTrue(generated, generated.contains("StringProp(label)"))
    }

    /**
     * The case the previous design had to refuse.
     *
     * A component's argument can now be a value the bundle works out for itself,
     * because the argument travels as data rather than being frozen into a copy
     * of the call. Changing what a component is given is an ordinary update.
     */
    @Test
    fun `lets a native component be given a value the bundle computes`() {

        val generated = lower(
            screen(
                body = """Icon(label)""",
                prelude = """val label = "save"""",
                extraImports = "import androidx.compose.material3.Icon",
            )
        )

        assertTrue(generated, generated.contains("ComponentNode("))
        assertTrue(generated, generated.contains("StringProp(label)"))
    }

    /**
     * A handler is named by what it does, so it survives being moved, having a
     * neighbour deleted, or being attached to a different component -- and two
     * components given the same handler share one entry.
     */
    @Test
    fun `names an action by what it does`() {

        val generated = lower(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.material3.IconButton
                    import androidx.compose.material3.Icon
                    import androidx.compose.runtime.Composable
                    import androidx.compose.runtime.MutableState

                    @Composable
                    fun Screen(menu: MutableState<Boolean>) {
                        IconButton(onClick = { menu.value = true }) { Icon("a") }
                        IconButton(onClick = { menu.value = true }) { Icon("b") }
                    }
                """.trimIndent(),
            )
        )

        val actions = Regex("""CallbackProp\("([^"]+)"""")
            .findAll(generated)
            .map { match -> match.groupValues[1] }
            .toList()

        assertEquals(listOf("menu.value=true", "menu.value=true"), actions)
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

    /**
     * The shape of Cahier's real `ToolBoxContent`, which is the screen Phase 3
     * exists to update over the air.
     *
     * It receives a view model, two `MutableState`s and a list of a domain type,
     * none of which can cross to a bundle; its body is three siblings rather
     * than one layout; and its components are icon buttons and app composables
     * with callbacks taking arguments. None of that has to be understood -- the
     * view model and the rest stay native, read only by components the app
     * draws, and what the bundle owns is the structure around them.
     */
    @Test
    fun `lowers a real toolbox screen by keeping its components native`() {

        val generated = lower(
            SourceFile(
                name = "Toolbox.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Box
                    import androidx.compose.material3.Icon
                    import androidx.compose.material3.IconButton
                    import androidx.compose.runtime.Composable
                    import androidx.compose.runtime.MutableState
                    import androidx.compose.ui.Modifier

                    class DrawingViewModel {
                        fun changeBrush(brush: String) {}
                        fun setEraserMode(on: Boolean) {}
                    }

                    data class CustomBrush(val name: String)

                    @Composable
                    fun BrushesMenu(
                        expanded: Boolean,
                        onDismissRequest: () -> Unit,
                        onBrushChange: (String) -> Unit,
                        customBrushes: List<CustomBrush>,
                    ) {}

                    @Composable
                    fun ToolBoxContent(
                        viewModel: DrawingViewModel,
                        brushesMenuExpanded: MutableState<Boolean>,
                        customBrushes: List<CustomBrush>,
                        onColorPickerClick: () -> Unit,
                        isEraserMode: Boolean,
                        modifier: Modifier = Modifier,
                    ) {
                        Box(modifier = modifier) {
                            IconButton(onClick = { brushesMenuExpanded.value = true }) {
                                Icon("brush")
                            }
                            BrushesMenu(
                                expanded = brushesMenuExpanded.value,
                                onDismissRequest = { brushesMenuExpanded.value = false },
                                onBrushChange = { newBrush ->
                                    viewModel.changeBrush(newBrush)
                                    brushesMenuExpanded.value = false
                                },
                                customBrushes = customBrushes,
                            )
                        }
                        IconButton(
                            onClick = {
                                onColorPickerClick()
                                viewModel.setEraserMode(false)
                            },
                        ) {
                            Icon("palette")
                        }
                        Box {
                            IconButton(onClick = { brushesMenuExpanded.value = true }) {
                                Icon("size")
                            }
                        }
                    }
                """.trimIndent(),
            )
        )

        // Three siblings, kept as siblings.
        assertTrue(generated, generated.contains("FragmentNode("))

        // Two of them are boxes the bundle owns and can rearrange.
        assertTrue(generated, generated.contains("BoxNode("))

        // A handler that calls one of the screen's own callbacks is named by
        // that callback, not by the `invoke` it resolves to -- the other pass
        // sees the parameter's name, and the two have to agree.
        assertTrue(
            generated,
            generated.contains("""onColorPickerClick();viewModel.setEraserMode(false)"""),
        )

        // The components stayed native, as instances of one adapter each.
        val adapters = Regex("""adapter = "([^"]+)"""")
            .findAll(generated)
            .map { match -> match.groupValues[1] }
            .toList()

        // Three icon buttons, and they are three instances of the *same*
        // adapter. Under the previous design they were three separately named
        // components, which is why deleting one silently rebound the others.
        assertEquals(3, adapters.count { it.startsWith("androidx.compose.material3.IconButton") })
        assertEquals(
            1,
            adapters.filter { it.startsWith("androidx.compose.material3.IconButton") }
                .toSet()
                .size,
        )
        assertTrue(adapters.toString(), adapters.any { it.startsWith("com.example.BrushesMenu") })

        // The view model, the MutableState and the list never become values the
        // bundle holds. They are named, and the app supplies the objects.
        assertTrue(generated, generated.contains("""HandleProp("customBrushes")"""))
        assertTrue(generated, generated.contains("""StateProp("brushesMenuExpanded")"""))

        // A handler taking a value from the component is still native code, and
        // is named by what it does rather than by where it was written.
        // The argument is written positionally, because a Kotlin function type
        // has no parameter names -- two sources that named it differently
        // describe the same action.
        assertTrue(generated, generated.contains("viewModel.changeBrush("))
        assertTrue(generated, generated.contains("0);brushesMenuExpanded.value=false"))

        // The only value that crossed is the one a bundle can carry.
        val read = Regex("""arguments\.[a-zA-Z]+\("([^"]+)"\)""")
            .findAll(generated)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(setOf("isEraserMode"), read)
    }

    // ---- refusals -------------------------------------------------------

    @Test
    fun `refuses a value of a type it cannot carry`() {

        // The local is held natively and nothing else changes. It used to refuse
        // the screen outright, which on real apps was the single largest reason
        // a screen could not be published -- a coroutine scope or a view model
        // most of the screen never read.
        val (generated, kept) = keptNative(
            screen(
                body = """Text("x")""",
                prelude = "val at = 'x'",
            )
        )

        assertTrue(generated, generated.contains("TextNode"))
        assertTrue(kept, kept.contains("not a number, String or Boolean"))
    }

    @Test
    fun `keeps a component whose argument the bundle cannot describe native`() {

        // A `Note` is not a value a bundle can carry, and neither is anything
        // computed from one. Nothing is lost: the Text still renders, with the
        // value its caller passed. It simply is not part of what can change.
        val (generated, kept) = keptNative(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable

                    data class Note(val title: String)

                    @Composable
                    fun Screen(note: Note) {
                        Column {
                            Text("Title: " + note.title)
                            Text("always here")
                        }
                    }
                """.trimIndent(),
            )
        )

        // The first Text keeps rendering the value its caller passed, as native
        // code. The second is still the bundle's, and so is the Column -- which
        // is what stops one unreachable value from costing the whole screen.
        assertTrue(generated, generated.contains("ColumnNode("))
        assertTrue(generated, generated.contains("""TextNode("always here")"""))
        assertTrue(generated, generated.contains("adapter = \""))
        assertTrue(kept, kept.contains("read from something this screen holds natively"))
    }

    @Test
    fun `refuses computing with a parameter it cannot carry`() {

        val (generated, kept) = keptNative(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable

                    data class Note(val title: String)

                    @Composable
                    fun Screen(note: Note) {
                        Column {
                            Text("Note: " + note)
                            Text("always here")
                        }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(generated, generated.contains("ColumnNode("))
        assertTrue(generated, generated.contains("""TextNode("always here")"""))
        assertTrue(kept, kept.contains("`note`"))
    }

    @Test
    fun `refuses a layout argument it does not bundle`() {

        // The arrangement is not describable, so the Column stops being a layout
        // the bundle arranges and becomes a region the app draws as written. The
        // screen still publishes; that one Column is simply not what an update
        // can change.
        //
        // The argument used to be a placeholder the stubs typed as an `Int`,
        // which no real Compose layout has. `propagateMinConstraints` is a real
        // one Dootah does not bundle, and like the placeholder it is a value the
        // adapter can carry -- so this still exercises the rung of the ladder it
        // was written for.
        //
        // An unbundled *arrangement* cannot reach this rung: its value is an
        // `Arrangement`, which is not something an adapter can be handed, so a
        // layout refused for one degrades further down the ladder instead. That
        // is covered separately in LayoutArgumentLoweringTest.
        val (generated, kept) = keptNative(
            screen(body = """Box(propagateMinConstraints = true) { Text("x") }""")
        )

        // The Box stops being a layout the bundle arranges and becomes a
        // component it places -- and its children stay remote, which is the
        // whole point: the layout is native, the content is not.
        assertTrue(generated, generated.contains("adapter = \"androidx.compose.foundation.layout.Box"))
        assertTrue(generated, generated.contains("""TextNode("x")"""))
        assertTrue(kept, kept.contains("propagateMinConstraints"))
        assertTrue(kept, kept.contains("a component the bundle places"))
    }

    @Test
    fun `keeps a button wired to app code native`() {

        // The button keeps working exactly as written. What it *does* is app
        // code that cannot cross into a bundle -- but the button itself is now a
        // component the bundle places, so where it sits and what it says are
        // still updatable. Only the handler stays behind, by name.
        val generated = lower(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.Button
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable

                    fun checkout() {}

                    @Composable
                    fun Screen() {
                        Column {
                            Button(onClick = { checkout() }) { Text("Buy") }
                        }
                    }
                """.trimIndent(),
            )
        )

        assertTrue(
            generated,
            generated.contains("""adapter = "androidx.compose.material3.Button("""),
        )
        assertTrue(generated, generated.contains("""CallbackProp("checkout()", 0)"""))

        // The label is inside the button's content slot, described by the
        // bundle, so it can change without the app being rebuilt.
        assertTrue(generated, generated.contains("""TextNode("Buy")"""))
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

                    fun checkout() {}

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


    // ---- worth shipping -------------------------------------------------

    /**
     * A screen that lowered to nothing but a copy of itself.
     *
     * Partial lowering means almost anything comes out of lowering as
     * *something*, and the something can be a single region kept exactly as
     * written. Publishing that costs a download and a risk and changes nothing
     * anyone can see, so it is refused -- and refused as its own outcome, not as
     * a failure, because there is nothing wrong with the screen.
     */
    @Test
    fun `refuses to publish a screen that is one native region and nothing else`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(
                SourceFile(
                    name = "Screen.kt",
                    contents = """
                        package com.example

                        import androidx.compose.foundation.layout.Column
                        import androidx.compose.runtime.Composable

                        // Not Compose's Text. Resolving by name alone would
                        // bundle this as though it were, which is why the plugin
                        // matches on the resolved symbol.
                        fun Text(text: String) {}

                        @Composable
                        fun Screen() {
                            Column {
                                Text("not Compose's Text")
                            }
                        }
                    """.trimIndent(),
                )
            ),
            mode = "extract",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        assertEquals("NOT_WORTH_SHIPPING", result.discoveryOutcomes()["com.example.Screen"])

        assertTrue(
            "a screen worth nothing was still generated",
            result.generatedSources().keys.none { it.startsWith("DootahScreen_") },
        )
    }

    /** One describable node is enough, however much of the rest is native. */
    @Test
    fun `publishes a screen with a single describable node among native ones`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(
                SourceFile(
                    name = "Screen.kt",
                    contents = """
                        package com.example

                        import androidx.compose.foundation.layout.Column
                        import androidx.compose.material3.Text
                        import androidx.compose.runtime.Composable

                        @Composable
                        fun Card(label: String) {}

                        @Composable
                        fun Screen() {
                            Column {
                                Card(label = "one")
                                Text("two")
                            }
                        }
                    """.trimIndent(),
                )
            ),
            mode = "extract",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        assertEquals("LOWERED", result.discoveryOutcomes()["com.example.Screen"])
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

    /**
     * Lowers a screen and returns what it had to keep native.
     *
     * The shape most of these tests take now. Before partial lowering the
     * question was "was the screen refused"; the question worth asking is "which
     * part of it stopped being updatable, and did the rest survive".
     */
    private fun keptNative(source: SourceFile): Pair<String, String> {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "extract",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        val generated = requireNotNull(result.generatedSources().entries.firstOrNull {
            it.key.startsWith("DootahScreen_")
        }) {
            "nothing was generated. The screen was refused:\n${result.rejectionReport()}"
        }.value

        return generated to result.degradationReport().orEmpty()
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
        declarations: String = "",
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
            ${extraImports.trimIndent()}

            @Composable
            fun Screen($parameters) {
                ${prelude.trimIndent().replace("\n", "\n    ")}
                Column(${columnModifier}) {
                    ${body.trimIndent().replace("\n", "\n        ")}
                }
            }

            ${declarations.trimIndent()}
        """.trimIndent(),
    )

    private companion object {
        const val COLOR_IMPORT = "import androidx.compose.ui.graphics.Color"
    }
}
