package dev.dootah.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * The generator emits Kotlin as text, so nothing about it is type-checked until
 * something compiles it. Without this test a generator could produce plausible
 * source that only fails much later, in the bundle build of whoever ran it.
 *
 * The generated source is compiled against the real bundle runtime rather than
 * a stub, because the point is that it works with the code it ships beside.
 */
class GeneratedSourceCompilesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the generated bundle compiles for the JavaScript target`() {

        val generated = generate(
            """
                package com.example

                import androidx.compose.foundation.layout.Column
                import androidx.compose.material3.Button
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable
                import dev.dootah.Bundlable

                @Bundlable
                @Composable
                fun OfferScreen() {
                    val price = 999
                    val discount = 100
                    val finalPrice = price - discount

                    Column {
                        Text("Dootah OTA Works")
                        Text("Price: Rs ${'$'}finalPrice")

                        Button(onClick = { }) {
                            Text("Buy")
                        }
                    }
                }
            """.trimIndent()
        )

        val outcome = compileForJs(generated)

        assertEquals(
            "generated bundle source did not compile:\n${outcome.output}",
            ExitCode.OK,
            outcome.exitCode,
        )
    }

    /**
     * A template starting with a value rather than a literal is the case that
     * needs the generator's leading empty string; without it the emitted
     * concatenation is `Int.plus(String)` and does not compile.
     */
    @Test
    fun `a template starting with a value still compiles`() {

        val generated = generate(
            """
                package com.example

                import androidx.compose.foundation.layout.Column
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable
                import dev.dootah.Bundlable

                @Bundlable
                @Composable
                fun CountScreen() {
                    val count = 3
                    Column {
                        Text("${'$'}count items left")
                    }
                }
            """.trimIndent()
        )

        val outcome = compileForJs(generated)

        assertEquals(
            "generated bundle source did not compile:\n${outcome.output}",
            ExitCode.OK,
            outcome.exitCode,
        )
    }

    private fun generate(screenSource: String): File {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(SourceFile("Screen.kt", screenSource)),
            mode = "extract",
        )

        assertTrue("extraction failed: ${result.messages}", result.succeeded)
        assertTrue("nothing was generated", result.generatedSources().isNotEmpty())

        return result.generatedDirectory
    }

    private class JsCompilation(val exitCode: ExitCode, val output: String)

    private fun compileForJs(generatedDirectory: File): JsCompilation {

        val runtimeSources = File(requiredProperty("dootah.runtime.sources"))
        val stdlib = requiredProperty("dootah.js.stdlib")
        val output = temporaryFolder.newFolder()

        val sources = (generatedDirectory.walkTopDown() + runtimeSources.walkTopDown())
            .filter { it.isFile && it.extension == "kt" }
            .map { it.absolutePath }
            .sorted()
            .toList()

        val arguments = buildList {
            add("-Xir-produce-klib-file")
            add("-libraries")
            add(stdlib)
            add("-ir-output-dir")
            add(output.absolutePath)
            add("-ir-output-name")
            add("dootah-bundle")
            addAll(sources)
        }

        val captured = ByteArrayOutputStream()
        val exitCode = PrintStream(captured, true).use { stream ->
            K2JSCompiler().exec(stream, *arguments.toTypedArray())
        }

        return JsCompilation(exitCode, captured.toString())
    }

    private fun requiredProperty(name: String): String =
        System.getProperty(name)
            ?: error(
                "System property '$name' is not set. The Gradle test task supplies it; " +
                    "running these tests outside Gradle is not supported."
            )
}
