package dev.dootah.compiler

import dev.dootah.contract.BundleRequirements
import dev.dootah.contract.ContractJson
import dev.dootah.contract.ContractValidation
import dev.dootah.contract.InstalledContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one invariant publishing rests on: identical source agrees with itself.
 *
 * A bundle is checked against a contract, and the two are produced by two
 * separate compilations of the same source -- the app's own build records what
 * it can be asked for, and the extraction pass records what a bundle asks. If
 * those two disagree about source neither of them has changed, then every real
 * publication is checked against a comparison that means nothing, and the
 * failures it reports are noise a developer can only learn to ignore.
 *
 * So: both passes, the same source, and the real validator in between. Nothing
 * here asserts on a name or a rendering; it asserts that the app can supply what
 * the bundle asks for, which is the only question the check exists to answer.
 */
class ContractAgreementTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * A handler that reads a value declared at the top of the screen.
     *
     * `val view = LocalView.current` and a handler that uses it is the shape
     * most of a real app's buttons have. Both passes move that declaration in
     * front of the body, so both can see it.
     */
    @Test
    fun `a handler reading a prologue value agrees`() {

        assertAgrees(
            SourceFile(
                name = "Screen.kt",
                contents = """
                    package com.example

                    import androidx.compose.material3.IconButton
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable

                    class Haptics
                    fun Haptics.tap() {}

                    @Composable
                    fun currentHaptics(): Haptics = Haptics()

                    @Composable
                    fun BackButton(onClick: () -> Unit) {
                        val haptics = currentHaptics()
                        IconButton(
                            onClick = {
                                onClick()
                                haptics.tap()
                            },
                        ) {
                            Text("back")
                        }
                    }
                """.trimIndent(),
            ),
            asks = """onClick();haptics.tap()""",
        )
    }

    /**
     * A handler whose first statement calls through a top-level constant.
     *
     * `AUDIO_FORMAT.updateInt(format); onDismissRequest()` is the shape of every
     * preference dialog in a settings screen. The constant is folded away before
     * the app's pass sees it and is still a name to the extraction pass, which is
     * exactly the kind of difference that has to not matter.
     */
    @Test
    fun `a handler calling through a constant agrees`() {

        assertAgrees(
            SourceFile(
                name = "Dialog.kt",
                contents = """
                    package com.example

                    import androidx.compose.material3.Button
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable

                    const val AUDIO_FORMAT = "audio_format"

                    fun String.updateInt(value: Int) {}

                    @Composable
                    fun AudioFormatDialog(format: Int, onDismissRequest: () -> Unit) {
                        Button(
                            onClick = {
                                AUDIO_FORMAT.updateInt(format)
                                onDismissRequest()
                            },
                        ) {
                            Text("confirm")
                        }
                    }
                """.trimIndent(),
            ),
            asks = "\"audio_format\".updateInt(format);onDismissRequest()",
        )
    }

    /**
     * A handler calling an extension declared inside an object.
     *
     * `view.slightHapticFeedback()`, imported as a member, is handed the object
     * as its dispatch receiver and `view` as its extension receiver. Reading the
     * first argument of the call reported the object, so the app named the
     * action `slightHapticFeedback()` while the bundle, reading source, asked
     * for `view.slightHapticFeedback()`.
     */
    @Test
    fun `a handler calling an object's extension agrees`() {

        assertAgrees(
            SourceFile(
                name = "Back.kt",
                contents = """
                    package com.example

                    import androidx.compose.material3.IconButton
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import com.example.Haptics.tap

                    class Feedback

                    object Haptics {
                        fun Feedback.tap(): Boolean = true
                    }

                    @Composable
                    fun currentFeedback(): Feedback = Feedback()

                    @Composable
                    fun BackButton(onClick: () -> Unit) {
                        val feedback = currentFeedback()
                        IconButton(
                            onClick = {
                                onClick()
                                feedback.tap()
                            },
                        ) {
                            Text("back")
                        }
                    }
                """.trimIndent(),
            ),
            asks = """onClick();feedback.tap()""",
        )
    }

    /**
     * A handler whose statement produces a value nobody wants.
     *
     * A `() -> Unit` throws away what its last statement produced, and the
     * throwing away is a node of its own wrapped around the call -- so the app's
     * pass, looking for a call, found none and registered no action at all.
     */
    @Test
    fun `a handler whose last statement returns a value agrees`() {

        assertAgrees(
            SourceFile(
                name = "Toggle.kt",
                contents = """
                    package com.example

                    import androidx.compose.material3.IconButton
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable

                    fun record(label: String): Boolean = true

                    @Composable
                    fun ToggleButton(onClick: () -> Unit) {
                        IconButton(
                            onClick = {
                                onClick()
                                record("tapped")
                            },
                        ) {
                            Text("toggle")
                        }
                    }
                """.trimIndent(),
            ),
            asks = """onClick();record("tapped")""",
        )
    }

    /**
     * A handler reading a delegated `var` declared at the top of the screen.
     *
     * `var filename by remember { … }` is read through a generated accessor
     * rather than as a value, so the app's pass saw no name to put in the
     * action, dropped the whole handler, and the bundle asked for it anyway.
     */
    @Test
    fun `a handler reading a delegated value agrees`() {

        assertAgrees(
            SourceFile(
                name = "Rename.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.IconButton
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import androidx.compose.runtime.getValue
                    import androidx.compose.runtime.setValue
                    import androidx.compose.runtime.mutableStateOf
                    import androidx.compose.runtime.remember

                    @Composable
                    fun RenameDialog(onConfirm: (String) -> Unit, onDismissRequest: () -> Unit) {
                        var filename by remember { mutableStateOf("") }
                        Column {
                            Text("rename the file")
                            IconButton(
                                onClick = {
                                    onConfirm(filename)
                                    onDismissRequest()
                                },
                            ) {
                                Text("rename")
                            }
                        }
                    }
                """.trimIndent(),
            ),
            asks = """onConfirm(filename);onDismissRequest()""",
        )
    }

    /**
     * A `when` over a subject, kept as the native region it is.
     *
     * Such a `when` is lowered into a block that evaluates the subject once and
     * branches on the temporary, and the branch alone reads a name that block
     * declares -- so the app refused to keep any of them, while the extraction
     * pass, reading source, kept them and named a region the app had not got.
     */
    @Test
    fun `a when over a subject agrees`() {

        assertAgrees(
            SourceFile(
                name = "Selection.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable

                    sealed interface Selection {
                        object Idle : Selection
                        data class One(val label: String) : Selection
                        data class Two(val label: String) : Selection
                    }

                    @Composable
                    fun Scoped(label: String) {
                        Text(label)
                    }

                    @Composable
                    fun Page(selection: Selection) {
                        Column {
                            Text("head")
                            when (selection) {
                                is Selection.One -> Scoped(selection.label)
                                is Selection.Two -> Scoped(selection.label)
                                Selection.Idle -> {}
                            }
                        }
                    }
                """.trimIndent(),
            ),
            asks = "!<conditional>@",
        )
    }

    /**
     * One component called twice, given different arguments each time.
     *
     * The app registered whichever call it walked into first with the most
     * arguments, so a `Text(text, textAlign)` beside a `Text(text, modifier)`
     * left the bundle asking for an argument the app had refused -- and which of
     * the two won depended on the order of the walk.
     */
    @Test
    fun `a component given different arguments at two call sites agrees`() {

        assertAgrees(
            SourceFile(
                name = "Labels.kt",
                contents = """
                    package com.example

                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable

                    @Composable
                    fun Badge(label: String, weight: Int = 0, count: Int = 0) {}

                    @Composable
                    fun Labels(heading: String, body: String) {
                        Column {
                            Text("labels")
                            Badge(label = heading, weight = 1)
                            Badge(label = body, count = 2)
                        }
                    }
                """.trimIndent(),
            ),
            asks = "com.example.Badge(",
        )
    }

    /**
     * Taking one element out changes that screen and leaves the rest alone.
     *
     * The other half of the invariant. Agreement on unchanged source is worth
     * little if any edit rewrites what every other screen asks for: a developer
     * could never tell the consequences of a change from the noise around it,
     * and every publication would have to be read as though the whole app had
     * been rewritten.
     */
    @Test
    fun `removing one element changes only the screen it was in`() {

        val before = compile(twoScreens(withSubtitle = true), mode = "extract").requirements()
        val after = compile(twoScreens(withSubtitle = false), mode = "extract").requirements()

        val untouched = "com.example.Sidebar"

        assertEquals(
            "the same screens should be described either way",
            before.screens.map { screen -> screen.id }.sorted(),
            after.screens.map { screen -> screen.id }.sorted(),
        )

        assertEquals(
            "a screen the edit did not touch must ask for exactly what it did",
            before.screens.single { it.id.startsWith(untouched) },
            after.screens.single { it.id.startsWith(untouched) },
        )

        val edited = "com.example.Detail"

        assertTrue(
            "the edited screen should have stopped asking for something",
            before.screens.single { it.id.startsWith(edited) } !=
                after.screens.single { it.id.startsWith(edited) },
        )
    }

    private fun twoScreens(withSubtitle: Boolean) = SourceFile(
        name = "Screens.kt",
        contents = """
            package com.example

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Text
            import androidx.compose.material3.TextStyle
            import androidx.compose.runtime.Composable

            @Composable
            fun Detail(title: String, subtitle: String) {
                Column {
                    Text(text = title)
                    ${if (withSubtitle) """Text(text = subtitle, style = TextStyle())""" else ""}
                }
            }

            @Composable
            fun Sidebar(label: String) {
                Column {
                    Text(text = label)
                    Text(text = "fixed")
                }
            }
        """.trimIndent(),
    )

    /**
     * Compiles [source] both ways and asks the real validator whether the app
     * can carry the bundle.
     *
     * [asks] is what the bundle must be found asking the app for, matched from
     * the start so that a region named by a digest of its own text can be named
     * here too. Without it these would all pass for a screen that turned out to
     * ask for nothing at all, which is the one way a test about agreement can
     * agree by accident.
     */
    private fun assertAgrees(source: SourceFile, asks: String) {

        val app = compile(source, mode = "intercept")
        val bundle = compile(source, mode = "extract")

        val installed = app.installedContract()
        val required = bundle.requirements()

        val wanted = required.screens.flatMap { screen ->
            screen.capabilities.map { it.id } + screen.adapters.map { it.id } + screen.resources
        }

        assertTrue(
            "the bundle never asks for '$asks'; it asks for $wanted\n" +
                "refused: ${bundle.rejectionReport()}\nkept native: ${bundle.degradationReport()}",
            wanted.any { want -> want.startsWith(asks) },
        )

        val fatal = ContractValidation.validate(installed, required)
            .filter { finding -> finding.isFatal }
            .map { finding -> finding.render() }

        assertEquals("identical source disagreed with itself", emptyList<String>(), fatal)
    }

    private fun compile(source: SourceFile, mode: String): CompilationResult {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(source),
            mode = mode,
        )

        assertTrue("$mode failed: ${result.messages}", result.succeeded)
        return result
    }

    /** The per-screen fragments the app's build wrote, merged as the build merges them. */
    private fun CompilationResult.installedContract(): InstalledContract {

        val fragments = installedContractFragment()
            .split(FRAGMENT_SEPARATOR)
            .filter { text -> text.isNotBlank() }
            .map { text -> ContractJson.readContract(text.restoreBrace()) }

        return InstalledContract(
            runtimeVersion = fragments.first().runtimeVersion,
            screens = fragments.flatMap { fragment -> fragment.screens },
        )
    }

    private fun CompilationResult.requirements(): BundleRequirements {

        val fragments = requirementsFragment()
            .split(FRAGMENT_SEPARATOR)
            .filter { text -> text.isNotBlank() }
            .map { text -> ContractJson.readRequirements(text.restoreBrace()) }

        return BundleRequirements(
            runtimeVersion = fragments.first().runtimeVersion,
            screens = fragments.flatMap { fragment -> fragment.screens },
        )
    }

    /** Puts back the closing brace the split above consumed. */
    private fun String.restoreBrace(): String = if (trimEnd().endsWith("}")) this else "$this\n}"

    private companion object {
        /** Where one fragment file ends and the next begins, as the readers join them. */
        const val FRAGMENT_SEPARATOR = "\n}\n"
    }
}
