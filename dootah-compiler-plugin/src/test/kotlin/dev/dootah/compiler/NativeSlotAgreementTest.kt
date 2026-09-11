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

        val registeredSlots = SLOT_PATTERN
            .findAll(intercepted.compiledClassText("com/example/ScreenKt.class"))
            .map { it.value }
            .toSet()

        assertTrue("the bundle refers to no native slots", bundleSlots.isNotEmpty())

        assertEquals(
            "the app does not register every slot the bundle refers to",
            emptySet<String>(),
            bundleSlots - registeredSlots,
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

    private companion object {
        val SLOT_PATTERN = Regex("slot@[0-9]+")
    }
}

