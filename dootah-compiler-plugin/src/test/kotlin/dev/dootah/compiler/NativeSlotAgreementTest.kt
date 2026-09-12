package dev.dootah.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A native slot is decided twice, by two separate compilations: the extraction
 * pass names the slots a bundle refers to, and the app's own build registers
 * what each one draws. If the two disagree the app renders a hole.
 *
 * These run both passes over the same source and check that the names line up,
 * which is the only thing standing between the design and a silently empty
 * region on a real screen.
 */
class NativeSlotAgreementTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the app registers every slot the bundle refers to`() {

        val source = screenWithNativeComponents()

        val extracted = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "extract",
        )

        assertTrue("extraction failed: ${extracted.messages}", extracted.succeeded)

        val intercepted = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "intercept",
        )

        assertTrue("interception failed: ${intercepted.messages}", intercepted.succeeded)

        val bundleSlots = SLOT_PATTERN
            .findAll(extracted.generatedSources().values.joinToString("\n"))
            .map { it.value }
            .toSet()

        val compiled = intercepted.compiledClassText("com/example/ScreenKt.class")

        assertTrue("the bundle refers to no native slots", bundleSlots.isNotEmpty())

        assertEquals(
            "the app does not register every slot the bundle refers to.\n" +
                "bundle refers to: $bundleSlots",
            emptyList<String>(),
            bundleSlots.filterNot { slot -> compiled.contains(slot) },
        )
    }

    /**
     * The lambda the app registers has to be composable, or the Compose compiler
     * will not lower it and the component cannot draw.
     *
     * Checked by compiling with the real Compose plugin in front of nothing: a
     * composable lambda that Compose did not lower leaves a call with no
     * composer, which fails the build. That the fixture compiles at all is the
     * assertion.
     */
    /**
     * The case that matters, and the one a same-source check misses entirely.
     *
     * An update exists precisely because the source changed. The APK was built
     * from one version and the bundle is published from another, so a slot name
     * that depends on where a component sits in the file is renamed by any edit
     * above it -- and the app, still registering the old names, quietly draws
     * nothing where those components were.
     *
     * This is what happened on a device: an unrelated edit ten lines higher made
     * a native component disappear from a shipped screen.
     */
    @Test
    fun `slot names survive an edit elsewhere in the file`() {

        val installed = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(screenWithNativeComponents()),
            mode = "intercept",
        )

        assertTrue("interception failed: ${installed.messages}", installed.succeeded)

        val published = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(editedScreen()),
            mode = "extract",
        )

        assertTrue("extraction failed: ${published.messages}", published.succeeded)

        val bundleSlots = SLOT_PATTERN
            .findAll(published.generatedSources().values.joinToString("\n"))
            .map { it.value }
            .toSet()

        val compiled = installed.compiledClassText("com/example/ScreenKt.class")

        assertTrue("the bundle refers to no native slots", bundleSlots.isNotEmpty())

        assertEquals(
            "an edit renamed slots the installed app still registers under the old names.\n" +
                "bundle refers to: $bundleSlots",
            emptyList<String>(),
            bundleSlots.filterNot { slot -> compiled.contains(slot) },
        )
    }

    /**
     * A component with a receiver, called without one of its defaults.
     *
     * The two passes read a call's arguments from different places. Extraction
     * reads the resolved argument mapping, which holds only the arguments the
     * call supplies and no receiver. The app's build reads the callee's
     * parameters against the argument list, which *is* indexed over receivers --
     * so taking a parameter's position after filtering the receivers out shifts
     * every later one, and the call gets named with an argument it never passed.
     *
     * The app then registers `Star(label|tint)#0` while the bundle asks for
     * `Star(label)#0`, and the component is silently missing from the screen.
     */
    @Test
    fun `slot names agree for a component with a receiver and an unsupplied default`() {

        val source = screenWithObjectComponent()

        val extracted = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "extract",
        )

        assertTrue("extraction failed: ${extracted.messages}", extracted.succeeded)

        val intercepted = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "intercept",
        )

        assertTrue("interception failed: ${intercepted.messages}", intercepted.succeeded)

        val bundleSlots = SLOT_PATTERN
            .findAll(extracted.generatedSources().values.joinToString("\n"))
            .map { it.value }
            .toSet()

        val compiled = intercepted.compiledClassText("com/example/ScreenKt.class")

        assertTrue(
            "the bundle refers to no component reached through an object: $bundleSlots",
            bundleSlots.any { slot -> slot.startsWith("Star(") },
        )

        assertEquals(
            "the app does not register every slot the bundle refers to.\n" +
                "bundle refers to: $bundleSlots",
            emptyList<String>(),
            bundleSlots.filterNot { slot -> compiled.contains(slot) },
        )
    }

    /**
     * A component that reads the scope of the layout around it.
     *
     * The app registers a native component as its own lambda, lifted out of the
     * layout it was written in, so there is no `ColumnScope` left for it to
     * read. The app's build cannot construct that lambda at all -- it used to
     * fail in the backend with `No mapping for symbol: $this$Column` -- so both
     * passes have to refuse the case: the app must not register what it cannot
     * build, and the bundle must not name what the app did not register.
     *
     * Checked for a component declared on the scope, and for the far more
     * ordinary case of one whose modifier calls `weight`.
     */
    @Test
    fun `neither pass accepts a component that reads the layout's scope`() {

        listOf(
            "a component declared on ColumnScope" to screenWithScopedComponent(),
            "a component whose modifier uses weight" to screenWithWeightedComponent(),
        ).forEach { (description, source) ->

            val intercepted = compileWithDootah(
                workingDirectory = temporaryFolder.newFolder(),
                sources = listOf(source),
                mode = "intercept",
            )

            assertTrue(
                "the app's build failed on $description: ${intercepted.messages}",
                intercepted.succeeded,
            )

            val extracted = compileWithDootah(
                workingDirectory = temporaryFolder.newFolder(),
                sources = listOf(source),
                mode = "extract",
            )

            assertTrue(
                "extraction failed on $description: ${extracted.messages}",
                extracted.succeeded,
            )

            val bundleSlots = SLOT_PATTERN
                .findAll(extracted.generatedSources().values.joinToString("\n"))
                .map { it.value }
                .toSet()

            val compiled = intercepted.compiledClassText("com/example/ScreenKt.class")

            assertEquals(
                "the bundle names a slot the app did not register, for $description.\n" +
                    "bundle refers to: $bundleSlots",
                emptyList<String>(),
                bundleSlots.filterNot { slot -> compiled.contains(slot) },
            )

            assertTrue(
                "$description was not reported as unsupported",
                extracted.rejectionReport().orEmpty().contains("scope"),
            )
        }
    }

    /**
     * Removing one of several identical components, which renames the rest.
     *
     * Shape-based naming fixed the case where an edit above a component renamed
     * it. It does not fix this one: three identical icon buttons are numbered
     * #0, #1, #2, so deleting the middle one slides the third into its place.
     * The bundle then asks for #1 and the installed app, which still has all
     * three, hands back a different component under a name that exists. Nothing
     * is missing, so the missing-component check sees nothing wrong.
     *
     * What gives it away is how many of that shape each source had, which both
     * sides now carry.
     */
    @Test
    fun `removing one of several identical components is caught by the counts`() {

        val installed = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(toolbar(buttons = 3)),
            mode = "intercept",
        )

        assertTrue("interception failed: ${installed.messages}", installed.succeeded)

        val published = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(toolbar(buttons = 2)),
            mode = "extract",
        )

        assertTrue("extraction failed: ${published.messages}", published.succeeded)

        val generated = published.generatedSources().values.joinToString("\n")
        val compiled = installed.compiledClassText("com/example/ScreenKt.class")

        // The name the bundle asks for exists in the installed app, so nothing
        // looks wrong -- this is exactly why the counts are needed.
        assertTrue(generated, generated.contains("IconButton(content|onClick)#1"))
        assertTrue("the app should still have that slot", compiled.contains("IconButton(content|onClick)#1"))

        // The two sources counted that shape differently, which is what the app
        // compares before it draws.
        assertTrue(compiled, compiled.contains("IconButton(content|onClick)=3"))
        // Quoted twice: the table is JSON, held in a Kotlin string literal.
        assertTrue(generated, generated.contains("""IconButton(content|onClick)\":2"""))
    }

    @Test
    fun `a registered slot is a composable lambda`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(screenWithNativeComponents()),
            mode = "intercept",
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        val compiled = result.compiledClassText("com/example/ScreenKt.class")

        assertTrue("no slots were registered", compiled.contains("dootahSlots"))

        // The component kept native is still reachable from the compiled class,
        // both as the fallback body and as the slot.
        assertTrue("the native component is missing", compiled.contains("Icon"))
    }

    @Test
    fun `passes the screen's values, callbacks and modifier through`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(screenWithNativeComponents()),
            mode = "intercept",
        )

        val compiled = result.compiledClassText("com/example/ScreenKt.class")

        assertTrue(
            "the screen's modifier was not carried",
            compiled.contains("dootahModifiedArguments"),
        )
        assertTrue("the screen's values were not carried", compiled.contains("title"))
        assertTrue("the screen's callbacks were not carried", compiled.contains("dootahCallbacks"))
        assertTrue("the callback name was not carried", compiled.contains("onSave"))
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
    private fun toolbar(buttons: Int): SourceFile = SourceFile(
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
            (1..buttons).joinToString("\n") {
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

    private companion object {
        /**
         * `Icon(name)#0`, `Text(color|text)#0`: what the component is, and which
         * one of those it is.
         *
         * Argument names are separated by `|`, not by a comma. Matching a comma
         * here instead found only single-argument slots, which quietly excused
         * every multi-argument one from the check these tests exist to make.
         */
        val SLOT_PATTERN = Regex("[A-Za-z0-9_]+\\([A-Za-z0-9_|]*\\)#[0-9]+")
    }
}

