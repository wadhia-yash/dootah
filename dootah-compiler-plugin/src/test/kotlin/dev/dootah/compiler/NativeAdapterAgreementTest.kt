package dev.dootah.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a bundle may name is decided twice, by two separate compilations: the
 * extraction pass names the adapters, actions, values and resources a bundle
 * refers to, and the app's own build registers what each one is. The two run
 * over two *different versions of the source* -- the one the APK was built from,
 * and the edited one a bundle is published from -- and a name is the only thing
 * joining them.
 *
 * These run both passes and check the names line up, including across the edits
 * an update exists to make. They are the only thing standing between the design
 * and a component silently missing from a shipped screen.
 */
class NativeAdapterAgreementTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the app registers everything the bundle refers to`() {

        val source = screenWithNativeComponents()

        assertAgreement(installed = source, published = source)
    }

    /**
     * An update exists precisely because the source changed, so a name that
     * depends on where a component sits in the file is renamed by any edit above
     * it -- and the app, still registering the old names, quietly draws nothing
     * where those components were.
     *
     * This is what happened on a device: an unrelated edit ten lines higher made
     * a native component disappear from a shipped screen.
     */
    @Test
    fun `names survive an edit elsewhere in the file`() {

        assertAgreement(installed = screenWithNativeComponents(), published = editedScreen())
    }

    /**
     * Deleting one of several identical components.
     *
     * The design this replaced named a component by its position among the
     * components sharing its shape, so three identical icon buttons were #0, #1
     * and #2 -- and deleting the middle one slid the third into its place. The
     * bundle then asked for #1 and the installed app, which still had all three,
     * handed back a different component under a name that existed. Nothing was
     * missing, so nothing was reported, and the screen drew the wrong icon.
     *
     * Guarding against that meant refusing the whole screen whenever the two
     * sources disagreed about how many of a shape there were, which is to say
     * whenever anyone added, removed or reordered a component.
     *
     * An adapter has no position, so this is simply an update.
     */
    @Test
    fun `removing one of several identical components is an ordinary update`() {

        assertAgreement(installed = toolbar(buttons = 3), published = toolbar(buttons = 2))
    }

    @Test
    fun `repeating a component more often than the source did is an ordinary update`() {

        assertAgreement(installed = toolbar(buttons = 2), published = toolbar(buttons = 5))
    }

    @Test
    fun `reordering components is an ordinary update`() {

        assertAgreement(installed = toolbar(buttons = 3), published = toolbar(buttons = 3, reversed = true))
    }

    /**
     * A component with a receiver, called without one of its defaults.
     *
     * The two passes read a call's parameters from different places, and an
     * off-by-the-receiver-count once made the app register `Star(label|tint)`
     * while the bundle asked for `Star(label)`. Naming an adapter after the
     * declaration rather than the call removes the arguments from the name
     * entirely, which is what makes changing them an update rather than a break.
     */
    @Test
    fun `names agree for a component reached through an object`() {

        assertAgreement(
            installed = screenWithObjectComponent(),
            published = screenWithObjectComponent(),
            expect = "androidx.compose.material3.Icons.Star",
        )
    }

    /**
     * A component that reads the scope of the layout around it.
     *
     * An adapter is a standalone lambda with no receiver, so a component
     * declared on `ColumnScope` -- or one whose modifier calls `weight` -- cannot
     * be built at all. The app's build used to fail in the backend with
     * `No mapping for symbol: ${'$'}this${'$'}Column`. Both passes have to refuse it: the
     * app must not register what it cannot build, and the bundle must not name
     * what the app did not register.
     *
     * What the screen does instead is keep the layout holding it whole, which is
     * the remedy the refusal already named. So the thing to assert is that the
     * component was not placed -- not that the screen was refused, which was the
     * old answer to a question about one component in it.
     */
    @Test
    fun `neither pass places a component that reads the layout's scope`() {

        listOf(
            "a component declared on ColumnScope" to screenWithScopedComponent(),
            "a component whose modifier uses weight" to screenWithWeightedComponent(),
        ).forEach { (description, source) ->

            val extracted = extract(source)

            assertEquals(
                "the bundle names something the app did not register, for $description",
                emptyList<String>(),
                extracted.namesUsed().filterNot { name -> intercept(source).registers(name) },
            )

            assertTrue(
                "$description was placed rather than kept inside a native region",
                extracted.generatedSources().values.none { generated ->
                    "ComponentNode" in generated
                },
            )
        }
    }

    /**
     * The same scope, read by the layout that introduced it.
     *
     * `Row { Text(modifier = Modifier.weight(1f)) }` kept native reads a
     * `RowScope` throughout, and every bit of it belongs to the `Row` being
     * lifted, so the app builds it without complaint and registers it. Refusing
     * it here on the grounds that a `this` appeared cost the screen for nothing
     * -- and cost most screens, because a `LazyColumn`, a `Scaffold` or a `Card`
     * kept native reads its own scope somewhere inside almost every time.
     */
    @Test
    fun `a region that reads only the scope it introduced is placed`() {

        val source = screenWithSelfScopedRegion()
        val extracted = extract(source)

        assertEquals(
            "the bundle names something the app did not register",
            emptyList<String>(),
            extracted.namesUsed().filterNot { name -> intercept(source).registers(name) },
        )

        assertEquals(
            "the screen was refused over a scope that travels with the region",
            mapOf("com.example.Screen" to "LOWERED"),
            extracted.discoveryOutcomes(),
        )

        assertTrue(
            "the region was not placed",
            extracted.namesUsed().any { name -> name.startsWith("androidx.compose.material3.Chip") },
        )
    }

    /**
     * The shape a real toolbox takes, and every kind of name at once.
     *
     * A view model and a list of domain objects routed in as handles, a
     * `MutableState` read inside the composition, a handler that takes a value
     * from the component it is attached to, and a handler that calls one of the
     * screen's own callbacks. Published from a source with the middle icon
     * button deleted, which is the edit that used to draw the wrong icon.
     */
    @Test
    fun `a real toolbox agrees across deleting one of its buttons`() {

        assertAgreement(
            installed = toolbox(buttons = 3),
            published = toolbox(buttons = 2),
            expect = "com.example.BrushesMenu",
        )
    }

    @Test
    fun `a handler taking a value from its component is registered`() {

        val compiled = intercept(toolbox(buttons = 3))
            .compiledClassText("com/example/ScreenKt.class")

        // Named by what it does, with the argument written positionally: a
        // Kotlin function type has no parameter names, so two sources that
        // named it differently describe the same action.
        assertTrue(compiled, compiled.contains("viewModel.changeBrush("))
    }

    /**
     * What a component is, and what it is not.
     *
     * Both passes walk a screen's body looking for composable calls, and both
     * have to reach the same answer about each one or the bundle names something
     * the app does not have. Two kinds of composable call are not components at
     * all, and treating them as one produced code the Compose backend refused:
     *
     *  - a composable that returns a value is *read*, not placed, so an adapter
     *    built from it is a lambda whose whole body is an expression it throws
     *    away;
     *  - the content a component wraps is drawn by the bundle, not run as an
     *    action, so copying it into a handler puts a composable call somewhere
     *    that has no composer to make it with.
     *
     * The second of those only appeared in an app build, because the composable
     * lambda the real Compose compiler hands the backend is a
     * `ComposableFunction0` while the one these stubs produce is an annotated
     * `Function0`. The classification now reads the parameter's type, which says
     * the same thing in both worlds -- and `ToolboxRegressionScreen` in the demo
     * app compiles against the real one.
     */
    @Test
    fun `a composable that produces a value is not a component`() {

        val compiled = intercept(screenReadingTheTheme())
            .compiledClassText("com/example/ScreenKt.class")

        assertTrue("nothing was registered at all", compiled.contains("dootahAdapter"))

        assertFalse(
            "the theme was registered as a component to place",
            compiled.contains("<get-colorScheme>"),
        )
    }

    @Test
    fun `the content a component wraps is not registered as an action`() {

        val compiled = intercept(toolbar(buttons = 3))
            .compiledClassText("com/example/ScreenKt.class")

        assertTrue("nothing was registered at all", compiled.contains("dootahCapability"))

        assertFalse(
            "an icon button's content was registered as an action",
            compiled.contains("Icon(\"tool1\")"),
        )
    }

    /**
     * Content handed the scope it sits in is still content.
     *
     * `Button` takes `@Composable RowScope.() -> Unit`, and a rule that only
     * recognised content taking no arguments called that a handler -- so the
     * button's label was copied into a plain lambda and the Compose backend
     * refused the build. The extraction pass had always read it as content, so
     * the two passes disagreed about what a button even was.
     */
    @Test
    fun `content given a scope is still content`() {

        assertAgreement(installed = screenWithScopedContent(), published = screenWithScopedContent())

        val compiled = intercept(screenWithScopedContent())
            .compiledClassText("com/example/ScreenKt.class")

        assertFalse(
            "a component's label was registered as an action",
            compiled.contains("Text(\"Save\")"),
        )
    }

    @Test
    fun `a registered adapter is a composable lambda`() {

        val compiled = intercept(screenWithNativeComponents())
            .compiledClassText("com/example/ScreenKt.class")

        assertTrue("no adapters were registered", compiled.contains("dootahAdapter"))

        // The component is reachable from the compiled class both as the
        // fallback body and as the adapter.
        assertTrue("the native component is missing", compiled.contains("Icon"))
    }

    @Test
    fun `passes the screen's values, callbacks and modifier through`() {

        val compiled = intercept(screenWithNativeComponents())
            .compiledClassText("com/example/ScreenKt.class")

        assertTrue(
            "the screen's modifier was not carried",
            compiled.contains("dootahModifiedArguments"),
        )
        assertTrue("the screen's values were not carried", compiled.contains("title"))
        assertTrue("the screen's callbacks were not carried", compiled.contains("dootahCallbacks"))
        assertTrue("the callback name was not carried", compiled.contains("onSave"))
    }

    // ---- harness ---------------------------------------------------------

    /**
     * Builds the app from one version of the source and the bundle from another,
     * and checks the app can supply everything the bundle asks for.
     *
     * The two compilations are deliberately separate, because that is what they
     * are in life: the APK is on a device and the bundle is published later.
     */
    /**
     * A region kept exactly as written, named by its own text.
     *
     * The two passes see this one very differently. The extraction pass reads a
     * styled `Text` the developer wrote; the app's pass sees the block Kotlin
     * lowered it into, where every defaulted argument has been hoisted into a
     * temporary first. Both have to arrive at the same name, and when they did
     * not, the bundle named regions the app had never registered -- caught by
     * contract validation rather than by a device, but only just.
     */
    @Test
    fun `a region kept as written is named the same by both passes`() {

        val source = screenWithStyledText()

        assertAgreement(installed = source, published = source, expect = "!")
    }

    /** And keeps that name when something unrelated moves above it. */
    @Test
    fun `a region kept as written survives an edit elsewhere`() {

        assertAgreement(
            installed = screenWithStyledText(),
            published = screenWithStyledText(extra = """Text("added above")"""),
            expect = "!",
        )
    }

    private fun screenWithStyledText(extra: String = ""): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.foundation.layout.padding
            import androidx.compose.ui.unit.dp
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun Screen() {
                Column {
                    $extra
                    Text(
                        modifier = Modifier.padding(16.dp),
                        text = "styled",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        """.trimIndent(),
    )

    private fun assertAgreement(
        installed: SourceFile,
        published: SourceFile,
        expect: String? = null,
    ) {

        val app = intercept(installed)
        val bundle = extract(published)

        val used = bundle.namesUsed()

        assertTrue("the bundle names nothing native", used.isNotEmpty())

        if (expect != null) {
            assertTrue(
                "the bundle does not name $expect: $used",
                used.any { name -> name.startsWith(expect) },
            )
        }

        assertEquals(
            "the app cannot supply everything the bundle asks for.\nbundle asks for: $used",
            emptyList<String>(),
            used.filterNot { name -> app.registers(name) },
        )
    }

    private fun intercept(source: SourceFile): CompilationResult {
        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "intercept",
        )
        assertTrue("interception failed: ${result.messages}", result.succeeded)
        return result
    }

    private fun extract(source: SourceFile): CompilationResult {
        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "extract",
        )
        assertTrue("extraction failed: ${result.messages}", result.succeeded)
        return result
    }

    /**
     * Every native name a published bundle refers to.
     *
     * Adapters, actions, the screen's own values routed into a component, and
     * resources. All four are names the installed app has to have registered,
     * and all four are read out of the generated bundle the same way.
     */
    private fun CompilationResult.namesUsed(): List<String> {

        val generated = generatedSources().values.joinToString("\n")

        return NAME_PATTERNS
            .flatMap { pattern -> pattern.findAll(generated).map { it.groupValues[1] } }
            // The generated file holds these as Kotlin string literals, so a
            // `$` in a name -- an action's positional argument -- is escaped.
            .map { name -> name.replace("\\${'$'}", "${'$'}") }
            .distinct()
            .sorted()
    }

    /** Whether the compiled app carries this name as a registered constant. */
    private fun CompilationResult.registers(name: String): Boolean =
        compiledClassText("com/example/ScreenKt.class").contains(name)

    private companion object {

        val NAME_PATTERNS = listOf(
            Regex("""adapter = "([^"]+)""""),
            Regex("""CallbackProp\("([^"]+)""""),
            Regex("""HandleProp\("([^"]+)""""),
            Regex("""StateProp\("([^"]+)""""),
            Regex("""PainterResourceProp\("([^"]+)""""),
            Regex("""StringResourceProp\("([^"]+)""""),
            Regex("""DimensionNode\(anchor = "([^"]+)""""),
        )
    }

    /**
     * The app's own spacing scale, named by a bundle and supplied by the APK.
     *
     * The shape this was built for, taken from a real app: a `Padding` class
     * hung off `MaterialTheme` by an extension property, read as
     * `MaterialTheme.padding.small`. Nothing about the chain is written down --
     * both passes have to arrive at the same name from the resolved symbols, and
     * this is the test that says they do.
     */
    @Test
    fun `a spacing scale the app owns is named the same by both passes`() {

        assertAgreement(
            installed = screenWithAppSpacing(),
            published = screenWithAppSpacing(),
            expect = "androidx.compose.material3.MaterialTheme.padding.small",
        )
    }

    /**
     * The same, across the edit an update exists to make.
     *
     * The published source has moved the spacing off the modifier and onto the
     * arrangement. The name has to be unchanged, because it names the property
     * rather than the argument it was read into.
     */
    @Test
    fun `a spacing name survives the layout around it changing`() {

        assertAgreement(
            installed = screenWithAppSpacing(),
            published = screenWithAppSpacing(edited = true),
            expect = "androidx.compose.material3.MaterialTheme.padding.small",
        )
    }

    /**
     * A length declared at the top of a file, which is how most apps write one.
     *
     * `private val CardHeight = 96.dp` beside the composable that uses it is the
     * commonest form there is. It has a package and a name, so both compilations
     * can arrive at it -- unlike the local `val` it looks exactly like.
     */
    @Test
    fun `a top-level length constant is named the same by both passes`() {

        assertAgreement(
            installed = screenWithFileConstant(),
            published = screenWithFileConstant(edited = true),
            expect = "com.example.RowHeight",
        )
    }

    private fun screenWithFileConstant(edited: Boolean = false): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.height
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp
            import dev.dootah.Bundlable

            private val RowHeight = 96.dp

            @Bundlable
            @Composable
            fun Screen(title: String, modifier: Modifier = Modifier) {
                Column(modifier = modifier) {
                    Text(title, modifier = Modifier.${if (edited) "padding" else "height"}(RowHeight))
                }
            }
        """.trimIndent(),
    )

    /**
     * A screen using the app's own spacing scale, the way a real one does.
     *
     * [edited] is the published version, with the spacing moved from the
     * padding onto the arrangement -- the kind of edit a bundle exists to make,
     * and one that may not rename anything.
     */
    private fun screenWithAppSpacing(edited: Boolean = false): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Icon
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
            fun Screen(title: String, modifier: Modifier = Modifier) {
                Column(
                    modifier = modifier${if (edited) "" else ".padding(MaterialTheme.padding.small)"},
                    ${if (edited) "verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)," else ""}
                ) {
                    Icon(title)
                    Text(title)
                }
            }
        """.trimIndent(),
    )

    private fun screenWithNativeComponents(): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Button
            import androidx.compose.material3.Icon
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.graphics.Color
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun Screen(
                title: String,
                onSave: () -> Unit,
                modifier: Modifier = Modifier,
            ) {
                Column(modifier = modifier) {
                    Icon(title)
                    Text(title, color = Color(0xFF2196F3L))
                    Button(onClick = onSave) { Text("Save") }
                }
            }
        """.trimIndent(),
    )

    /** A toolbar of [buttons] identical icon buttons, side by side. */
    private fun toolbar(buttons: Int, reversed: Boolean = false): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Icon
            import androidx.compose.material3.IconButton
            import androidx.compose.runtime.Composable
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun Screen(onPick: () -> Unit) {
                Column {
        """.trimIndent() +
            (1..buttons).let { range -> if (reversed) range.reversed() else range.toList() }
                .joinToString("\n") {
                """
                    IconButton(onClick = onPick) { Icon("tool$it") }
                """.trimIndent()
            } +
            """
                }
            }
            """.trimIndent(),
    )

    /**
     * A toolbox of [buttons] icon buttons beside a menu that needs the screen's
     * un-serialisable parameters.
     */
    private fun toolbox(buttons: Int): SourceFile {

        val tools = (1..buttons).joinToString("\n") { index ->
            """
                    IconButton(
                        onClick = {
                            onColorPickerClick()
                            viewModel.setEraserMode(false)
                        },
                    ) { Icon("tool$index") }
            """.trimIndent()
        }

        return SourceFile(
            name = "Screen.kt",
            contents = """
                package com.example

                import androidx.compose.foundation.layout.Box
                import androidx.compose.material3.Icon
                import androidx.compose.material3.IconButton
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.MutableState
                import dev.dootah.Bundlable

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

                @Bundlable
                @Composable
                fun Screen(
                    viewModel: DrawingViewModel,
                    menuExpanded: MutableState<Boolean>,
                    customBrushes: List<CustomBrush>,
                    onColorPickerClick: () -> Unit,
                ) {
                    Box {
                        BrushesMenu(
                            expanded = menuExpanded.value,
                            onDismissRequest = { menuExpanded.value = false },
                            onBrushChange = { newBrush ->
                                viewModel.changeBrush(newBrush)
                                menuExpanded.value = false
                            },
                            customBrushes = customBrushes,
                        )
                    }
                $tools
                }
            """.trimIndent(),
        )
    }



    /** A screen whose component hands its content the scope it is laid out in. */
    private fun screenWithScopedContent(): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Chip
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun Screen(onSave: () -> Unit) {
                Column {
                    Chip(onClick = onSave) { Text("Save") }
                }
            }
        """.trimIndent(),
    )

    /** A screen that reads a themed colour, which is a value rather than a component. */
    private fun screenReadingTheTheme(): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Icon
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.runtime.Composable
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun Screen(label: String) {
                Column {
                    Icon(label, tint = MaterialTheme.colorScheme.primary)
                }
            }
        """.trimIndent(),
    )

    /**
     * A screen whose native component is reached through an object and is called
     * without its defaulted `tint`.
     */
    private fun screenWithObjectComponent(): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Icons
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun Screen(
                title: String,
                modifier: Modifier = Modifier,
            ) {
                Column(modifier = modifier) {
                    Icons.Star(label = title)
                    Text(title)
                }
            }
        """.trimIndent(),
    )

    /**
     * A screen whose native component is declared on `ColumnScope` and is called
     * without either of its defaulted arguments.
     */
    private fun screenWithScopedComponent(): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Badge
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun Screen(
                title: String,
                modifier: Modifier = Modifier,
            ) {
                Column(modifier = modifier) {
                    Badge(label = title)
                    Text(title)
                }
            }
        """.trimIndent(),
    )

    /**
     * The shape that drove all of this: a screen whose native part is a lazy list.
     *
     * `LazyColumn { postContentItems(post) }` -- an app's own extension on
     * `LazyListScope`, which is how every long screen in the corpus is written.
     * The scope belongs to the `LazyColumn` being kept native, so the app builds
     * it and registers it, and a bundle may name it. Refusing it cost 132 of the
     * corpus's 172 screens that stopped here, none of them for a reason that
     * survived being looked at.
     *
     * Asserted through agreement rather than through an outcome, because what
     * matters is not that this screen lowers but that the two passes say the
     * same thing about it.
     */
    @Test
    fun `a lazy list that reads its own scope agrees across an edit`() {

        // The list itself is placed, so a bundle decides its modifiers and which
        // entries it holds; the app's own `articleItems` travels as a region the
        // app performs against the real scope. Both names have to be the app's.
        assertAgreement(
            installed = screenWithLazyList(extraItem = false),
            published = screenWithLazyList(extraItem = true),
            expect = "androidx.compose.foundation.lazy.LazyColumn(",
        )

        assertTrue(
            "the app's own list-building did not travel as a region it performs",
            extract(screenWithLazyList(extraItem = true)).namesUsed()
                .any { name -> name.startsWith("!com.example.articleItems@") },
        )
    }


    /**
     * A screen whose native part is a lazy list reading its own scope.
     *
     * [extraItem] adds a row to the part that stays remote, which is the
     * ordinary edit a bundle ships: the frozen list is untouched, so both passes
     * have to name it identically across the two compilations.
     */
    private fun screenWithLazyList(extraItem: Boolean): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.lazy.LazyColumn
            import androidx.compose.foundation.lazy.LazyListScope
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import dev.dootah.Bundlable

            fun LazyListScope.articleItems(title: String) {
                item { Text(title) }
            }

            @Bundlable
            @Composable
            fun Screen(
                title: String,
                modifier: Modifier = Modifier,
            ) {
                Column(modifier = modifier) {
                    Text(title)
            ${if (extraItem) "        Text(title)" else ""}
                    LazyColumn { articleItems(title) }
                }
            }
        """.trimIndent(),
    )

    /**
     * The shape of a real article screen, whole.
     *
     * A list the app owns, given the app's own padding by name, building its
     * entries from the app's own extension on the scope -- and an entry the
     * bundle describes, added above them. Every kind of name this milestone
     * introduced is in it at once, which is the point: the two passes see two
     * versions of this file and have to agree on all of them.
     *
     * Published from a version with a banner the installed build never had,
     * because adding an entry is the update this is for.
     */
    @Test
    fun `an article list agrees across an entry being added to it`() {

        val installed = articleScreen(banner = false)
        val published = articleScreen(banner = true)

        assertAgreement(
            installed = installed,
            published = published,
            expect = "androidx.compose.foundation.lazy.LazyColumn(",
        )

        val extracted = extract(published)

        assertTrue(
            "the app's own list-building did not travel as a region it performs",
            extracted.namesUsed().any { it.startsWith("!com.example.postContentItems@") },
        )

        // The whole reason the screen is worth shipping: the padding stays the
        // app's, by name, while the entries around it become the bundle's.
        assertTrue(
            "the app's own spacing did not travel as a name",
            "com.example.defaultSpacerSize" in extracted.requirementsFragment(),
        )

        assertEquals(
            mapOf("com.example.PostContent" to "LOWERED"),
            extracted.discoveryOutcomes(),
        )

        // As the four edges the renderer reads, not as the word Compose's
        // overload happened to use. Sent as `horizontal` it arrived under a name
        // nothing looked up, the padding resolved to zero, and the article drew
        // hard against both edges of the screen -- on a device, having passed
        // every check up to that point.
        val generated = extracted.generatedSources().values.joinToString("\n")

        assertTrue(
            "the padding did not travel as the edges the renderer reads:\n$generated",
            Regex("""ModifierOpNode\("padding", mapOf\(.*"start" to AnchorProp\("com\.example\.defaultSpacerSize"\)""")
                .containsMatchIn(generated),
        )
        assertTrue(
            "the padding named an argument the renderer does not read:\n$generated",
            "\"horizontal\" to" !in generated,
        )
    }


    /**
     * An article screen shaped like JetNews's, to the argument.
     *
     * [banner] adds an entry the bundle describes above the app's own ones,
     * which is the ordinary edit an update ships and the one that must not
     * disturb any of the names around it.
     */
    private fun articleScreen(banner: Boolean): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.PaddingValues
            import androidx.compose.foundation.layout.padding
            import androidx.compose.foundation.lazy.LazyColumn
            import androidx.compose.foundation.lazy.LazyListScope
            import androidx.compose.foundation.lazy.LazyListState
            import androidx.compose.foundation.lazy.rememberLazyListState
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.Dp
            import dev.dootah.Bundlable

            val defaultSpacerSize = Dp(16f)

            class Post(val title: String)

            fun LazyListScope.postContentItems(post: Post) {
                item { Text(post.title) }
            }

            @Bundlable
            @Composable
            fun PostContent(
                post: Post,
                modifier: Modifier = Modifier,
                contentPadding: PaddingValues = PaddingValues(Dp(0f)),
                state: LazyListState = rememberLazyListState(),
            ) {
                LazyColumn(
                    contentPadding = contentPadding,
                    modifier = modifier.padding(horizontal = defaultSpacerSize),
                    state = state,
                ) {
            ${if (banner) "            item { Text(\"Editor's pick\") }" else ""}
                    postContentItems(post)
                }
            }
        """.trimIndent(),
    )

    /**
     * A component whose own content lambda hands it the scope it reads.
     *
     * `Chip` takes `@Composable RowScope.() -> Unit`, so the `weight` inside it
     * resolves against a receiver the `Chip` call introduces. Lifting the call
     * takes that receiver with it, which is exactly the case the app's pass
     * already registers.
     */
    private fun screenWithSelfScopedRegion(): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Chip
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun Screen(
                title: String,
                modifier: Modifier = Modifier,
            ) {
                Column(modifier = modifier) {
                    Chip(onClick = {}) { Text(title, modifier = Modifier.weight(1f)) }
                    Text(title)
                }
            }
        """.trimIndent(),
    )

    /**
     * The ordinary version of the same problem: a component Dootah keeps native
     * that is given a weight by the column it sits in.
     */
    private fun screenWithWeightedComponent(): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Icon
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun Screen(
                title: String,
                modifier: Modifier = Modifier,
            ) {
                Column(modifier = modifier) {
                    Icon(title, modifier = Modifier.weight(1f))
                    Text(title)
                }
            }
        """.trimIndent(),
    )

    /**
     * The same screen after an ordinary edit: a line added above, and a
     * component's text changed. Neither touches the components kept native.
     */
    private fun editedScreen(): SourceFile = SourceFile(
        name = "Screen.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Button
            import androidx.compose.material3.Icon
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.graphics.Color
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun Screen(
                title: String,
                onSave: () -> Unit,
                modifier: Modifier = Modifier,
            ) {
                val heading = "Now with a heading"
                Column(modifier = modifier) {
                    Text(heading)
                    Icon(title)
                    Text(title, color = Color(0xFF2196F3L))
                    Button(onClick = onSave) { Text("Save now") }
                }
            }
        """.trimIndent(),
    )

}

