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
        /** `Icon(name)#0`: what the component is, and which one of those it is. */
        val SLOT_PATTERN = Regex("[A-Za-z0-9_]+\\([A-Za-z0-9_,]*\\)#[0-9]+")
    }
}

