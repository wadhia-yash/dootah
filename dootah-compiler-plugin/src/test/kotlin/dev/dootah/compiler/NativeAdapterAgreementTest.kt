package dev.dootah.compiler

import org.junit.Assert.assertEquals
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
     */
    @Test
    fun `neither pass accepts a component that reads the layout's scope`() {

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
                "$description was not reported as unsupported",
                extracted.rejectionReport().orEmpty().contains("scope"),
            )
        }
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
        )
    }

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

