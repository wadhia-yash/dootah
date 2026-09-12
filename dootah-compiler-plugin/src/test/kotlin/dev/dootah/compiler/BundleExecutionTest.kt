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
 * Compiles a generated bundle all the way to JavaScript and runs it.
 *
 * Everything upstream of this checks what the compiler believed it produced.
 * This checks what a device would actually get: the real artifact, evaluated in
 * a bare context like the one the Android isolate provides, answering the same
 * three calls the installed runtime makes.
 *
 * It is the only test that can catch a protocol change the generator and the
 * runtime agree on but the linked bundle does not honour.
 */
class BundleExecutionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `serves two screens independently`() {

        val bundle = buildBundle(twoScreens())

        assertTrue(
            bundle.screenIds,
            bundle.screenIds.contains("com.example.CartScreen") &&
                bundle.screenIds.contains("com.example.ProfileScreen"),
        )

        val cart = bundle.render("com.example.CartScreen", """{"unitPrice":100}""")
        val profile = bundle.render("com.example.ProfileScreen", PROFILE_ARGUMENTS)

        assertTrue(cart, cart.contains("Total: 100"))
        assertTrue(profile, profile.contains("Hello Ada"))

        // Neither screen can see the other's content.
        assertTrue(cart, !cart.contains("Hello"))
        assertTrue(profile, !profile.contains("Total"))
    }

    @Test
    fun `a button changes that screen's state and nothing else`() {

        val bundle = buildBundle(twoScreens())

        bundle.render("com.example.CartScreen", """{"unitPrice":100}""")
        bundle.render("com.example.ProfileScreen", PROFILE_ARGUMENTS)

        val afterOneTap = bundle.act("com.example.CartScreen", "add", """{"unitPrice":100}""")
        assertTrue(afterOneTap, afterOneTap.contains("Total: 200"))

        val afterTwoTaps = bundle.act("com.example.CartScreen", "add", """{"unitPrice":100}""")
        assertTrue(afterTwoTaps, afterTwoTaps.contains("Total: 300"))

        // The other screen declares `quantity` too, and it has not moved.
        val profile = bundle.render("com.example.ProfileScreen", PROFILE_ARGUMENTS)
        assertTrue(profile, profile.contains("Visits: 1"))
    }

    @Test
    fun `state survives a re-render`() {

        val bundle = buildBundle(twoScreens())

        bundle.render("com.example.CartScreen", """{"unitPrice":100}""")
        bundle.act("com.example.CartScreen", "add", """{"unitPrice":100}""")

        val rerendered = bundle.render("com.example.CartScreen", """{"unitPrice":100}""")

        assertTrue(rerendered, rerendered.contains("Total: 200"))
    }

    @Test
    fun `asks the app to invoke a callback`() {

        val bundle = buildBundle(twoScreens())

        val response = bundle.act("com.example.CartScreen", "checkout", """{"unitPrice":100}""")

        assertTrue(
            response,
            response.contains("""{"type":"invokeCallback","name":"onCheckout"}"""),
        )
    }

    @Test
    fun `reports a screen it does not implement`() {

        val bundle = buildBundle(twoScreens())

        val response = bundle.render("com.example.Missing", "{}")

        assertTrue(response, response.contains("unknownScreen"))
    }

    @Test
    fun `renders a branch chosen by a caller's argument`() {

        val bundle = buildBundle(twoScreens())

        val premium = bundle.render("com.example.ProfileScreen", """{"name":"Ada","premium":true}""")
        val standard =
            bundle.render("com.example.ProfileScreen", """{"name":"Ada","premium":false}""")

        assertTrue(premium, premium.contains("Premium member"))
        assertTrue(standard, standard.contains("Standard member"))
    }

    /**
     * A screen that is several components rather than one layout.
     *
     * This is the shape of a real toolbox: siblings laid out by whatever the
     * caller wrapped the call in. The bundle has to return them as one value
     * without inventing a layout, so it returns a fragment -- and the point of
     * running it is that the linked JavaScript really does emit one.
     */
    @Test
    fun `serves a screen whose body is several components`() {

        val bundle = buildBundle(siblingScreen())

        val rendered = bundle.render("com.example.ToolbarScreen", """{"label":"Undo"}""")

        assertTrue(rendered, rendered.contains(""""type":"fragment""""))
        assertTrue(rendered, rendered.contains("Undo"))
        assertTrue(rendered, rendered.contains("tail"))

        // No layout was added around them.
        assertTrue(rendered, !rendered.contains(""""type":"column""""))

        // And a component kept native is still named inside the fragment.
        assertTrue(rendered, rendered.contains(""""type":"native""""))
    }

    // ---- fixture ---------------------------------------------------------

    private fun siblingScreen(): SourceFile = SourceFile(
        name = "Toolbar.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Box
            import androidx.compose.material3.Icon
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun ToolbarScreen(label: String) {
                Box { Text(label) }
                Icon(label)
                Text("tail")
            }
        """.trimIndent(),
    )

    private fun twoScreens(): SourceFile = SourceFile(
        name = "Screens.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Button
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import dev.dootah.Bundlable

            @Bundlable
            @Composable
            fun CartScreen(unitPrice: Int, onCheckout: () -> Unit) {
                var quantity = 1
                Column {
                    Text("Total: " + unitPrice * quantity)
                    Button(onClick = { quantity = quantity + 1 }) { Text("Add") }
                    Button(onClick = onCheckout) { Text("Checkout") }
                }
            }

            @Bundlable
            @Composable
            fun ProfileScreen(name: String, premium: Boolean) {
                var quantity = 1
                Column {
                    Text("Hello " + name)
                    Text("Visits: " + quantity)
                    if (premium) {
                        Text("Premium member")
                    } else {
                        Text("Standard member")
                    }
                }
            }
        """.trimIndent(),
    )

    // ---- building and running -------------------------------------------

    private fun buildBundle(source: SourceFile): BundleSession {

        val extracted = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = "extract",
        )

        assertTrue("extraction failed: ${extracted.messages}", extracted.succeeded)

        val runtimeSources = File(requiredProperty("dootah.runtime.sources"))
        val stdlib = requiredProperty("dootah.js.stdlib")

        // Canonical paths: the temporary folder lives under a symlinked
        // /var/folders on macOS, and the linker matches the module it was told
        // to include against the path it resolved the library to.
        val klibDirectory = temporaryFolder.newFolder().canonicalFile
        val bundleDirectory = temporaryFolder.newFolder().canonicalFile

        val sources = (extracted.generatedDirectory.walkTopDown() + runtimeSources.walkTopDown())
            .filter { it.isFile && it.extension == "kt" }
            .map { it.absolutePath }
            .sorted()
            .toList()

        compile(
            buildList {
                add("-Xir-produce-klib-file")
                add("-libraries")
                add(stdlib)
                add("-ir-output-dir")
                add(klibDirectory.absolutePath)
                add("-ir-output-name")
                add(MODULE_NAME)
                addAll(sources)
            }
        )

        val klib = File(klibDirectory, "$MODULE_NAME.klib")

        check(klib.isFile) {
            "the klib step produced no $MODULE_NAME.klib. Directory holds: " +
                klibDirectory.walkTopDown().joinToString(", ") { it.name }
        }

        compile(
            listOf(
                "-Xir-produce-js",
                "-Xinclude=${klib.absolutePath}",
                "-libraries", stdlib,
                "-ir-output-dir", bundleDirectory.absolutePath,
                "-ir-output-name", MODULE_NAME,
                "-module-kind", "umd",
                "-main", "noCall",
                "-Xir-dce",
            )
        )

        return BundleSession(File(bundleDirectory, "$MODULE_NAME.js"), temporaryFolder)
    }

    private fun compile(arguments: List<String>) {

        val captured = ByteArrayOutputStream()
        val exitCode = PrintStream(captured, true).use { stream ->
            K2JSCompiler().exec(stream, *arguments.toTypedArray())
        }

        assertEquals("bundle compilation failed:\n$captured", ExitCode.OK, exitCode)
    }

    private fun requiredProperty(name: String): String =
        System.getProperty(name)
            ?: error(
                "System property '$name' is not set. The Gradle test task supplies it; " +
                    "running these tests outside Gradle is not supported."
            )

    private companion object {
        const val MODULE_NAME = "dootah-bundle"

        /**
         * Every declared argument is required.
         *
         * A missing one is a disagreement between the app and the bundle, and
         * the bundle reports it as a failure rather than substituting a default
         * and rendering a screen nobody asked for.
         */
        const val PROFILE_ARGUMENTS = """{"name":"Ada","premium":false}"""
    }
}

/**
 * A bundle, and the calls made against it so far.
 *
 * Each call replays the whole sequence in a fresh Node process, so remote state
 * accumulates exactly as it does inside a long-lived isolate while the test
 * needs nothing more than a process per assertion.
 */
private class BundleSession(
    private val bundle: File,
    private val workingDirectory: TemporaryFolder,
) {

    private val calls = mutableListOf<String>()

    val screenIds: String get() = evaluate("screenIds()")

    fun render(screenId: String, argumentsJson: String): String =
        evaluate("renderScreen(${literal(screenId)}, ${literal(argumentsJson)})")

    fun act(screenId: String, action: String, argumentsJson: String): String =
        evaluate(
            "handleAction(${literal(screenId)}, ${literal(action)}, ${literal(argumentsJson)})"
        )

    private fun evaluate(expression: String): String {

        calls += expression

        val script = workingDirectory.newFile()

        script.writeText(
            """
            const fs = require('fs');
            const vm = require('vm');

            // A bare context, like the Android isolate: no module system and
            // nothing but a global object for the UMD wrapper to install on.
            const context = vm.createContext({});
            vm.runInContext(fs.readFileSync(${literal(bundle.absolutePath)}, 'utf8'), context);

            const dootah = context['dootah-bundle'];
            let last = '';
            ${calls.joinToString("\n") { "last = dootah.$it;" }}
            process.stdout.write(String(last));
            """.trimIndent()
        )

        val process = ProcessBuilder("node", script.absolutePath)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()

        check(process.waitFor() == 0) { "running the bundle failed:\n$output" }

        return output
    }

    private fun literal(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
